#!/usr/bin/env python3
"""Prepare background-noise clips and room impulse responses.

1. Background noise: Freesound clips from the parquet shards of
   benjamin-paine/freesound-laion-640k-commercial-16khz-tiny, keeping ONLY rows whose
   per-clip license is CC0-1.0 (class 0). Split train/test by freesound_id % 5 == 0 (test).
2. Room impulse responses: fully synthetic, generated with pyroomacoustics (MIT) using the
   image-source method in random shoebox rooms. No third-party RIR recordings are used.

  python -I prep_noise.py --parquet <shard.parquet> [...] --out <work/noise> --n-rir-train 400 --n-rir-test 100
"""
import argparse
import csv
import io
import os

import numpy as np
import soundfile as sf
from scipy.signal import resample_poly

CC0 = 0


def export_freesound(paths, out):
    import pyarrow.parquet as pq
    for split in ("train", "test"):
        os.makedirs(os.path.join(out, split), exist_ok=True)
    rows = []
    for p in paths:
        pf = pq.ParquetFile(p)
        for batch in pf.iter_batches(batch_size=256, columns=["audio", "username", "freesound_id", "license"]):
            d = batch.to_pydict()
            for audio, user, fid, lic in zip(d["audio"], d["username"], d["freesound_id"], d["license"]):
                if lic != CC0:
                    continue
                try:
                    x, sr = sf.read(io.BytesIO(audio["bytes"]), dtype="float32", always_2d=True)
                except Exception:
                    continue
                x = x.mean(1)
                if sr != 16000:
                    g = np.gcd(16000, sr)
                    x = resample_poly(x, 16000 // g, sr // g)
                x = x[: 30 * 16000]
                if len(x) < 8000 or np.abs(x).max() < 1e-4:
                    continue
                x = x / np.abs(x).max() * 0.9
                split = "test" if fid % 5 == 0 else "train"
                fn = "fs_%d.wav" % fid
                sf.write(os.path.join(out, split, fn), (x * 32767).astype(np.int16), 16000, subtype="PCM_16")
                rows.append([split, fn, fid, user, "CC0-1.0", "%.2f" % (len(x) / 16000)])
    with open(os.path.join(out, "freesound_manifest.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["split", "file", "freesound_id", "username", "license", "seconds"])
        w.writerows(rows)
    for split in ("train", "test"):
        secs = sum(float(r[5]) for r in rows if r[0] == split)
        print("freesound CC0 %s: %d clips, %.2f h" % (split, sum(r[0] == split for r in rows), secs / 3600))


def make_rirs(out, n, seed):
    import pyroomacoustics as pra
    rng = np.random.default_rng(seed)
    os.makedirs(out, exist_ok=True)
    made = 0
    while made < n:
        dims = np.array([rng.uniform(2.5, 12), rng.uniform(2.5, 10), rng.uniform(2.3, 4.5)])
        rt60 = rng.uniform(0.12, 1.0)
        try:
            e_abs, max_order = pra.inverse_sabine(rt60, dims)
        except ValueError:
            continue
        max_order = min(max_order, 20)
        room = pra.ShoeBox(dims, fs=16000, materials=pra.Material(e_abs), max_order=max_order)
        margin = 0.4
        mic = np.array([rng.uniform(margin, d - margin) for d in dims])
        mic[2] = rng.uniform(0.7, 1.8)
        for _ in range(50):
            src = np.array([rng.uniform(margin, d - margin) for d in dims])
            src[2] = rng.uniform(1.0, 1.9)
            dist = np.linalg.norm(src - mic)
            if 0.3 <= dist <= 5.0:
                break
        room.add_source(src)
        room.add_microphone(mic)
        room.compute_rir()
        h = np.asarray(room.rir[0][0], dtype=np.float64)
        h = h[np.argmax(np.abs(h)) - min(np.argmax(np.abs(h)), 40):][: int(1.2 * 16000)]
        h = h / np.abs(h).max()
        sf.write(os.path.join(out, "rir_%04d.wav" % made), h.astype(np.float32), 16000, subtype="FLOAT")
        made += 1
    print("rirs", out, made)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--parquet", nargs="+", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--n-rir-train", type=int, default=400)
    ap.add_argument("--n-rir-test", type=int, default=100)
    a = ap.parse_args()
    export_freesound(a.parquet, a.out)
    make_rirs(os.path.join(a.out, "rir_train"), a.n_rir_train, 1)
    make_rirs(os.path.join(a.out, "rir_test"), a.n_rir_test, 2)


if __name__ == "__main__":
    main()
