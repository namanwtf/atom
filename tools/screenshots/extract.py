#!/usr/bin/env python3
"""Turns a downloaded screenshots job log back into image files and a texts file.

Usage: extract.py <job-log.txt> <out-dir>
"""

import base64
import os
import re
import sys

STAMP = re.compile(r"^\S+Z ")


def main():
    log, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    name, chunks, texts, tname = None, [], {}, None
    with open(log, encoding="utf-8", errors="replace") as f:
        for raw in f:
            line = STAMP.sub("", raw.rstrip("\n")).lstrip("﻿")
            m = re.match(r"^=====SHOT (\S+)=====$", line)
            t = re.match(r"^=====TEXTS (\S+)=====$", line)
            if m:
                name, chunks, tname = m.group(1), [], None
            elif t:
                tname = t.group(1)
                texts[tname] = []
            elif line == "=====END=====":
                if name:
                    with open(os.path.join(out, os.path.basename(name)), "wb") as img:
                        img.write(base64.b64decode("".join(chunks)))
                name, tname = None, None
            elif name:
                chunks.append(line.strip())
            elif tname:
                texts[tname].append(line)
    with open(os.path.join(out, "texts.txt"), "w", encoding="utf-8") as f:
        for k, v in texts.items():
            f.write(f"## {k}\n" + "\n".join(v) + "\n\n")
    print(f"wrote {len(os.listdir(out)) - 1} images to {out}")


if __name__ == "__main__":
    main()
