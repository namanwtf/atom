"""Numpy/scipy audio augmentation (own implementation of the openWakeWord recipe:
EQ, distortion, pitch/speed, band-stop, coloured noise, background noise, RIR, gain).
All audio is float64 in [-1, 1] at 16 kHz.
"""
import glob
import os

import numpy as np
import soundfile as sf
from scipy.signal import fftconvolve, lfilter, resample_poly

SR = 16000


def load_wav(path):
    x, sr = sf.read(path, dtype="float64", always_2d=True)
    assert sr == SR, (path, sr)
    return x.mean(1)


def rms(x):
    return np.sqrt(np.mean(x ** 2) + 1e-12)


def _biquad_peak(f0, gain_db, q):
    a = 10 ** (gain_db / 40)
    w0 = 2 * np.pi * f0 / SR
    alpha = np.sin(w0) / (2 * q)
    b = [1 + alpha * a, -2 * np.cos(w0), 1 - alpha * a]
    aa = [1 + alpha / a, -2 * np.cos(w0), 1 - alpha / a]
    return np.array(b) / aa[0], np.array(aa) / aa[0]


def _biquad(kind, f0, q=0.707):
    w0 = 2 * np.pi * f0 / SR
    alpha = np.sin(w0) / (2 * q)
    c = np.cos(w0)
    if kind == "lp":
        b = [(1 - c) / 2, 1 - c, (1 - c) / 2]
    elif kind == "hp":
        b = [(1 + c) / 2, -(1 + c), (1 + c) / 2]
    else:  # notch / band-stop
        b = [1, -2 * c, 1]
    a = [1 + alpha, -2 * c, 1 - alpha]
    return np.array(b) / a[0], np.array(a) / a[0]


def coloured_noise(n, rng):
    """Noise with 1/f^beta spectrum, beta in [-1, 2] (blue..brown)."""
    beta = rng.uniform(-1, 2)
    spec = np.fft.rfft(rng.standard_normal(n))
    f = np.fft.rfftfreq(n, 1 / SR)
    f[0] = f[1]
    spec *= f ** (-beta / 2)
    x = np.fft.irfft(spec, n)
    return x / (rms(x) + 1e-12)


class Augmenter:
    def __init__(self, noise_dir, rir_dir, babble_files=None, seed=0):
        self.rng = np.random.default_rng(seed)
        self.noise_files = sorted(glob.glob(os.path.join(noise_dir, "*.wav")))
        self.rirs = [load_wav(p) for p in sorted(glob.glob(os.path.join(rir_dir, "*.wav")))]
        self.babble_files = babble_files or []
        self._cache = {}

    def _noise_segment(self, n):
        rng = self.rng
        if self.babble_files and rng.random() < 0.25:
            # babble: 1-3 overlapping speech segments
            out = np.zeros(n)
            for _ in range(rng.integers(1, 4)):
                out += self._segment(rng.choice(self.babble_files), n) * rng.uniform(0.3, 1.0)
            return out
        return self._segment(rng.choice(self.noise_files), n)

    def _segment(self, path, n):
        if path not in self._cache:
            if len(self._cache) > 400:
                self._cache.pop(next(iter(self._cache)))
            self._cache[path] = load_wav(path)
        x = self._cache[path]
        if len(x) < n:
            x = np.tile(x, int(np.ceil(n / len(x))) + 1)
        s = self.rng.integers(0, len(x) - n + 1)
        return x[s:s + n]

    def rir(self, x):
        h = self.rirs[self.rng.integers(len(self.rirs))]
        y = fftconvolve(x, h)[: len(x)]
        return y * (rms(x) / (rms(y) + 1e-12))

    def colour(self, x):
        rng = self.rng
        if rng.random() < 0.3:  # random 2-band parametric EQ
            for _ in range(2):
                b, a = _biquad_peak(rng.uniform(100, 7000), rng.uniform(-6, 6), rng.uniform(0.5, 2.0))
                x = lfilter(b, a, x)
        if rng.random() < 0.2:  # phone / cheap mic band-limiting
            b, a = _biquad("lp", rng.uniform(3000, 7500))
            x = lfilter(b, a, x)
        if rng.random() < 0.2:
            b, a = _biquad("hp", rng.uniform(80, 400))
            x = lfilter(b, a, x)
        if rng.random() < 0.15:
            b, a = _biquad("notch", rng.uniform(300, 6000), rng.uniform(0.5, 3))
            x = lfilter(b, a, x)
        if rng.random() < 0.15:
            k = rng.uniform(1, 8)
            x = np.tanh(k * x) / np.tanh(k)
        return x

    def add_background(self, x, snr_lo, snr_hi, p_bg=0.8, p_col=0.25):
        rng = self.rng
        n = len(x)
        sig = rms(x)
        if rng.random() < p_bg:
            nz = self._noise_segment(n)
            snr = rng.uniform(snr_lo, snr_hi)
            x = x + nz / (rms(nz) + 1e-12) * sig / 10 ** (snr / 20)
        if rng.random() < p_col:
            snr = rng.uniform(10, 40)
            x = x + coloured_noise(n, rng) * sig / 10 ** (snr / 20)
        return x

    def finalize(self, x):
        """Random level, dither floor, clip, return int16-scale float32."""
        rng = self.rng
        peak = np.abs(x).max() + 1e-9
        x = x / peak * 10 ** (rng.uniform(-30, -1) / 20)
        x = x + rng.standard_normal(len(x)) * 10 ** (rng.uniform(-80, -60) / 20)
        return (np.clip(x, -1, 1) * 32767).astype(np.float32)

    def speed(self, x):
        f = self.rng.uniform(0.9, 1.1)
        up = 100
        return resample_poly(x, up, int(round(up * f)))

    def fixed_clip(self, path, total, end_jitter=0.2, p_speed=0.3, p_rir=0.5, snr=(-3, 20), min_tail=0.0):
        """Place a short clip so it ends 0..end_jitter s before the window end, then augment."""
        rng = self.rng
        x = load_wav(path)
        if rng.random() < p_speed:
            x = self.speed(x)
        x = x[-total:]
        out = np.zeros(total)
        end = total - int((min_tail + rng.uniform(0, end_jitter)) * SR)
        start = max(0, end - len(x))
        out[start:end] = x[len(x) - (end - start):]
        if rng.random() < p_rir:
            out = self.rir(out)
        out = self.colour(out)
        # SNR relative to the speech-active part, not the zero-padded window
        act = np.zeros(total)
        act[start:end] = 1
        sig = rms(out[act > 0])
        bg = self._noise_segment(total) if rng.random() < 0.8 else np.zeros(total)
        if bg.any():
            s = rng.uniform(*snr)
            out = out + bg / (rms(bg) + 1e-12) * sig / 10 ** (s / 20)
        if rng.random() < 0.25:
            out = out + coloured_noise(total, rng) * sig / 10 ** (rng.uniform(10, 40) / 20)
        return self.finalize(out)

    def long_audio(self, x, p_rir=0.5, snr=(0, 25), p_bg=0.5):
        if self.rng.random() < p_rir:
            x = self.rir(x)
        x = self.colour(x)
        x = self.add_background(x, *snr, p_bg=p_bg)
        return self.finalize(x)
