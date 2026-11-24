"""PyTorch port of the feature front-end, used ONLY for fast bulk feature extraction on
Apple MPS during training. Weights/constants come from build_frontend.py, and
`verify()` checks it numerically against the shipped TFLite models before use.
Deployment uses the TFLite files, never this module.
"""
import numpy as np
import torch
import torch.nn.functional as F

import build_frontend as bf


def device():
    return torch.device("mps") if torch.backends.mps.is_available() else torch.device("cpu")


class TorchFrontend:
    def __init__(self, hub_module_dir, dev=None):
        self.dev = dev or device()
        real, imag = bf.stft_kernels()
        k = np.concatenate([real, imag], 1).T[:, None, :]  # [514, 1, 512]
        self.stft = torch.tensor(k, dtype=torch.float32, device=self.dev)
        self.fb = torch.tensor(bf.mel_filterbank().T, dtype=torch.float32, device=self.dev)  # [257, 32]
        w = bf.load_hub_weights(hub_module_dir)
        emb = bf.Embedding(w)
        self.ops = []
        for name, e in emb.ops:
            if e is None:
                self.ops.append((name, None))
                continue
            k = torch.tensor(e["kernel"].numpy().transpose(3, 2, 0, 1), dtype=torch.float32, device=self.dev)
            d = {"kernel": k, "pad": e["pad"]}
            if "scale" in e:
                d["scale"] = torch.tensor(e["scale"].numpy(), device=self.dev)[None, :, None, None]
                d["shift"] = torch.tensor(e["shift"].numpy(), device=self.dev)[None, :, None, None]
            self.ops.append((name, d))

    @torch.no_grad()
    def melspec(self, x):
        """x: float32 [B, N] int16-scale audio -> [B, frames, 32] TRANSFORMED log-mel (x/10+2),
        with top_db applied per clip (equivalent to running melspectrogram.tflite per clip)."""
        x = torch.as_tensor(x, dtype=torch.float32, device=self.dev)[:, None, :]
        s = F.conv1d(x, self.stft, stride=bf.HOP)  # [B, 514, frames]
        re, im = s[:, :257], s[:, 257:]
        p = (re * re + im * im).transpose(1, 2)  # [B, frames, 257]
        m = p @ self.fb
        db = (10.0 / np.log(10.0)) * torch.log(torch.clamp(m, min=bf.AMIN))
        mx = db.amax(dim=(1, 2), keepdim=True)
        db = torch.maximum(db, mx - bf.TOP_DB)
        return db / 10 + 2

    @torch.no_grad()
    def embed_windows(self, w):
        """w: [B, 76, 32] -> [B, 96]"""
        x = torch.as_tensor(w, dtype=torch.float32, device=self.dev)[:, None]  # NCHW, C=1
        for name, e in self.ops:
            if name == "pool22":
                x = F.max_pool2d(x, (2, 2), (2, 2))
            elif name == "pool12same":
                if x.shape[-1] % 2:
                    x = F.pad(x, (0, 1), value=-1e9)
                x = F.max_pool2d(x, (1, 2), (1, 2))
            elif name == "pool12":
                x = F.max_pool2d(x, (1, 2), (1, 2))
            else:
                k = e["kernel"]
                if e["pad"] == "valid_pad_w":
                    x = F.conv2d(F.pad(x, (1, 1, 0, 0)), k)
                elif e["pad"] == "same":
                    kh, kw = k.shape[2], k.shape[3]
                    x = F.conv2d(F.pad(x, ((kw - 1) // 2, kw // 2, (kh - 1) // 2, kh // 2)), k)
                else:
                    x = F.conv2d(x, k)
                if "scale" in e:
                    x = x * e["scale"] + e["shift"]
                    x = torch.maximum(0.2 * x, x)
                    x = torch.clamp(x, min=-0.4)
        return x.reshape(x.shape[0], -1)

    @torch.no_grad()
    def embed_audio(self, x, chunk=4096):
        """x: float32 [B, N] equal-length clips -> embeddings [B, n_emb, 96] (numpy float32)."""
        mel = self.melspec(x)  # [B, T, 32]
        T = mel.shape[1]
        starts = list(range(0, T - 75, 8))
        wins = torch.stack([mel[:, s:s + 76] for s in starts], 1)  # [B, n, 76, 32]
        B, n = wins.shape[:2]
        flat = wins.reshape(B * n, 76, 32)
        out = torch.cat([self.embed_windows(flat[i:i + chunk]) for i in range(0, flat.shape[0], chunk)])
        return out.reshape(B, n, 96).cpu().numpy()


def verify(fe, mel_tflite, emb_tflite, n=3, seed=0):
    rng = np.random.default_rng(seed)
    worst = 0.0
    for i in range(n):
        x = np.clip(rng.normal(0, rng.uniform(100, 8000), (1, 32000)), -32768, 32767).astype(np.float32)
        m_ref = bf.run_tflite(mel_tflite, x)[0, 0] / 10 + 2
        m_t = fe.melspec(x)[0].cpu().numpy()
        win = np.stack([m_ref[s:s + 76] for s in range(0, m_ref.shape[0] - 75, 8)])
        e_ref = bf.run_tflite(emb_tflite, win[..., None]).reshape(len(win), 96)
        e_t = fe.embed_audio(x)[0]
        worst = max(worst, float(np.abs(m_ref - m_t).max()), float(np.abs(e_ref - e_t).max()))
    return worst
