#!/usr/bin/env python3
"""Rebuild the openWakeWord-compatible feature front-end from permissively licensed sources.

Produces two float32 TFLite models that are drop-in compatible with openWakeWord's
`melspectrogram.tflite` and `embedding_model.tflite` (same tensor shapes, same math):

  melspectrogram.tflite  input  [batch, n_samples]  float32, raw int16-range PCM @ 16 kHz
                         output [batch, 1, n_frames, 32] float32 log-mel (dB, top_db=80)
                         n_frames = 1 + (n_samples - 512) // 160
                         NOTE: openWakeWord applies `x / 10 + 2` to this output in host code.
  embedding_model.tflite input  [batch, 76, 32, 1] float32 (transformed log-mel)
                         output [batch, 1, 1, 96]  float32 speech embedding

Provenance (why we rebuild instead of shipping openWakeWord's release files):
  * The melspectrogram is a fixed DSP transform (STFT n_fft=512, win=400 hann, hop=160,
    slaney mel 32 bins 60-3800 Hz, power_to_db with amin=1e-10, top_db=80), matching the
    parameters documented in openWakeWord's Apache-2.0 notebook
    `converting_google_speech_embedding_model.ipynb`. It contains no learned weights.
  * The embedding network weights are loaded directly from Google's `speech_embedding/1`
    TF-Hub module (Apache-2.0), using the layer layout documented in the same notebook.
  * openWakeWord's README places "all included pre-trained models" under CC BY-NC-SA 4.0
    and the author has not clarified the status of the two feature files (open issues
    #313, #338, #346, #348, #357), so we do not ship or derive from those files. They are
    only used, if provided via --reference-dir, as a numerical cross-check.

Usage:
  python -I build_frontend.py --hub-module <dir with variables/> --out-dir <dir> \
      [--reference-dir <dir with openWakeWord melspectrogram.tflite/embedding_model.tflite>]
"""
import argparse
import os

import numpy as np

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "3")
import tensorflow as tf  # noqa: E402

SR = 16000
N_FFT = 512
WIN = 400
HOP = 160
N_MELS = 32
FMIN, FMAX = 60.0, 3800.0
AMIN = 1e-10
TOP_DB = 80.0
BN_EPS = 1e-3


# --------------------------------------------------------------------------------------
# Mel filterbank (Slaney-style, identical to librosa.filters.mel(htk=False, norm="slaney"))
# Implemented here so the build has no dependency beyond numpy.
# --------------------------------------------------------------------------------------
def _hz_to_mel(f):
    f = np.asanyarray(f, dtype=np.float64)
    f_sp = 200.0 / 3
    mels = f / f_sp
    min_log_hz = 1000.0
    min_log_mel = min_log_hz / f_sp
    logstep = np.log(6.4) / 27.0
    return np.where(f >= min_log_hz, min_log_mel + np.log(np.maximum(f, 1e-12) / min_log_hz) / logstep, mels)


def _mel_to_hz(m):
    m = np.asanyarray(m, dtype=np.float64)
    f_sp = 200.0 / 3
    freqs = f_sp * m
    min_log_hz = 1000.0
    min_log_mel = min_log_hz / f_sp
    logstep = np.log(6.4) / 27.0
    return np.where(m >= min_log_mel, min_log_hz * np.exp(logstep * (m - min_log_mel)), freqs)


def mel_filterbank():
    n_bins = N_FFT // 2 + 1
    fftfreqs = np.linspace(0, SR / 2, n_bins)
    mel_f = _mel_to_hz(np.linspace(_hz_to_mel(FMIN), _hz_to_mel(FMAX), N_MELS + 2))
    fdiff = np.diff(mel_f)
    ramps = mel_f[:, None] - fftfreqs[None, :]
    weights = np.zeros((N_MELS, n_bins))
    for i in range(N_MELS):
        lower = -ramps[i] / fdiff[i]
        upper = ramps[i + 2] / fdiff[i + 1]
        weights[i] = np.maximum(0, np.minimum(lower, upper))
    enorm = 2.0 / (mel_f[2:N_MELS + 2] - mel_f[:N_MELS])
    weights *= enorm[:, None]
    return weights.astype(np.float32)  # [32, 257]


