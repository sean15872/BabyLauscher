"""Export a pretrained baby-cry HuBERT classifier to fixed-input ONNX.

v0.5.2: Replace two PyTorch advanced-indexing mask operations that can be
traced into ONNX Where/Scatter nodes with incompatible dimensions. Compare
outputs from the original PyTorch model and from the exported ONNX model.

If any step fails, abort the GitHub build; never ship a placeholder model.
"""
import json
from pathlib import Path
from types import MethodType

import numpy as np
import onnx
import onnxruntime as ort
import torch
from huggingface_hub import snapshot_download
from transformers import AutoModelForAudioClassification, AutoFeatureExtractor

MODEL_ID = "AmeerHesham/distilhubert-finetuned-baby_cry"
MODEL_REV = "7b9a7700ef09ab81ea597cb4bbb87c283536ae2c"
DEST = Path("app/src/main/assets/model/babycry.onnx")
N = 160_000
RATE = 16_000
LABELS = ["belly_pain", "burping", "discomfort", "hungry", "tired"]


def export_safe_feature_mask(self, feature_vector_length, attention_mask):
    """Prefix mask equivalent to HuBERT's indexed assignment + flip + cumsum.

    HuBERT 4.41.2 builds the mask by setting a single indexed element and
    flipping/cumsumming. Legacy torch.onnx may export this as a broken Where.
    A vector comparison has the same result for 1..N contiguous valid samples.
    """
    valid_input_lengths = attention_mask.sum(dim=-1)
    feature_lengths = self._get_feat_extract_output_lengths(valid_input_lengths).to(torch.long)
    positions = torch.arange(feature_vector_length, device=attention_mask.device)
    return positions.unsqueeze(0) < feature_lengths.unsqueeze(1)


class OnnxFriendlyLogits(torch.nn.Module):
    """Same learned weights, with export-friendly masked pooling (no in-place indexing)."""

    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, input_values, attention_mask):
        weighted = bool(self.model.config.use_weighted_layer_sum)
        outputs = self.model.hubert(
            input_values=input_values,
            attention_mask=attention_mask,
            output_hidden_states=weighted,
            return_dict=True,
        )
        if weighted:
            states = torch.stack(outputs.hidden_states, dim=1)
            weights = torch.nn.functional.softmax(self.model.layer_weights, dim=-1)
            hidden = (states * weights.view(-1, 1, 1)).sum(dim=1)
        else:
            hidden = outputs.last_hidden_state
        projected = self.model.projector(hidden)
        valid = export_safe_feature_mask(self.model.hubert, projected.shape[1], attention_mask)
        valid_float = valid.unsqueeze(-1).to(projected.dtype)
        # Equivalent to model.forward's hidden_states[~mask]=0 and masked mean,
        # but avoids advanced boolean indexing that can break ONNX shape inference.
        summed = (projected * valid_float).sum(dim=1)
        denominator = valid_float.sum(dim=1).clamp_min(1.0)
        return self.model.classifier(summed / denominator)


def fixture(valid_seconds, frequency):
    valid_samples = int(valid_seconds * RATE)
    t = torch.arange(valid_samples, dtype=torch.float32)
    wave = torch.zeros((1, N), dtype=torch.float32)
    mask = torch.zeros((1, N), dtype=torch.int64)
    wave[0, :valid_samples] = 0.15 * torch.sin(2 * torch.pi * frequency * t / RATE)
    mask[0, :valid_samples] = 1
    return wave, mask


def main():
    torch.set_num_threads(2)
    local_dir = snapshot_download(
        repo_id=MODEL_ID, revision=MODEL_REV,
        allow_patterns=["config.json", "preprocessor_config.json", "model.safetensors"],
    )
    extractor = AutoFeatureExtractor.from_pretrained(local_dir)
    if extractor.sampling_rate != RATE or not extractor.do_normalize:
        raise ValueError("Incompatible feature extractor")
    model = AutoModelForAudioClassification.from_pretrained(local_dir).eval()
    if [model.config.id2label[i] for i in range(5)] != LABELS:
        raise ValueError("Model class order is unexpected")

    # Check the original model against the rewritten mask/pooling BEFORE export.
    # Patch only the inner Hubert mask (the classification pooling is replaced
    # by OnnxFriendlyLogits); the trained parameters remain exactly unchanged.
    test_waves = [fixture(8, 415), fixture(3, 615)]
    with torch.inference_mode():
        reference = [model(input_values=w, attention_mask=m).logits.numpy()
                     for w, m in test_waves]
    model.hubert._get_feature_vector_attention_mask = MethodType(
        export_safe_feature_mask, model.hubert
    )
    wrapped = OnnxFriendlyLogits(model).eval()
    with torch.inference_mode():
        for (wave, mask), truth in zip(test_waves, reference):
            value = wrapped(wave, mask).numpy()
            diff = float(np.max(np.abs(value - truth)))
            print(f"PyTorch mask/pooling rewrite check: max difference={diff:.7g}", flush=True)
            if diff > 0.0005:
                raise RuntimeError("Rewritten pooling is not equivalent to source model")

    DEST.parent.mkdir(parents=True, exist_ok=True)
    wave, mask = test_waves[0]
    with torch.inference_mode():
        torch.onnx.export(
            wrapped, (wave, mask), str(DEST), opset_version=17,
            input_names=["input_values", "attention_mask"],
            output_names=["logits"], export_params=True,
            do_constant_folding=True, dynamo=False,
        )

    onnx.checker.check_model(str(DEST))
    session = ort.InferenceSession(str(DEST), providers=["CPUExecutionProvider"])
    names = {x.name for x in session.get_inputs()}
    if "input_values" not in names or "attention_mask" not in names:
        raise RuntimeError("Missing audio or mask input after ONNX export")
    max_diff = 0.0
    for (wave, mask), truth in zip(test_waves, reference):
        got = session.run(None, {"input_values": wave.numpy(),
                                 "attention_mask": mask.numpy()})[0]
        if got.shape != (1, len(LABELS)) or not np.isfinite(got).all():
            raise RuntimeError(f"Invalid ONNX output {got.shape}")
        diff = float(np.max(np.abs(got - truth)))
        print(f"ONNX vs ORIGINAL PyTorch ({int(mask.sum())/RATE:.0f}s): max difference={diff:.7g}", flush=True)
        max_diff = max(max_diff, diff)
        if diff > 0.05:
            raise RuntimeError("ONNX validation failed: output changed too much")

    metadata = {
        "model": MODEL_ID, "revision": MODEL_REV,
        "labels": LABELS, "sample_rate": RATE, "samples": N,
        "max_abs_logit_difference": max_diff,
        "export_patch": "v0.5.2 export-safe Hubert attention mask and masked pooling",
    }
    Path("tools/model_build_info.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(f"Verified model: {DEST} ({DEST.stat().st_size / 1024 / 1024:.1f} MiB)", flush=True)


if __name__ == "__main__":
    main()
