#!/usr/bin/env python3
"""End-to-end streaming evaluation of the three shipped TFLite models.

Re-implements openWakeWord's streaming loop (openwakeword/utils.py AudioFeatures +
model.py): audio arrives in 1280-sample (80 ms) chunks; melspectrogram.tflite runs on the
last 1280+480 samples (8 new frames, transformed x/10+2); embedding_model.tflite runs on the
last 76 mel frames (1 new 96-d embedding); hey_atom.tflite runs on the last 16 embeddings.
Buffers are initialised like openWakeWord (mel buffer of ones, embeddings of random noise)
and the first 5 predictions are zeroed.

Reports (threshold 0.5, activations = rising edges with a 1.28 s refractory period):
  * recall on held-out synthetic positives (unseen speakers + unseen voice), mixed into a
    continuous stream of held-out background noise, with and without noise/reverb;
  * false accepts per hour on held-out speech (LibriSpeech test-other, half of it
    reverberated / noise-mixed) and held-out CC0 background audio;
  * false-accept rate per utterance on held-out adversarial TTS phrases ("hey adam", ...).

  python -I evaluate.py --models <dir with the 3 tflite> --pos-dir work/tts/pos_test --adv-dir work/tts/adv_test \
      --speech-list work/lists/speech_test.txt --noise-dir work/noise/test --rir-dir work/noise/rir_test --out report.json
"""
import argparse
import csv
import glob
import json
import os
import sys
from multiprocessing import Pool

import numpy as np

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "3")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from augment import Augmenter, load_wav  # noqa: E402

CHUNK = 1280
THR = 0.5
REFRACTORY = 16


class Streamer:
    def __init__(self, model_dir, seed=0):
        import tensorflow as tf
        self.mel = tf.lite.Interpreter(os.path.join(model_dir, "melspectrogram.tflite"), num_threads=1)
        self.mel.resize_tensor_input(0, [1, CHUNK + 480], strict=False)
        self.mel.allocate_tensors()
        self.emb = tf.lite.Interpreter(os.path.join(model_dir, "embedding_model.tflite"), num_threads=1)
        self.emb.resize_tensor_input(0, [1, 76, 32, 1], strict=False)
        self.emb.allocate_tensors()
        self.clf = tf.lite.Interpreter(os.path.join(model_dir, "hey_atom.tflite"), num_threads=1)
        self.clf.allocate_tensors()
        self.mi, self.mo = self.mel.get_input_details()[0]["index"], self.mel.get_output_details()[0]["index"]
        self.ei, self.eo = self.emb.get_input_details()[0]["index"], self.emb.get_output_details()[0]["index"]
        self.ci, self.co = self.clf.get_input_details()[0]["index"], self.clf.get_output_details()[0]["index"]
        self.rng = np.random.default_rng(seed)

    def _mel(self, x):
        self.mel.set_tensor(self.mi, x[None].astype(np.float32))
        self.mel.invoke()
        return self.mel.get_tensor(self.mo)[0, 0] / 10 + 2

    def _emb(self, m):
        self.emb.set_tensor(self.ei, m[None, :, :, None].astype(np.float32))
        self.emb.invoke()
        return self.emb.get_tensor(self.eo).reshape(96)

    def reset(self):
        self.raw = np.zeros(CHUNK + 480, dtype=np.float32)
        self.melbuf = np.ones((76, 32), dtype=np.float32)
        # openWakeWord seeds the feature buffer with embeddings of 4 s of random noise
        noise = self.rng.integers(-1000, 1000, 16000 * 4).astype(np.float32)
        self.mel.resize_tensor_input(0, [1, len(noise)], strict=False)
        self.mel.allocate_tensors()
        m = self._mel(noise)
        self.mel.resize_tensor_input(0, [1, CHUNK + 480], strict=False)
        self.mel.allocate_tensors()
        self.feat = [self._emb(m[i:i + 76]) for i in range(0, len(m) - 75, 8)][-16:]
        self.n_pred = 0

    def run(self, audio):
        """audio: int16-scale float array; returns one score per 80 ms chunk."""
        self.reset()
        n = len(audio) // CHUNK
        scores = np.zeros(n, dtype=np.float32)
        for k in range(n):
            chunk = audio[k * CHUNK:(k + 1) * CHUNK]
            self.raw = np.concatenate([self.raw[CHUNK:], chunk]).astype(np.float32)
            self.melbuf = np.concatenate([self.melbuf, self._mel(self.raw)])[-76:]
            self.feat = self.feat[1:] + [self._emb(self.melbuf)]
            self.clf.set_tensor(self.ci, np.stack(self.feat)[None].astype(np.float32))
            self.clf.invoke()
            s = float(self.clf.get_tensor(self.co).reshape(-1)[0])
            self.n_pred += 1
            scores[k] = 0.0 if self.n_pred <= 5 else s
        return scores


