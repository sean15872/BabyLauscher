"""Export the publicly available Apache-2.0 DistilHuBERT model to fixed-input ONNX.

Run in GitHub Actions on Linux. Both the weights and preprocessing must be compatible
with the model card. No recordings are sent to Hugging Face at inference time.
"""
import json
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
import torch
from huggingface_hub import snapshot_download
from transformers import AutoModelForAudioClassification, AutoFeatureExtractor

MODEL_ID = "AmeerHesham/distilhubert-finetuned-baby_cry"
# Immutable source revision, so model updates cannot silently change the APK.
MODEL_REV = "7b9a7700ef09ab81ea597cb4bbb87c283536ae2c"
DEST = Path("app/src/main/assets/model/babycry.onnx")
N = 160_000
LABELS = ["belly_pain", "burping", "discomfort", "hungry", "tired"]


def main():
    torch.set_num_threads(2)
    local_dir = snapshot_download(repo_id=MODEL_ID, revision=MODEL_REV,
                                  allow_patterns=["config.json", "preprocessor_config.json", "model.safetensors"])
    extractor = AutoFeatureExtractor.from_pretrained(local_dir)
    assert extractor.sampling_rate == 16000 and extractor.do_normalize
    model = AutoModelForAudioClassification.from_pretrained(local_dir).eval()
    assert [model.config.id2label[i] for i in range(5)] == LABELS

    class OnlyLogits(torch.nn.Module):
        def __init__(self, model):
            super().__init__()
            self.model = model
        def forward(self, input_values, attention_mask):
            return self.model(input_values=input_values, attention_mask=attention_mask).logits

    wrapped = OnlyLogits(model).eval()
    t = torch.arange(N, dtype=torch.float32)
    wave = (torch.sin(2 * torch.pi * 415 * t / 16000) * 0.15)[None, :]
    mask = torch.ones((1, N), dtype=torch.int64)
    mask[:, 8 * 16000:] = 0
    wave[:, 8 * 16000:] = 0

    DEST.parent.mkdir(parents=True, exist_ok=True)
    with torch.inference_mode():
        expected = wrapped(wave, mask).detach().cpu().numpy()
        # Torch 2.5 defaults to the established legacy exporter unless dynamo=True.
        torch.onnx.export(wrapped, (wave, mask), str(DEST), opset_version=17,
                          input_names=["input_values", "attention_mask"],
                          output_names=["logits"], do_constant_folding=True,
                          export_params=True, dynamo=False)

    model_proto = onnx.load(str(DEST))
    onnx.checker.check_model(model_proto)
    test = ort.InferenceSession(str(DEST), providers=["CPUExecutionProvider"])
    feeds = {"input_values": wave.numpy()}
    if "attention_mask" in {i.name for i in test.get_inputs()}:
        feeds["attention_mask"] = mask.numpy()
    actual = test.run(["logits"], feeds)[0]
    max_diff = float(np.max(np.abs(expected - actual)))
    print(f"Verified ONNX output shape={actual.shape}, maximum absolute logit diff={max_diff:.6g}")
    if actual.shape != (1, 5) or not np.isfinite(actual).all() or max_diff > 0.05:
        raise RuntimeError("ONNX validation failed: differences too large or wrong shape")
    metadata = {"model": MODEL_ID, "revision": MODEL_REV, "labels": LABELS,
                "sample_rate": 16000, "samples": N, "max_abs_logit_difference": max_diff}
    Path("tools/model_build_info.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(f"Model ready: {DEST} ({DEST.stat().st_size/1024/1024:.1f} MiB)")

if __name__ == "__main__":
    main()
