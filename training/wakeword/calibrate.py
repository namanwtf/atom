#!/usr/bin/env python3
"""Pick the operating point on VALIDATION data and fold it into the classifier bias, so that the
app's fixed threshold of 0.5 lands on that point.

sigmoid(z) >= t  <=>  z - logit(t) >= 0, so subtracting logit(t*) from the output bias moves the
decision boundary from t* to 0.5 without changing the ranking of the scores.

The chosen t* maximises streaming-like recall on pos_val_long, subject to false accepts per hour
on validation speech (LibriSpeech test-clean + an unseen train-other-500 shard) <= --target-fa.

  python -I calibrate.py --feat-dir work/feat --ckpt work/model_X/head.pt --out work/model_X/head_cal.pt
"""
import argparse
import json
import os
import sys

import numpy as np
import torch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from train import Head, LongSeqs, count_activations  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--feat-dir", required=True)
    ap.add_argument("--ckpt", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--target-fa", type=float, default=0.3)
    ap.add_argument("--target-adv", type=float, default=1.0)
    ap.add_argument("--speech-val", nargs="+", default=["speech_val"],
                    help="validation negative speech sets (feature prefixes); FA/hr is pooled over them")
    a = ap.parse_args()
    ck = torch.load(a.ckpt, map_location="cpu")
    m = Head(ck["proj"], ck["hidden"])
    m.load_state_dict(ck["state_dict"])
    m.eval()
    F = lambda n: os.path.join(a.feat_dir, n)  # noqa: E731
    pl = np.load(F("pos_val_long.npy")).astype(np.float32)
    nt = np.load(F("neg_val_tts.npy")).astype(np.float32)
    svs = [LongSeqs(F(n), np.random.default_rng(0)) for n in a.speech_val]
    hours = sum(v.hours for v in svs)
    with torch.no_grad():
        w = np.lib.stride_tricks.sliding_window_view(pl, (16, 96), axis=(1, 2))[:, :, 0]
        pos_max = m(torch.tensor(w.reshape(-1, 16, 96))).numpy().reshape(len(pl), -1).max(1)
        tts = m(torch.tensor(nt)).numpy().ravel()
        speech = [m(torch.tensor(np.ascontiguousarray(x), dtype=torch.float32)).numpy().ravel()
                  for sv in svs for x in sv.all_windows()]
        advl = []
        if os.path.exists(F("neg_val_tts_long.npy")):
            al = LongSeqs(F("neg_val_tts_long"), np.random.default_rng(0))
            advl = [m(torch.tensor(np.ascontiguousarray(x), dtype=torch.float32)).numpy().ravel() for x in al.all_windows()]
    rows = []
    for t in np.concatenate([[0.01, 0.02, 0.03], np.linspace(0.05, 0.95, 19), [0.97, 0.98, 0.99, 0.995, 0.998, 0.999]]):
        fa = sum(count_activations(s, t) for s in speech) / hours
        rows.append({"thr": float(t), "recall_stream_val": float((pos_max >= t).mean()),
                     "tts_neg_fpr": float((tts >= t).mean()), "fa_per_hr_val": fa,
                     "adv_utt_fa_val": float(np.mean([count_activations(x, t) > 0 for x in advl])) if advl else None})
    for r in rows:
        print(json.dumps(r))
    ok = [r for r in rows if r["fa_per_hr_val"] <= a.target_fa and (r["adv_utt_fa_val"] or 0) <= a.target_adv]
    best = max(ok, key=lambda r: (r["recall_stream_val"], -r["thr"])) if ok else rows[-1]
    t = best["thr"]
    shift = float(np.log(t / (1 - t)))
    sd = {k: v.clone() for k, v in ck["state_dict"].items()}
    sd["out.bias"] = sd["out.bias"] - shift
    torch.save({"state_dict": sd, "proj": ck["proj"], "hidden": ck["hidden"],
                "calibration": {"threshold_before": t, "logit_shift": shift, "val": best}}, a.out)
    print("CHOSEN", json.dumps({"threshold_before": t, "logit_shift": shift, **best}))


if __name__ == "__main__":
    main()
