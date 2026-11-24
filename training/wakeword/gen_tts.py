#!/usr/bin/env python3
"""Generate synthetic "hey atom" positives and adversarial/random negatives with Piper voices.

Runs Piper VITS voices (.onnx) directly with onnxruntime, feeding phoneme ids built from
hand-written / CMUdict-derived IPA (see phrases.py). No espeak-ng / piper-tts (GPL) code.

  python -I gen_tts.py --voices-dir <piper_voices> --cmudict <cmudict.dict> \
      --kind pos|adv|rand --split train|test --n 20000 --out <dir> [--workers 8] [--seed 1]

Train/test speaker split: speakers with (speaker_id % 10 == 7) of the multi-speaker voices and
the whole en_US-sam-medium voice are used ONLY for the test split.
"""
import argparse
import csv
import json
import os
import random
import sys
import unicodedata
from multiprocessing import Pool

import numpy as np
import soundfile as sf
from scipy.signal import resample_poly

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import phrases  # noqa: E402

# voice name -> (sampling weight, is_british_phonemes)
VOICES = {
    "en_US-libritts-high": (0.30, False),  # "high" model is ~10x slower to run
    "en_GB-vctk-medium": (0.25, True),
    "en_GB-aru-medium": (0.09, True),
    "en_US-hifi_tts_92-medium": (0.04, False),
    "en_GB-alba-medium": (0.04, True),
    "en_US-ljspeech-medium": (0.04, False),
    "en_US-kristin-medium": (0.04, False),
    "en_US-norman-medium": (0.04, False),
    "en_US-joe-medium": (0.04, False),
    "en_US-mike-medium": (0.04, False),
    "en_US-sam-medium": (0.04, False),  # test-only voice
}
TEST_ONLY_VOICE = "en_US-sam-medium"

_SESS = {}
_CFG = {}


def _load(voices_dir, name):
    import onnxruntime as ort
    if name not in _SESS:
        _SESS.clear()
        _CFG.clear()
        so = ort.SessionOptions()
        so.intra_op_num_threads = 1
        so.inter_op_num_threads = 1
        _SESS[name] = ort.InferenceSession(os.path.join(voices_dir, name + ".onnx"), so,
                                           providers=["CPUExecutionProvider"])
        with open(os.path.join(voices_dir, name + ".onnx.json"), encoding="utf-8") as f:
            _CFG[name] = json.load(f)
    return _SESS[name], _CFG[name]


def phoneme_ids(ipa, id_map):
    ids = list(id_map["^"]) + list(id_map["_"])
    for ch in unicodedata.normalize("NFD", ipa):
        if ch in id_map:
            ids += list(id_map[ch]) + list(id_map["_"])
    ids += list(id_map["$"])
    return ids


def trim(audio, sr):
    """Trim leading/trailing low-energy audio, keeping ~30 ms margins."""
    frame = int(0.01 * sr)
    n = len(audio) // frame
    if n < 3:
        return audio
    e = np.sqrt((audio[:n * frame].reshape(n, frame) ** 2).mean(1) + 1e-12)
    active = np.where(e > e.max() * 10 ** (-40 / 20))[0]
    if len(active) == 0:
        return audio
    s = max(0, (active[0] - 3) * frame)
    t = min(len(audio), (active[-1] + 4) * frame)
    return audio[s:t]


def synth_job(args):
    voices_dir, name, items = args
    sess, cfg = _load(voices_dir, name)
    out = []
    for path, ipa, sid, ls, ns, nw in items:
        if os.path.exists(path):  # resumable: rows are deterministic given --seed
            out.append(path)
            continue
        ids = phoneme_ids(ipa, cfg["phoneme_id_map"])
        feeds = {"input": np.array([ids], dtype=np.int64),
                 "input_lengths": np.array([len(ids)], dtype=np.int64),
                 "scales": np.array([ns, ls, nw], dtype=np.float32)}
        if cfg["num_speakers"] > 1:
            feeds["sid"] = np.array([sid], dtype=np.int64)
        audio = sess.run(None, feeds)[0].squeeze().astype(np.float64)
        sr = cfg["audio"]["sample_rate"]
        audio = trim(audio, sr)
        if sr != 16000:
            g = np.gcd(16000, sr)
            audio = resample_poly(audio, 16000 // g, sr // g)
        peak = np.abs(audio).max()
        if peak < 1e-4 or len(audio) < 1600:
            continue
        audio = audio / peak * 0.9
        sf.write(path, (audio * 32767).astype(np.int16), 16000, subtype="PCM_16")
        out.append(path)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--voices-dir", required=True)
    ap.add_argument("--cmudict", required=True)
    ap.add_argument("--kind", choices=["pos", "adv", "rand"], required=True)
    ap.add_argument("--split", choices=["train", "test"], required=True)
    ap.add_argument("--n", type=int, required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--workers", type=int, default=8)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--manifest-only", action="store_true",
                    help="only (re)write manifest.csv for the files that already exist")
    a = ap.parse_args()

    rng = random.Random(a.seed)
    ph = phrases.Phonemizer(a.cmudict)
    os.makedirs(a.out, exist_ok=True)
    cfgs = {}
    for v in VOICES:
        with open(os.path.join(a.voices_dir, v + ".onnx.json"), encoding="utf-8") as f:
            cfgs[v] = json.load(f)
    names = [v for v in VOICES if (a.split == "test" or v != TEST_ONLY_VOICE)]
    weights = [VOICES[v][0] for v in names]
    if a.kind != "pos":  # negatives: halve the share of the slow "high" voice to save compute
        weights = [w * (0.5 if v == "en_US-libritts-high" else 1.0) for v, w in zip(names, weights)]

    rows, by_voice = [], {}
    for i in range(a.n):
        v = rng.choices(names, weights)[0]
        gb = VOICES[v][1]
        nspk = cfgs[v]["num_speakers"]
        if nspk > 1:
            while True:
                sid = rng.randrange(nspk)
                if (sid % 10 == 7) == (a.split == "test"):
                    break
        else:
            sid = 0
        if a.kind == "pos":
            text, ipa = "hey atom", phrases.positive_ipa(rng, gb)
        elif a.kind == "adv":
            text, ipa = phrases.adversarial_negative(rng, ph, gb)
        else:
            text, ipa = phrases.random_negative(rng, ph, gb)
        ls = rng.uniform(0.75, 1.35)
        ns = rng.uniform(0.35, 0.9)
        nw = rng.uniform(0.4, 1.0)
        path = os.path.join(a.out, "%s_%s_%06d.wav" % (a.kind, a.split, i))
        by_voice.setdefault(v, []).append((path, ipa, sid, ls, ns, nw))
        rows.append([os.path.basename(path), v, sid, text, ipa, "%.3f" % ls, "%.3f" % ns, "%.3f" % nw])

    if a.manifest_only:
        rows = [r for r in rows if os.path.exists(os.path.join(a.out, r[0]))]
        by_voice = {}
    jobs = []
    for v, items in by_voice.items():
        for k in range(0, len(items), 200):
            jobs.append((a.voices_dir, v, items[k:k + 200]))
    done = 0
    with Pool(a.workers) as pool:
        for res in pool.imap_unordered(synth_job, jobs):
            done += len(res)
            print("generated %d/%d" % (done, a.n), flush=True)
    with open(os.path.join(a.out, "manifest.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["file", "voice", "speaker", "text", "ipa", "length_scale", "noise_scale", "noise_w"])
        w.writerows(rows)


if __name__ == "__main__":
    main()
