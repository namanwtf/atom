#!/usr/bin/env python3
"""Loads every "Hey Atom" model stage the way the on-device AI pack does and runs each once on silence.

The pack (ondevice-pack/.../TfliteWakeModels.kt) runs these files with LiteRT on the phone's CPU: the melspectrogram
model on 1760 samples, the embedding model on 76 x 32 mel frames, the classifier on 16 x 96 embeddings. LiteRT's Java
Interpreter allocates the tensors in its constructor, at the shapes stored in the file, so the pack first writes the
shape it feeds into the model's stored input shape (core/.../ondevice/TfliteModelFile.kt). This does the same with the
same files and LiteRT version, with and without XNNPack: it writes the stored shape, allocates at it as the Java
constructor does, and runs silence. It also shows what allocating at the file's own stored shape does, which is what
4.0.6 did: the melspectrogram input is stored as [1, 1], and its first CONV_2D (a 512-sample window) cannot be
prepared at that size. Exits non-zero when a stage fails the pack's way. Usage: wake_probe.py <assets/wakeword dir>
"""
import os
import struct
import sys

import numpy as np
from ai_edge_litert.interpreter import Interpreter, OpResolverType

# core/src/main/kotlin/atom/core/ondevice/OnDevice.kt: WakeShapes
STAGES = [
    ("melspectrogram.tflite", [1, 1280 + 480]),
    ("embedding_model.tflite", [1, 76, 32, 1]),
    ("hey_atom.tflite", None),
]


def set_stored_input_shape(data, dims):
    """TfliteModelFile.setStoredInputShape: Model.subgraphs[0] -> inputs[0] -> tensors[i].shape, values only."""
    b = bytearray(data)
    u32 = lambda o: struct.unpack_from("<I", b, o)[0]
    i32 = lambda o: struct.unpack_from("<i", b, o)[0]
    u16 = lambda o: struct.unpack_from("<H", b, o)[0]

    def ref(table, field):
        vtable = table - i32(table)
        slot = 4 + 2 * field
        if slot + 2 > u16(vtable) or u16(vtable + slot) == 0:
            return None
        at = table + u16(vtable + slot)
        return at + u32(at)

    def element(vector, index):
        at = vector + 4 + 4 * index
        return at + u32(at)

    subgraph = element(ref(u32(0), 2), 0)
    tensors, inputs = ref(subgraph, 0), ref(subgraph, 1)
    shape = ref(element(tensors, i32(inputs + 4)), 0)
    if u32(shape) != len(dims):
        return data, False
    owners = sum(1 for i in range(u32(tensors)) for f in (0, 7) if ref(element(tensors, i), f) == shape)
    if owners != 1:
        return data, False
    for i, d in enumerate(dims):
        struct.pack_into("<i", b, shape + 4 + 4 * i, d)
    return bytes(b), True


def interpreter(data, xnnpack):
    resolver = OpResolverType.AUTO if xnnpack else OpResolverType.BUILTIN_WITHOUT_DEFAULT_DELEGATES
    return Interpreter(model_content=data, num_threads=1, experimental_op_resolver_type=resolver)


def main():
    folder = sys.argv[1]
    failed = False
    for xnnpack in (True, False):
        print(f"=== {'XNNPack' if xnnpack else 'plain CPU kernels'} ===")
        for name, shape in STAGES:
            with open(os.path.join(folder, name), "rb") as f:
                data = f.read()
            d = interpreter(data, xnnpack).get_input_details()[0]
            print(f"{name}: stored input {d['shape'].tolist()} (signature {d['shape_signature'].tolist()})")
            try:
                interpreter(data, xnnpack).allocate_tensors()
                print("  allocate at the file's stored shape: ok")
            except Exception as e:  # noqa: BLE001
                print(f"  allocate at the file's stored shape: FAILS ({e})")
            try:
                if shape is not None:
                    data, ok = set_stored_input_shape(data, shape)
                    print(f"  stored shape set to {shape}: {ok}")
                it = interpreter(data, xnnpack)
                it.allocate_tensors()
                d = it.get_input_details()[0]
                it.set_tensor(d["index"], np.zeros(d["shape"], dtype=np.float32))
                it.invoke()
                out = it.get_tensor(it.get_output_details()[0]["index"])
                ok = bool(np.all(np.isfinite(out)))
                print(f"  the pack's way (input {d['shape'].tolist()}), silence -> output {list(out.shape)}"
                      f" min {float(out.min()):.4f} max {float(out.max()):.4f}{'' if ok else ' NOT FINITE'}")
                failed = failed or not ok
            except Exception as e:  # noqa: BLE001
                print(f"  the pack's way: FAILED ({e})")
                failed = True
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
