#!/usr/bin/env python3
"""Augment audio and compute openWakeWord-style embedding features for training.

Two modes:
  clips  short clips (TTS positives / negatives) -> fixed 2.0 s windows -> [N, 16, 96]
  long   long recordings (speech, noise)        -> per-file embedding sequences
          saved as  <out>.npy [M, 96] float16  and  <out>.idx.npy [n_files+1] offsets

Examples:
  python -I features.py clips --wav-dir work/tts/pos_train --rounds 2 --out work/feat/pos_train.npy \
      --noise-dir work/noise/train --rir-dir work/noise/rir_train --babble-list work/lists/babble_train.txt
  python -I features.py long --list work/lists/speech_train.txt --rounds 2 --out work/feat/speech_train \
      --noise-dir work/noise/train --rir-dir work/noise/rir_train
"""
import argparse
import glob
import os
import sys
from multiprocessing import Pool

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from augment import Augmenter, load_wav  # noqa: E402

TOTAL = 32000  # 2.0 s -> 197 mel frames -> 16 embeddings (openWakeWord default window)
_AUG = None
_TAIL = 0.0
_JIT = 0.2


def _init(noise_dir, rir_dir, babble_list, seed, total=TOTAL, tail=0.0, pad=0.0, jit=0.2):
    global _AUG, TOTAL, _TAIL, _PAD, _JIT
    TOTAL, _TAIL, _PAD, _JIT = total, tail, pad, jit
    babble = []
    if babble_list:
        with open(babble_list) as f:
            babble = [l.strip() for l in f if l.strip()]
    _AUG = Augmenter(noise_dir, rir_dir, babble, seed=seed + os.getpid())


def _clip_job(args):
    paths, kind = args
    out = []
    for p in paths:
        if kind == "clean":
            x = load_wav(p)[-TOTAL:]
            y = np.zeros(TOTAL)
            y[TOTAL - len(x):] = x
            out.append((_AUG.finalize(y)))
        else:
            out.append(_AUG.fixed_clip(p, TOTAL, min_tail=_TAIL, end_jitter=_JIT))
    return np.stack(out)


_PAD = 0.0


def _long_job(args):
    path, clean = args
    x = load_wav(path)
    if _PAD > 0:  # short TTS clip -> pad with silence so every alignment (before/after) is covered
        r = _AUG.rng
        pre, post = int(r.uniform(0.5, 1.0) * _PAD * 16000), int(r.uniform(0.5, 1.0) * _PAD * 16000)
        x = np.concatenate([np.zeros(pre), x, np.zeros(post)])
    if len(x) < 12400 + 1280:
        return None
    return _AUG.finalize(x) if clean else _AUG.long_audio(x)


def get_frontend(hub, device):
    import torch
    import frontend_torch as ft
    fe = ft.TorchFrontend(hub, dev=torch.device(device))
    return fe


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("mode", choices=["clips", "long"])
    ap.add_argument("--wav-dir", nargs="*", default=[])
    ap.add_argument("--list", default=None)
    ap.add_argument("--rounds", type=int, default=1)
    ap.add_argument("--out", required=True)
    ap.add_argument("--noise-dir", required=True)
    ap.add_argument("--rir-dir", required=True)
    ap.add_argument("--babble-list", default=None)
    ap.add_argument("--clean", action="store_true", help="no augmentation (level + dither only)")
    ap.add_argument("--hub-module", required=True)
    ap.add_argument("--device", default="mps")
    ap.add_argument("--workers", type=int, default=6)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--max-files", type=int, default=0)
    ap.add_argument("--total-sec", type=float, default=2.0, help="clip window length (2.0 s = 16 embeddings)")
    ap.add_argument("--tail-sec", type=float, default=0.0, help="extra audio after the phrase end")
    ap.add_argument("--end-jitter-sec", type=float, default=0.2,
                    help="clips mode: phrase end placed uniformly in [tail, tail+jitter] before the window end")
    ap.add_argument("--pad-sec", type=float, default=0.0, help="long mode: pad each file with up to this much silence on both sides")
    a = ap.parse_args()
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)

    if a.list:
        with open(a.list) as f:
            files = [l.strip() for l in f if l.strip()]
    else:
        files = sorted(p for d in a.wav_dir for p in glob.glob(os.path.join(d, "*.wav")))
    if a.max_files:
        files = files[: a.max_files]
    fe = get_frontend(a.hub_module, a.device)
    total = int(round(a.total_sec * 16000))
    n_emb = 1 + (1 + (total - 512) // 160 - 76) // 8
    pool = Pool(a.workers, initializer=_init, initargs=(a.noise_dir, a.rir_dir, a.babble_list, a.seed, total, a.tail_sec, a.pad_sec, a.end_jitter_sec))

    if a.mode == "clips":
        allp = files * a.rounds
        chunks = [(allp[i:i + 64], "clean" if a.clean else "aug") for i in range(0, len(allp), 64)]
        feats = np.lib.format.open_memmap(a.out, mode="w+", dtype=np.float16, shape=(len(allp), n_emb, 96))
        k = 0
        for j, audio in enumerate(pool.imap(_clip_job, chunks)):
            e = fe.embed_audio(audio)
            feats[k:k + len(e)] = e.astype(np.float16)
            k += len(e)
            if j % 20 == 0:
                print("clips %d/%d" % (k, len(allp)), flush=True)
        feats.flush()
        print("saved", a.out, feats.shape)
    else:
        jobs = [(p, a.clean) for _ in range(a.rounds) for p in files]
        seqs, offsets = [], [0]
        for j, audio in enumerate(pool.imap(_long_job, jobs, chunksize=4)):
            if audio is None:
                continue
            e = fe.embed_audio(audio[None])[0].astype(np.float16)
            seqs.append(e)
            offsets.append(offsets[-1] + len(e))
            if j % 200 == 0:
                print("long %d/%d  frames %d (%.2f h)" % (j, len(jobs), offsets[-1], offsets[-1] * 0.08 / 3600),
                      flush=True)
        np.save(a.out + ".npy", np.concatenate(seqs))
        np.save(a.out + ".idx.npy", np.array(offsets, dtype=np.int64))
        print("saved", a.out, offsets[-1], "frames = %.2f h" % (offsets[-1] * 0.08 / 3600))
    pool.close()


if __name__ == "__main__":
    main()
