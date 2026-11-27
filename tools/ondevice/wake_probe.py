#!/usr/bin/env python3
"""Loads every "Hey Atom" model stage the way the on-device AI pack does and runs each once on silence.

The pack (ondevice-pack/.../TfliteWakeModels.kt) runs these files with LiteRT on the phone's CPU: the melspectrogram
model on 1760 samples, the embedding model on 76 x 32 mel frames, the classifier on 16 x 96 embeddings. This runs the
same files with the same LiteRT version on the runner, with and without XNNPack, in the pack's order: each input is
given its shape before the tensors are allocated. It also shows what allocating first does, which is what 4.0.6 did:
the melspectrogram input is stored as [1, 1], and its first CONV_2D (a 512-sample window) cannot be prepared at that
size. Exits non-zero when a stage fails in the pack's order. Usage: wake_probe.py <assets/wakeword dir>
"""
import os
import sys

import numpy as np
from ai_edge_litert.interpreter import Interpreter, OpResolverType

# core/src/main/kotlin/atom/core/ondevice/OnDevice.kt: WakeShapes
STAGES = [
    ("melspectrogram.tflite", [1, 1280 + 480]),
    ("embedding_model.tflite", [1, 76, 32, 1]),
    ("hey_atom.tflite", None),
]


def interpreter(path, xnnpack):
    resolver = OpResolverType.AUTO if xnnpack else OpResolverType.BUILTIN_WITHOUT_DEFAULT_DELEGATES
    return Interpreter(model_path=path, num_threads=1, experimental_op_resolver_type=resolver)


def main():
    folder = sys.argv[1]
    failed = False
    for xnnpack in (True, False):
        label = "XNNPack" if xnnpack else "plain CPU kernels"
        print(f"=== {label} ===")
        for name, shape in STAGES:
            path = os.path.join(folder, name)
            it = interpreter(path, xnnpack)
            d = it.get_input_details()[0]
            print(f"{name}: stored input {d['shape'].tolist()} (signature {d['shape_signature'].tolist()})")
            try:
                interpreter(path, xnnpack).allocate_tensors()
                print("  allocate at the stored shape: ok")
            except Exception as e:  # noqa: BLE001
                print(f"  allocate at the stored shape: FAILS ({e})")
            try:
                if shape is not None and d["shape"].tolist() != shape:
                    it.resize_tensor_input(d["index"], shape, strict=False)
                it.allocate_tensors()
                d = it.get_input_details()[0]
                it.set_tensor(d["index"], np.zeros(d["shape"], dtype=np.float32))
                it.invoke()
                out = it.get_tensor(it.get_output_details()[0]["index"])
                ok = bool(np.all(np.isfinite(out)))
                print(f"  pack order (shape {d['shape'].tolist()}, then allocate), silence -> output {list(out.shape)}"
                      f" min {float(out.min()):.4f} max {float(out.max()):.4f}{'' if ok else ' NOT FINITE'}")
                failed = failed or not ok
            except Exception as e:  # noqa: BLE001
                print(f"  pack order: FAILED ({e})")
                failed = True
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
