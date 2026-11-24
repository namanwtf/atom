#!/usr/bin/env python3
"""Train the small "hey atom" classifier head on frozen openWakeWord-style features.

Follows openWakeWord's train.py recipe (BCE, linearly increasing negative-class weight,
high-loss sample filtering with gradient accumulation, warmup + cosine LR, checkpoint
selection on validation recall / false accepts per hour, checkpoint averaging), with a
smaller network so the exported TFLite file is tens of KB:

  input [1, 16, 96] -> per-frame Linear(96->P) -> flatten(16*P) -> Linear(->H) -> LN -> ReLU
                    -> Linear(H->H) -> LN -> ReLU -> Linear(H->1) -> sigmoid   output [1, 1]

  python -I train.py --feat-dir work/feat --out work/model --steps 40000 (see run_all.sh for the exact flags)
"""
import argparse
import copy
import json
import os
import time

import numpy as np
import torch
from torch import nn


class Head(nn.Module):
    def __init__(self, proj=16, hidden=32, frames=16, dim=96, dropout=0.0):
        super().__init__()
        self.drop = nn.Dropout(dropout)
        self.proj = nn.Linear(dim, proj)
        self.fc1 = nn.Linear(frames * proj, hidden)
        self.ln1 = nn.LayerNorm(hidden)
        self.fc2 = nn.Linear(hidden, hidden)
        self.ln2 = nn.LayerNorm(hidden)
        self.out = nn.Linear(hidden, 1)

    def forward(self, x):
        x = self.drop(self.proj(x).flatten(1))
        x = self.drop(torch.relu(self.ln1(self.fc1(x))))
        x = self.drop(torch.relu(self.ln2(self.fc2(x))))
        return torch.sigmoid(self.out(x))


class LongSeqs:
    """Embedding sequences of long recordings; samples 16-frame windows inside one file."""

    def __init__(self, prefix, rng):
        self.x = np.asarray(np.load(prefix + ".npy", mmap_mode="r"))
        if self.x.ndim == 3:  # [N, T, 96] fixed-length clips (e.g. TTS negatives at random alignments)
            n, t = self.x.shape[:2]
            self.x = self.x.reshape(n * t, -1)
            idx = np.arange(0, n * t + 1, t)
        else:
            idx = np.load(prefix + ".idx.npy")
        starts, ends = idx[:-1], idx[1:]
        ok = (ends - starts) >= 16
        self.starts, self.ends = starts[ok], ends[ok]
        self.valid = np.concatenate([np.arange(s, e - 15) for s, e in zip(self.starts, self.ends)])
        self.rng = rng
        self.hours = len(self.x) * 0.08 / 3600

    def sample(self, n):
        s = self.rng.choice(self.valid, n)
        return self.x[s[:, None] + np.arange(16)[None]]

    def all_windows(self):
        for s, e in zip(self.starts, self.ends):
            yield np.lib.stride_tricks.sliding_window_view(self.x[s:e], (16, 96))[:, 0]


def count_activations(scores, thr=0.5, refractory=16):
    """Rising edges above threshold with a refractory period (16 frames = 1.28 s)."""
    n, next_ok = 0, -1
    for i in np.where(scores >= thr)[0]:
        if i >= next_ok:
            n += 1
            next_ok = i + refractory
    return n


def evaluate(model, dev, pos_val, neg_val_tts, speech_val, thr=0.5, pos_val_long=None, neg_val_long=None):
    model.eval()
    with torch.no_grad():
        p = model(torch.tensor(pos_val, dtype=torch.float32, device=dev)).cpu().numpy().ravel()
        rs = None
        if pos_val_long is not None:  # streaming-like: any of the 16-frame windows sliding past the phrase end
            n, T = pos_val_long.shape[:2]
            w = np.lib.stride_tricks.sliding_window_view(pos_val_long, (16, 96), axis=(1, 2))[:, :, 0]
            sc = model(torch.tensor(w.reshape(-1, 16, 96), dtype=torch.float32, device=dev)).cpu().numpy()
            rs = float((sc.reshape(n, -1).max(1) >= thr).mean())
        q = model(torch.tensor(neg_val_tts, dtype=torch.float32, device=dev)).cpu().numpy().ravel()
        fa = 0
        for w in speech_val.all_windows():
            s = model(torch.tensor(np.ascontiguousarray(w), dtype=torch.float32, device=dev)).cpu().numpy().ravel()
            fa += count_activations(s, thr)
        adv = None
        if neg_val_long is not None:
            hits = [count_activations(model(torch.tensor(np.ascontiguousarray(w), dtype=torch.float32, device=dev))
                                      .cpu().numpy().ravel(), thr) > 0 for w in neg_val_long.all_windows()]
            adv = float(np.mean(hits))
    model.train()
    return {"adv_utt_fa": adv, "recall": rs if rs is not None else float((p >= thr).mean()), "recall_window": float((p >= thr).mean()),
            "tts_neg_fpr": float((q >= thr).mean()),
            "fa_per_hr": fa / speech_val.hours}