def stft_kernels():
    n = np.arange(WIN)
    hann = 0.5 - 0.5 * np.cos(2 * np.pi * n / WIN)  # periodic Hann (scipy fftbins=True)
    window = np.zeros(N_FFT)
    left = (N_FFT - WIN) // 2
    window[left:left + WIN] = hann
    k = np.arange(N_FFT // 2 + 1)
    t = np.arange(N_FFT)
    ang = 2 * np.pi * np.outer(t, k) / N_FFT  # [512, 257]
    real = (np.cos(ang) * window[:, None]).astype(np.float32)
    imag = (-np.sin(ang) * window[:, None]).astype(np.float32)
    return real, imag


class MelSpectrogram(tf.Module):
    def __init__(self):
        super().__init__()
        real, imag = stft_kernels()
        self.k_real = tf.constant(real[:, None, :])  # conv1d kernel [width, in, out]
        self.k_imag = tf.constant(imag[:, None, :])
        self.fb = tf.constant(mel_filterbank().T)  # [257, 32]

    @tf.function(input_signature=[tf.TensorSpec([None, None], tf.float32, name="input")])
    def __call__(self, x):
        x = tf.expand_dims(x, -1)
        re = tf.nn.conv1d(x, self.k_real, stride=HOP, padding="VALID")
        im = tf.nn.conv1d(x, self.k_imag, stride=HOP, padding="VALID")
        power = re * re + im * im  # [b, frames, 257]
        mel = tf.matmul(power, self.fb)  # [b, frames, 32]
        db = (10.0 / np.log(10.0)) * tf.math.log(tf.maximum(mel, AMIN))
        db = tf.maximum(db, tf.reduce_max(db) - TOP_DB)
        return tf.expand_dims(db, 1)


def mel_numpy(x):
    """Float64 numpy reference of the same transform (used for verification and offline features)."""
    real, imag = stft_kernels()
    n = 1 + (x.shape[1] - N_FFT) // HOP
    idx = np.arange(N_FFT)[None, :] + HOP * np.arange(n)[:, None]
    fr = x[:, idx]
    p = (fr @ real) ** 2 + (fr @ imag) ** 2
    m = p @ mel_filterbank().T
    db = 10 * np.log10(np.maximum(m, AMIN))
    return np.maximum(db, db.max() - TOP_DB)[:, None]


# --------------------------------------------------------------------------------------
# Speech embedding network (Google speech_embedding/1 weights, Apache-2.0)
# --------------------------------------------------------------------------------------
PFX = "embeddings/state/MudpuppyLite/"
# (conv var, kernel, padding) in graph order; BatchNorm index follows conv order.
LAYERS = [("ZeroMeanConv2d", "valid_pad_w"), ("Conv", "same"), ("Conv_1", "valid"), "pool22",
          ("Conv_2", "same"), ("Conv_3", "valid"),
          ("Conv_4", "same"), ("Conv_5", "valid"), "pool12same",
          ("Conv_6", "same"), ("Conv_7", "valid"),
          ("Conv_8", "same"), ("Conv_9", "valid"), "pool22",
          ("Conv_10", "same"), ("Conv_11", "valid"),
          ("Conv_12", "same"), ("Conv_13", "valid"), "pool12",
          ("Conv_14", "same"), ("Conv_15", "valid"),
          ("Conv_16", "same"), ("Conv_17", "valid"), "pool22",
          ("Conv_18", "valid")]


def load_hub_weights(module_dir):
    reader = tf.train.load_checkpoint(os.path.join(module_dir, "variables", "variables"))
    return {k[len(PFX):]: reader.get_tensor(k) for k in reader.get_variable_to_shape_map() if k.startswith(PFX)}


class Embedding(tf.Module):
    def __init__(self, w):
        super().__init__()
        self.ops = []
        bn_i = 0
        for layer in LAYERS:
            if isinstance(layer, str):
                self.ops.append((layer, None))
                continue
            name, pad = layer
            k = w[name + "/weights"].astype(np.float32)
            if name == "ZeroMeanConv2d":
                k = k - k.mean(axis=(0, 1), keepdims=True)
            entry = {"kernel": tf.constant(k), "pad": pad}
            if name != "Conv_18":
                bn = "BatchNorm" if bn_i == 0 else "BatchNorm_%d" % bn_i
                bn_i += 1
                gamma, beta = w[bn + "/gamma"], w[bn + "/beta"]
                mean, var = w[bn + "/moving_mean"], w[bn + "/moving_variance"]
                scale = gamma / np.sqrt(var + BN_EPS)
                entry["scale"] = tf.constant(scale.astype(np.float32))
                entry["shift"] = tf.constant((beta - mean * scale).astype(np.float32))
            self.ops.append((name, entry))
        assert bn_i == 19

    @tf.function(input_signature=[tf.TensorSpec([None, 76, 32, 1], tf.float32, name="input_1")])
    def __call__(self, x):
        for name, e in self.ops:
            if name == "pool22":
                x = tf.nn.max_pool2d(x, (2, 2), (2, 2), "VALID")
            elif name == "pool12same":
                x = tf.nn.max_pool2d(x, (1, 2), (1, 2), "SAME")
            elif name == "pool12":
                x = tf.nn.max_pool2d(x, (1, 2), (1, 2), "VALID")
            else:
                if e["pad"] == "valid_pad_w":
                    x = tf.pad(x, [[0, 0], [0, 0], [1, 1], [0, 0]])
                    # No activation here: verified numerically (max|diff| ~1e-4) against the
                    # openWakeWord v0.5.1 embedding_model.tflite, which the runtime expects.
                    x = tf.nn.conv2d(x, e["kernel"], 1, "VALID")
                else:
                    x = tf.nn.conv2d(x, e["kernel"], 1, "SAME" if e["pad"] == "same" else "VALID")
                if "scale" in e:
                    x = x * e["scale"] + e["shift"]
                    x = tf.maximum(0.2 * x, x)  # leaky relu, alpha 0.2
                    x = tf.maximum(x, -0.4)
        return x


def to_tflite(module, path):
    conv = tf.lite.TFLiteConverter.from_concrete_functions([module.__call__.get_concrete_function()], module)
    conv.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
    data = conv.convert()
    with open(path, "wb") as f:
        f.write(data)
    return path


def run_tflite(path, x):
    it = tf.lite.Interpreter(model_path=path)
    it.resize_tensor_input(it.get_input_details()[0]["index"], list(x.shape), strict=False)
    it.allocate_tensors()
    it.set_tensor(it.get_input_details()[0]["index"], x.astype(np.float32))
    it.invoke()
    return it.get_tensor(it.get_output_details()[0]["index"])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--hub-module", required=True)
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--reference-dir", default=None)
    a = ap.parse_args()
    os.makedirs(a.out_dir, exist_ok=True)

    mel_path = to_tflite(MelSpectrogram(), os.path.join(a.out_dir, "melspectrogram.tflite"))
    emb_path = to_tflite(Embedding(load_hub_weights(a.hub_module)), os.path.join(a.out_dir, "embedding_model.tflite"))

    rng = np.random.default_rng(1234)
    tests = [rng.normal(0, 3000, (1, 1760)), rng.normal(0, 3000, (1, 32000)),
             np.concatenate([rng.normal(0, 2, (1, 8000)), rng.normal(0, 12000, (1, 8000))], 1),
             np.clip(rng.normal(0, 30000, (1, 16000)), -32768, 32767)]
    for x in tests:
        m = run_tflite(mel_path, x)
        d = np.abs(m - mel_numpy(x)).max()
        print("mel vs numpy float64: shape %s max|diff| %.2e" % (m.shape, d))
        assert d < 1e-2, d
    if a.reference_dir:
        ref_mel = os.path.join(a.reference_dir, "melspectrogram.tflite")
        ref_emb = os.path.join(a.reference_dir, "embedding_model.tflite")
        for x in tests:
            print("mel vs reference: max|diff| %.2e" % np.abs(run_tflite(mel_path, x) - run_tflite(ref_mel, x)).max())
        spec = run_tflite(mel_path, tests[1])[0, 0] / 10 + 2
        win = np.stack([spec[i:i + 76] for i in range(0, spec.shape[0] - 75, 8)])[..., None]
        e_new, e_ref = run_tflite(emb_path, win), run_tflite(ref_emb, win)
        print("embedding vs reference: shape %s max|diff| %.2e (ref max|x| %.2f)"
              % (e_new.shape, np.abs(e_new - e_ref).max(), np.abs(e_ref).max()))
    for p in (mel_path, emb_path):
        it = tf.lite.Interpreter(model_path=p)
        print(os.path.basename(p), os.path.getsize(p), "bytes",
              [(d["shape_signature"].tolist(), d["dtype"].__name__) for d in it.get_input_details()],
              "->", [(d["shape_signature"].tolist(), d["dtype"].__name__) for d in it.get_output_details()])


if __name__ == "__main__":
    main()