def activations(scores):
    out, next_ok = [], -1
    for i in np.where(scores >= THR)[0]:
        if i >= next_ok:
            out.append(int(i))
            next_ok = i + REFRACTORY
    return out


# ------------------------------------------------------------------------------------------
_S = None
_A = None


def _init(model_dir, noise_dir, rir_dir, seed):
    global _S, _A
    _S = Streamer(model_dir, seed + os.getpid())
    _A = Augmenter(noise_dir, rir_dir, None, seed=seed + 7 * os.getpid())


def _bed(n):
    """continuous low-level background bed (held-out noise) so the stream is never silent."""
    x = _A._noise_segment(n)
    return x / (np.abs(x).max() + 1e-9)


def _pos_job(args):
    files, noisy = args
    gap = int(1.5 * 16000)
    pieces, spans, pos = [], [], 0
    for f in files:
        x = load_wav(f)
        if noisy and _A.rng.random() < 0.5:
            x = _A.rir(np.concatenate([x, np.zeros(8000)]))
        pieces.append(np.zeros(gap)); pos += gap
        spans.append((pos, pos + len(x)))
        pieces.append(x); pos += len(x)
    pieces.append(np.zeros(gap)); pos += gap
    sig = np.concatenate(pieces)
    bed = _bed(len(sig))
    out = bed / (np.sqrt(np.mean(bed ** 2)) + 1e-12)  # bed at RMS 1
    snr = _A.rng.uniform(5, 20, len(spans)) if noisy else np.full(len(spans), 40.0)
    for (s, e), r in zip(spans, snr):  # each utterance at its own SNR against the bed
        u = sig[s:e]
        out[s:e] += u / (np.sqrt(np.mean(u ** 2)) + 1e-12) * 10 ** (r / 20)
    out = out / np.abs(out).max() * 10 ** (_A.rng.uniform(-20, -3) / 20) * 32767
    scores = _S.run(out.astype(np.float32))
    det = []
    for s, e in spans:
        a, b = s // 1280, min(len(scores), e // 1280 + 13)  # allow up to ~1 s after the clip ends
        det.append(bool((scores[a:b] >= THR).any()))
    acts = activations(scores)
    inside = set()
    for i in acts:
        for j, (s, e) in enumerate(spans):
            if s // 1280 <= i <= e // 1280 + 13:
                inside.add(i)
    return det, len(acts) - len(inside), len(out) / 16000, float(np.max(scores))


def _neg_stream_job(args):
    files, aug = args
    xs = []
    for f in files:
        x = load_wav(f)
        if aug:
            x = _A.long_audio(x, p_rir=0.5, snr=(0, 20), p_bg=0.6) / 32767
        else:
            x = x / (np.abs(x).max() + 1e-9) * 10 ** (_A.rng.uniform(-25, -1) / 20)
        xs.append(x)
    sig = np.concatenate(xs)
    sig = sig + _A.rng.standard_normal(len(sig)) * 10 ** (-70 / 20)
    scores = _S.run((np.clip(sig, -1, 1) * 32767).astype(np.float32))
    acts = activations(scores)
    return len(acts), len(sig) / 16000, [float(scores[i]) for i in acts]