def lr_at(step, total, warmup, hold, peak):
    if step < warmup:
        return peak * step / warmup
    if step < warmup + hold:
        return peak
    return 0.5 * peak * (1 + np.cos(np.pi * (step - warmup - hold) / max(1, total - warmup - hold)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--feat-dir", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--steps", type=int, default=30000)
    ap.add_argument("--max-neg-weight", type=float, default=1000.0)
    ap.add_argument("--lr", type=float, default=1e-3)
    ap.add_argument("--n-pos", type=int, default=128)
    ap.add_argument("--n-tts-neg", type=int, default=128)
    ap.add_argument("--n-long-neg", type=int, default=1024)
    ap.add_argument("--target-fa", type=float, default=0.3)
    ap.add_argument("--proj", type=int, default=16)
    ap.add_argument("--hidden", type=int, default=32)
    ap.add_argument("--tts-neg-weight-max", type=float, default=None,
                    help="cap on the weight of TTS (adversarial/random) negatives; default = --max-neg-weight")
    ap.add_argument("--dropout", type=float, default=0.0)
    ap.add_argument("--weight-decay", type=float, default=0.0)
    ap.add_argument("--device", default="cpu")
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--long-train", nargs="+", default=["speech_train", "noise_train"])
    ap.add_argument("--tts-long", nargs="*", default=[],
                    help="TTS negatives as padded sequences (all alignments), replaces the fixed-window arrays")
    ap.add_argument("--target-adv", type=float, default=1.0, help="max adversarial-utterance FA rate for selection")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    rng = np.random.default_rng(a.seed)
    torch.manual_seed(a.seed)
    dev = torch.device(a.device)
    F = lambda n: os.path.join(a.feat_dir, n)  # noqa: E731

    pos = np.load(F("pos_train.npy"))
    tts_files = [F(n) for n in ("adv_train.npy", "rand_train.npy") if os.path.exists(F(n))]
    tts_neg = np.concatenate([np.load(f) for f in tts_files]) if tts_files else np.zeros((0, 16, 96), np.float16)
    if len(tts_neg) == 0:
        a.n_tts_neg = 0
    longs = [LongSeqs(F(n), rng) for n in a.long_train]
    long_w = np.array([l.hours for l in longs]) / sum(l.hours for l in longs)
    pos_val = np.load(F("pos_val.npy")).astype(np.float32)
    neg_val_tts = np.load(F("neg_val_tts.npy")).astype(np.float32)
    speech_val = LongSeqs(F("speech_val"), rng)
    pos_val_long = np.load(F("pos_val_long.npy")).astype(np.float32) if os.path.exists(F("pos_val_long.npy")) else None
    neg_val_long = LongSeqs(F("neg_val_tts_long"), rng) if os.path.exists(F("neg_val_tts_long.npy")) else None
    tts_long = [LongSeqs(F(n), rng) for n in a.tts_long]
    tts_long_w = np.array([l.hours for l in tts_long]) / max(1e-9, sum(l.hours for l in tts_long))
    if tts_long:
        a.n_tts_neg = a.n_tts_neg or 128
    print("pos %d  tts_neg %d  long %s h  val speech %.2f h" % (
        len(pos), len(tts_neg), [round(l.hours, 2) for l in longs], speech_val.hours), flush=True)

    model = Head(a.proj, a.hidden, dropout=a.dropout).to(dev)
    print("params", sum(p.numel() for p in model.parameters()))
    opt = torch.optim.AdamW(model.parameters(), lr=a.lr, weight_decay=a.weight_decay)
    neg_w = np.linspace(1, a.max_neg_weight, a.steps)
    tts_w = np.linspace(1, a.tts_neg_weight_max if a.tts_neg_weight_max else a.max_neg_weight, a.steps)
    val_every = max(500, a.steps // 60)
    history, ckpts = [], []
    acc_p, acc_y, acc_w = [], [], []
    t0 = time.time()
    for step in range(a.steps):
        for g in opt.param_groups:
            g["lr"] = lr_at(step, a.steps, a.steps // 10, a.steps // 4, a.lr)
        n_l = rng.multinomial(a.n_long_neg, long_w)
        xb = [pos[rng.integers(0, len(pos), a.n_pos)]]
        if a.n_tts_neg and tts_long:
            k = rng.multinomial(a.n_tts_neg, tts_long_w)
            xb.append(np.concatenate([l.sample(c) for l, c in zip(tts_long, k) if c > 0]))
        elif a.n_tts_neg:
            xb.append(tts_neg[rng.integers(0, len(tts_neg), a.n_tts_neg)])
        xb += [l.sample(k) for l, k in zip(longs, n_l) if k > 0]
        x = torch.tensor(np.concatenate(xb).astype(np.float32), device=dev)
        y = torch.zeros(len(x), device=dev)
        y[: a.n_pos] = 1
        wall = torch.full_like(y, float(neg_w[step]))
        wall[: a.n_pos] = 1.0
        wall[a.n_pos:a.n_pos + a.n_tts_neg] = float(tts_w[step])
        p = model(x).squeeze(1)
        keep = ((y == 0) & (p >= 0.001)) | ((y == 1) & (p < 0.999))
        if keep.sum() == 0:
            continue
        w = wall[keep]
        acc_p.append(p[keep]); acc_y.append(y[keep]); acc_w.append(w)
        if sum(len(t) for t in acc_p) >= 128:
            pp, yy, ww = torch.cat(acc_p), torch.cat(acc_y), torch.cat(acc_w)
            loss = nn.functional.binary_cross_entropy(pp, yy, weight=ww)
            opt.zero_grad()
            loss.backward()
            opt.step()
            acc_p, acc_y, acc_w = [], [], []
        else:
            # keep graph small: accumulate predictions via detach-free list (tiny model)
            pass
        if (step + 1) % val_every == 0 or step == a.steps - 1:
            m = evaluate(model, dev, pos_val, neg_val_tts, speech_val, pos_val_long=pos_val_long, neg_val_long=neg_val_long)
            m.update(step=step + 1, neg_weight=float(neg_w[step]), elapsed=round(time.time() - t0))
            history.append(m)
            print(json.dumps(m), flush=True)
            if step > a.steps * 0.4:
                ckpts.append((m, copy.deepcopy(model.state_dict())))

    # checkpoint selection: best recall among checkpoints meeting the FA target; then
    # average it with the other qualifying checkpoints within 2 recall points (SWA-style)
    ok = [c for c in ckpts if c[0]["fa_per_hr"] <= a.target_fa and (c[0]["adv_utt_fa"] or 0) <= a.target_adv] or sorted(ckpts, key=lambda c: c[0]["fa_per_hr"])[:3]
    best = max(c[0]["recall"] for c in ok)
    chosen = [c for c in ok if c[0]["recall"] >= best - 0.02]
    avg = copy.deepcopy(chosen[0][1])
    for k in avg:
        avg[k] = sum(c[1][k] for c in chosen) / len(chosen)
    model.load_state_dict(avg)
    final = evaluate(model, dev, pos_val, neg_val_tts, speech_val, pos_val_long=pos_val_long, neg_val_long=neg_val_long)
    single = max(chosen, key=lambda c: c[0]["recall"])
    if final["fa_per_hr"] > a.target_fa and single[0]["fa_per_hr"] <= a.target_fa:
        model.load_state_dict(single[1])
        final = evaluate(model, dev, pos_val, neg_val_tts, speech_val, pos_val_long=pos_val_long, neg_val_long=neg_val_long)
        final["note"] = "averaged model missed FA target; using best single checkpoint"
    final["averaged_checkpoints"] = [c[0]["step"] for c in chosen]
    print("FINAL(val)", json.dumps(final), flush=True)
    torch.save({"state_dict": model.cpu().state_dict(), "proj": a.proj, "hidden": a.hidden}, os.path.join(a.out, "head.pt"))
    with open(os.path.join(a.out, "train_history.json"), "w") as f:
        json.dump({"args": vars(a), "history": history, "final_val": final}, f, indent=1)


if __name__ == "__main__":
    main()
