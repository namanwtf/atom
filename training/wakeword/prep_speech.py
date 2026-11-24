#!/usr/bin/env python3
"""Export LibriSpeech (CC BY 4.0) parquet shards (openslr/librispeech_asr on Hugging Face)
to 16 kHz wav files and write file lists.

Split usage (speaker-disjoint by construction of LibriSpeech):
  validation.clean + validation.other  -> training negatives (+ babble noise for augmentation)
  test.clean                           -> validation negatives (checkpoint selection)
  test.other                           -> FINAL held-out false-accept evaluation only

  python -I prep_speech.py --parquet-dir downloads/librispeech_hf --out work/speech --lists work/lists
"""
import argparse
import io
import os
import re

import numpy as np
import soundfile as sf

SPLITS = {"validation.clean": "train", "validation.other": "train", "test.clean": "val", "test.other": "test"}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--parquet-dir", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--lists", required=True)
    ap.add_argument("--shards", nargs="*", default=None,
                    help="override as name:role pairs, e.g. train.clean.100-0000:train2")
    a = ap.parse_args()
    splits = dict(x.split(":") for x in a.shards) if a.shards else SPLITS
    import pyarrow.parquet as pq
    os.makedirs(a.lists, exist_ok=True)
    lists = {r: [] for r in splits.values()}
    hits = []
    for shard, role in splits.items():
        od = os.path.join(a.out, shard)
        os.makedirs(od, exist_ok=True)
        pf = pq.ParquetFile(os.path.join(a.parquet_dir, shard + ".parquet"))
        secs = 0.0
        for b in pf.iter_batches(batch_size=128, columns=["audio", "id", "text"]):
            d = b.to_pydict()
            for au, uid, text in zip(d["audio"], d["id"], d["text"]):
                x, sr = sf.read(io.BytesIO(au["bytes"]), dtype="int16")
                assert sr == 16000
                p = os.path.join(od, uid + ".wav")
                if not os.path.exists(p):
                    sf.write(p, x, 16000, subtype="PCM_16")
                lists[role].append(p)
                secs += len(x) / 16000
                if re.search(r"\b(ATOM|ATOMS|ADAM|ADAMS)\b", text):
                    hits.append((shard, uid, text))
        print("%s -> %s: %.2f h" % (shard, role, secs / 3600), flush=True)
    for role, files in lists.items():
        with open(os.path.join(a.lists, "speech_%s.txt" % role), "w") as f:
            f.write("\n".join(files) + "\n")
    if "train" in lists:
        with open(os.path.join(a.lists, "babble_train.txt"), "w") as f:
            f.write("\n".join(lists["train"]) + "\n")
    print("utterances whose transcript contains atom/adam:", len(hits))
    for h in hits[:20]:
        print("  ", h)


if __name__ == "__main__":
    main()
