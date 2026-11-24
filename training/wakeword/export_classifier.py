#!/usr/bin/env python3
"""Export the trained PyTorch head (train.py) to a float32 TFLite model in openWakeWord's
classifier format: input [1, 16, 96] float32 embeddings -> output [1, 1] float32 score.

  python -I export_classifier.py --ckpt work/model/head.pt --out hey_atom.tflite
"""
import argparse
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "3")
import tensorflow as tf  # noqa: E402
import torch  # noqa: E402

from train import Head  # noqa: E402

LN_EPS = 1e-5


class TFHead(tf.Module):
    def __init__(self, sd):
        super().__init__()
        c = lambda k: tf.constant(sd[k].numpy().astype(np.float32))  # noqa: E731
        self.pw, self.pb = c("proj.weight"), c("proj.bias")
        self.w1, self.b1, self.g1, self.be1 = c("fc1.weight"), c("fc1.bias"), c("ln1.weight"), c("ln1.bias")
        self.w2, self.b2, self.g2, self.be2 = c("fc2.weight"), c("fc2.bias"), c("ln2.weight"), c("ln2.bias")
        self.wo, self.bo = c("out.weight"), c("out.bias")
        self.frames, self.dim = 16, 96

    @staticmethod
    def _ln(x, g, b):
        m = tf.reduce_mean(x, -1, keepdims=True)
        v = tf.reduce_mean(tf.square(x - m), -1, keepdims=True)
        return (x - m) * tf.math.rsqrt(v + LN_EPS) * g + b

    @tf.function(input_signature=[tf.TensorSpec([1, 16, 96], tf.float32, name="input")])
    def __call__(self, x):
        x = tf.reshape(x, [16, 96])
        x = tf.matmul(x, self.pw, transpose_b=True) + self.pb  # [16, P]
        x = tf.reshape(x, [1, -1])
        x = tf.nn.relu(self._ln(tf.matmul(x, self.w1, transpose_b=True) + self.b1, self.g1, self.be1))
        x = tf.nn.relu(self._ln(tf.matmul(x, self.w2, transpose_b=True) + self.b2, self.g2, self.be2))
        return tf.sigmoid(tf.matmul(x, self.wo, transpose_b=True) + self.bo)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ckpt", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    ck = torch.load(a.ckpt, map_location="cpu")
    head = Head(ck["proj"], ck["hidden"])
    head.load_state_dict(ck["state_dict"])
    head.eval()
    m = TFHead(ck["state_dict"])
    conv = tf.lite.TFLiteConverter.from_concrete_functions([m.__call__.get_concrete_function()], m)
    conv.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
    with open(a.out, "wb") as f:
        f.write(conv.convert())
    it = tf.lite.Interpreter(model_path=a.out)
    it.allocate_tensors()
    rng = np.random.default_rng(0)
    worst = 0.0
    for _ in range(200):
        x = (rng.normal(0, 1, (1, 16, 96)) * rng.uniform(0.5, 20)).astype(np.float32)
        it.set_tensor(it.get_input_details()[0]["index"], x)
        it.invoke()
        y = it.get_tensor(it.get_output_details()[0]["index"])
        with torch.no_grad():
            t = head(torch.tensor(x)).numpy()
        worst = max(worst, float(np.abs(y - t).max()))
    print("exported", a.out, os.path.getsize(a.out), "bytes; max|tflite - torch| = %.2e" % worst)
    print("in", [(d["shape"].tolist(), d["dtype"].__name__) for d in it.get_input_details()],
          "out", [(d["shape"].tolist(), d["dtype"].__name__) for d in it.get_output_details()],
          "ops", sorted(set(o["op_name"] for o in it._get_ops_details())))
    assert worst < 1e-4


if __name__ == "__main__":
    main()
