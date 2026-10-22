package dev.babylauscher.offline;

import android.content.Context;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * On-device inference only. We never send audio to a network endpoint.
 * Model: AmeerHesham/distilhubert-finetuned-baby_cry (Apache-2.0).
 * IMPORTANT: Predictions are exploratory, not a diagnosis or certainty about an infant's needs.
 */
public final class CryClassifier {
    private static final String MODEL_ASSET = "model/babycry.onnx";
    private static final int RATE = 16000;
    private static final int MODEL_SAMPLES = 10 * RATE;
    private static OrtEnvironment environment;
    private static OrtSession session;

    // Verified against the model card's id2label. Do not reorder.
    public static final String[] LABELS = { "belly_pain", "burping", "discomfort", "hungry", "tired" };

    public static final class Prediction {
        public final double[] scores;
        private Prediction(double[] scores) { this.scores = scores; }
    }

    private CryClassifier() { }

    private static synchronized OrtSession getSession(Context context) throws Exception {
        if (session != null) return session;
        File output = new File(context.getFilesDir(), "babycry-onnx-v05.onnx");
        if (!output.isFile() || output.length() < 1024 * 1024) {
            // Assets cannot necessarily be opened as real filesystem paths.
            // Store in app-private files; no INTERNET permission or remote download.
            File tmp = new File(context.getFilesDir(), "babycry.tmp");
            try (InputStream in = context.getAssets().open(MODEL_ASSET);
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[65536]; int read;
                while ((read = in.read(buf)) != -1) out.write(buf, 0, read);
            }
            if (!tmp.renameTo(output)) throw new IllegalStateException("KI-Modell konnte nicht lokal gespeichert werden");
        }
        environment = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(2);
        options.setInterOpNumThreads(1);
        try {
            session = environment.createSession(output.getAbsolutePath(), options);
        } finally {
            options.close();
        }
        return session;
    }

    /** Returns the 16kHz, 10s float signal after per-clip normalization (real samples only). */
    static float[][] normalizePcm(byte[] pcm, int inputRate, long[][] mask) {
        if (inputRate < 8000 || inputRate > 96000 || pcm == null || pcm.length < 3200)
            throw new IllegalArgumentException("PCM zu kurz oder unbekannte Samplerate");
        int inputCount = pcm.length / 2;
        int activeSamples = Math.min(MODEL_SAMPLES,
                (int) Math.floor((double) inputCount * RATE / inputRate));
        if (activeSamples < RATE) throw new IllegalArgumentException("Mindestens eine Sekunde Audio erforderlich");
        float[][] values = new float[1][MODEL_SAMPLES];
        double mean = 0;
        for (int i = 0; i < activeSamples; i++) {
            // Linear interpolation with a fixed source rate; no external audio libraries.
            double at = ((double) i * inputRate) / RATE;
            int j0 = Math.min(inputCount - 1, (int) at);
            int j1 = Math.min(inputCount - 1, j0 + 1);
            double f = at - j0;
            double value = sample(pcm, j0) * (1.0 - f) + sample(pcm, j1) * f;
            values[0][i] = (float) value;
            mean += value;
            mask[0][i] = 1;
        }
        mean /= activeSamples;
        double variance = 0;
        for (int i = 0; i < activeSamples; i++) {
            double d = values[0][i] - mean;
            variance += d * d;
        }
        variance /= activeSamples;
        double divisor = Math.sqrt(variance + 1e-7);
        for (int i = 0; i < activeSamples; i++) {
            values[0][i] = (float) ((values[0][i] - mean) / divisor);
        }
        // The padded samples remain 0, as in a masked Hugging Face feature extractor.
        return values;
    }

    private static float sample(byte[] bytes, int i) {
        int lo = bytes[i * 2] & 255;
        int hi = bytes[i * 2 + 1];
        return (short) ((hi << 8) | lo) / 32768f;
    }

    public static Prediction classify(Context context, byte[] pcm, int inputRate) throws Exception {
        long[][] mask = new long[1][MODEL_SAMPLES];
        float[][] audio = normalizePcm(pcm, inputRate, mask);
        OrtSession model = getSession(context);
        OrtEnvironment env = environment;
        try (OnnxTensor input = OnnxTensor.createTensor(env, audio);
             OnnxTensor attentionMask = OnnxTensor.createTensor(env, mask)) {
            Map<String, OnnxTensor> tensors = new HashMap<>();
            Set<String> names = model.getInputNames();
            if (!names.contains("input_values")) throw new IllegalStateException("ONNX-Modell ohne input_values");
            tensors.put("input_values", input);
            if (names.contains("attention_mask")) tensors.put("attention_mask", attentionMask);
            try (OrtSession.Result result = model.run(tensors)) {
                float[][] logits = (float[][]) result.get(0).getValue();
                if (logits.length != 1 || logits[0].length != LABELS.length)
                    throw new IllegalStateException("ONNX-Ausgabe hat nicht fuenf Klassen");
                double max = -Double.MAX_VALUE;
                for (float v : logits[0]) {
                    if (!Float.isFinite(v)) throw new IllegalStateException("KI hat ungueltige Werte geliefert");
                    max = Math.max(max, v);
                }
                double[] relativeScores = new double[LABELS.length];
                double sum = 0;
                for (int i = 0; i < LABELS.length; i++) {
                    relativeScores[i] = Math.exp(logits[0][i] - max);
                    sum += relativeScores[i];
                }
                for (int i = 0; i < LABELS.length; i++) relativeScores[i] /= sum;
                return new Prediction(relativeScores);
            }
        } catch (OrtException e) {
            throw new IllegalStateException("ONNX-Auswertung fehlgeschlagen: " + e.getMessage(), e);
        }
    }
}