def _adv_job(args):
    files, noisy = args
    res = []
    for f in files:
        x = load_wav(f)
        if noisy and _A.rng.random() < 0.5:
            x = _A.rir(np.concatenate([x, np.zeros(8000)]))
        x = x / (np.sqrt(np.mean(x ** 2)) + 1e-12)
        sig = np.concatenate([np.zeros(16000), x, np.zeros(16000)])
        bed = _bed(len(sig))
        bed = bed / (np.sqrt(np.mean(bed ** 2)) + 1e-12)
        sig = sig + bed * 10 ** (-(_A.rng.uniform(5, 20) if noisy else 40.0) / 20)
        sig = sig / np.abs(sig).max() * 10 ** (_A.rng.uniform(-20, -3) / 20) * 32767
        sc = _S.run(sig.astype(np.float32))
        res.append((os.path.basename(f), bool(len(activations(sc)) > 0), float(sc.max())))
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", required=True)
    ap.add_argument("--pos-dir", required=True)
    ap.add_argument("--adv-dir", required=True)
    ap.add_argument("--speech-list", required=True)
    ap.add_argument("--noise-dir", required=True)
    ap.add_argument("--rir-dir", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--workers", type=int, default=6)
    ap.add_argument("--max-pos", type=int, default=1500)
    ap.add_argument("--max-adv", type=int, default=1000)
    ap.add_argument("--half", choices=["first", "second"], default="second",
                    help="second = held-out test half (final numbers); first = validation half (development)")
    ap.add_argument("--pos-only", action="store_true", help="development: positives + adversarial only")
    a = ap.parse_args()

    pos = sorted(glob.glob(os.path.join(a.pos_dir, "*.wav")))
    pick = (lambda l: l[len(l) // 2:]) if a.half == "second" else (lambda l: l[: len(l) // 2])
    pos = pick(pos)[: a.max_pos]  # second half = never used for training/selection
    adv = sorted(glob.glob(os.path.join(a.adv_dir, "*.wav")))
    adv = pick(adv)[: a.max_adv]
    with open(a.speech_list) as f:
        speech = [l.strip() for l in f if l.strip()]
    noise = sorted(glob.glob(os.path.join(a.noise_dir, "*.wav")))
    pool = Pool(a.workers, initializer=_init, initargs=(a.models, a.noise_dir, a.rir_dir, 123))
    rep = {}

    for noisy in (False, True):
        jobs = [(pos[i:i + 50], noisy) for i in range(0, len(pos), 50)]
        det, fa, secs = [], 0, 0.0
        for d, f, s, _ in pool.imap_unordered(_pos_job, jobs):
            det += d; fa += f; secs += s
        key = "positives_noisy_5to20dB_rir50" if noisy else "positives_quiet"
        rep[key] = {"n": len(det), "recall": float(np.mean(det))}
        print(key, rep[key], flush=True)

    if a.pos_only:
        speech, noise = [], []
    # held-out speech: interleave clean and augmented chunks of ~20 utterances
    rng = np.random.default_rng(0)
    rng.shuffle(speech)
    jobs = [(speech[i:i + 20], (i // 20) % 2 == 1) for i in range(0, len(speech), 20)]
    n, secs, sc = 0, 0.0, []
    for k, s, scs in pool.imap_unordered(_neg_stream_job, jobs):
        n += k; secs += s; sc += scs
    secs, secs_n = max(secs, 1e-9), None
    rep["speech_librispeech_test_other"] = {"hours": secs / 3600, "false_accepts": n, "fa_per_hour": n / (secs / 3600),
                                            "fa_scores": sorted(sc)[-10:]}
    print("speech", rep["speech_librispeech_test_other"], flush=True)
    jobs = [(noise[i:i + 20], False) for i in range(0, len(noise), 20)]
    n2, secs2, sc2 = 0, 0.0, []
    for k, s, scs in pool.imap_unordered(_neg_stream_job, jobs):
        n2 += k; secs2 += s; sc2 += scs
    secs2 = max(secs2, 1e-9)
    rep["background_freesound_cc0_test"] = {"hours": secs2 / 3600, "false_accepts": n2,
                                            "fa_per_hour": n2 / (secs2 / 3600), "fa_scores": sorted(sc2)[-10:]}
    print("noise", rep["background_freesound_cc0_test"], flush=True)
    rep["negatives_combined"] = {"hours": (secs + secs2) / 3600, "false_accepts": n + n2,
                                 "fa_per_hour": (n + n2) / ((secs + secs2) / 3600)}

    man = {}
    with open(os.path.join(a.adv_dir, "manifest.csv"), encoding="utf-8") as f:
        for r in csv.DictReader(f):
            man[r["file"]] = r["text"]
    for noisy in (False, True):
        by = {}
        jobs = [(adv[i:i + 25], noisy) for i in range(0, len(adv), 25)]
        for res in pool.imap_unordered(_adv_job, jobs):
            for fn, hit, mx in res:
                by.setdefault(man.get(fn, "?"), []).append(hit)
        sfx = "_noisy_5to20dB_rir50" if noisy else "_quiet"
        rep["adversarial_tts_false_accept_rate" + sfx] = {
            t: {"n": len(v), "fa_rate": float(np.mean(v))} for t, v in sorted(by.items(), key=lambda kv: -np.mean(kv[1]))}
        allv = [h for v in by.values() for h in v]
        rep["adversarial_tts_overall" + sfx] = {"n": len(allv), "fa_rate": float(np.mean(allv))}
        print("adversarial overall" + sfx, rep["adversarial_tts_overall" + sfx], flush=True)
    with open(a.out, "w") as f:
        json.dump(rep, f, indent=1)


if __name__ == "__main__":
    main()
