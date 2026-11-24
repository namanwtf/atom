# "Hey Atom" wake-word model

Build scripts for the three TFLite files in `voice/src/main/assets/wakeword/`:

| File | Role | Input | Output |
|---|---|---|---|
| `melspectrogram.tflite` | log-mel front-end (fixed DSP, no learned weights) | `[1, N]` float32, raw 16 kHz PCM **in int16 range** (not normalised to ±1). The runtime uses N = 1760 (1280 new samples + 480 history) | `[1, 1, F, 32]` float32, F = 1 + (N − 512) // 160 (8 for N = 1760) |
| `embedding_model.tflite` | Google speech_embedding (frozen) | `[1, 76, 32, 1]` float32, last 76 mel frames **after** `x / 10 + 2` | `[1, 1, 1, 96]` float32 |
| `hey_atom.tflite` | our classifier | `[1, 16, 96]` float32, last 16 embeddings | `[1, 1]` float32 score in 0..1 (sigmoid) |

These match openWakeWord's TFLite format exactly. The two feature models have dynamic
batch/length dimensions (signature `[-1,-1]`, `[-1,76,32,1]`), like openWakeWord's, so
resizing the input tensor works. They contain only TFLite builtin ops: CONV_2D,
FULLY_CONNECTED, LOG, MAXIMUM, REDUCE_MAX, MAX_POOL_2D, LEAKY_RELU, PAD, MUL, ADD, SUB,
SQUEEZE, EXPAND_DIMS. No Flex/Select-TF ops.

## How to run it on Android (openWakeWord streaming loop)

Every 80 ms (1280 new samples at 16 kHz, mono, 16-bit):
1. Keep the last 1760 samples. Run `melspectrogram.tflite` on them as float32, without
   dividing by 32768. You get 8 new mel frames. Apply `x / 10 + 2` to each value and
   append the frames to a mel buffer. Initialise that buffer with 76 frames of `1.0`.
2. Run `embedding_model.tflite` on the last 76 mel frames to get one 96-d embedding.
   Append it to a feature buffer. openWakeWord seeds this buffer with embeddings of 4 s of
   random noise in [-1000, 1000].
3. Run `hey_atom.tflite` on the last 16 embeddings. Score >= 0.5 means "wake". Ignore the
   first 5 scores after a reset, and apply a refractory period of about 1.3 s (16 frames)
   after a detection.

`evaluate.py` (class `Streamer`) is a line-by-line reference implementation of this loop.
It is what all the numbers below were measured with.

## Results (held-out data, full streaming TFLite pipeline, threshold 0.5)

Measured with `evaluate.py --half second` on data that training, checkpoint selection and
calibration never saw: unseen TTS speakers plus the unseen `en_US-sam` voice, LibriSpeech
test-other, held-out CC0 noise and held-out RIRs. Raw numbers are in
`manifests/eval_report_test.json`.

| Metric | Shipped model (threshold 0.5) | Target |
|---|---|---|
| Recall, held-out synthetic "hey atom", quiet (40 dB SNR bed) | **0.895** (n=1500) | ≥ 0.85 met |
| Recall, same clips at 5–20 dB SNR, 50 % reverberated | **0.788** (n=1500) | ≥ 0.85 **missed** |
| False accepts per hour, LibriSpeech test-other (5.34 h, half noise/reverb-mixed) | 0.37 (2 events) | |
| False accepts per hour, held-out CC0 background audio (3.73 h) | 0.00 | |
| **False accepts per hour, all negatives (9.07 h)** | **0.22** | ≤ 0.5 met |
| Adversarial TTS phrases, share that trigger (quiet / noisy) | 0.144 / 0.174 (n=1000) | |
| "hey adam" / "hey, adam" | 0.43 / 0.50 (quiet), 0.52 / 0.43 (noisy) | |
| "adam" alone / "atom" alone | 0.03 / 0.02 | |
| "hey" alone, "hey tom", "hey alexa", "hey siri" | 0.03, 0.00, 0.00, 0.00 | |
| Classifier size | **47.7 KB** (48,796 bytes, 10,993 float32 parameters) | tens of KB |

**Sensitivity trade-off.** The same weights with the decision bias 1.35 logits lower, i.e. a
threshold of about **0.21** on the shipped model, gave recall 0.967 quiet and 0.876 noisy on
the same test set. At that setting false accepts are 1.10 per hour (1.31 on speech, 0.80 on
noise), and "hey adam" triggers 73 % of the time. So with synthetic-only training we could
not reach both targets at once. We shipped the low-false-accept point. The app can expose
the threshold as a sensitivity setting: roughly 0.2 is sensitive, 0.5 is the default and
0.7 is strict.

**How the operating point was chosen (disclosure).** `calibrate.py` sets the 0.5 decision
point from validation data only. Our first calibration used LibriSpeech test-clean alone,
and its held-out test run is the 0.21-equivalent row above: false accepts of 1.1 per hour,
much higher than that validation set had suggested. We therefore added a harder, unseen
validation shard (LibriSpeech train-other-500 shard 0001, 7.5 h) and re-calibrated on both
validation sets pooled, with the rule fixed in advance: highest validation recall subject to
at most 0.3 false accepts per hour and at most 12 % adversarial triggers. That gave the
shipped point, which was tested once. The test set was evaluated twice in total, and the
choice between the two points was made on validation.

Tensor shapes (all float32; batch is dynamic for the two feature models):

```
melspectrogram.tflite   in [1, N]  (sig [-1, -1])           out [1, 1, F, 32] (sig [-1, 1, -1, 32])
embedding_model.tflite  in [1, 76, 32, 1] (sig [-1,76,32,1]) out [1, 1, 1, 96] (sig [-1, 1, 1, 96])
hey_atom.tflite         in [1, 16, 96]                       out [1, 1]
```

File sizes: melspectrogram 1,090,836 B; embedding_model 1,327,688 B; hey_atom 48,796 B.
SHA-256: melspectrogram `69f3097c…4cbb`, embedding_model `b2945a1f…4ba8`, hey_atom `8aa96e82…f5a7`.

## Data and method

The method is openWakeWord's custom-model recipe (README, `notebooks/automatic_model_training.ipynb`,
`openwakeword/train.py`, v0.6.0), with every non-commercial or unclear input swapped for a
clean one. See `LICENSES.md` for each artifact and its licence.

- **Front-end.** `build_frontend.py` rebuilds both feature models from Apache-2.0 sources:
  the DSP parameters, and Google's `speech_embedding/1` weights. It then checks them against
  openWakeWord's v0.5.1 files (max abs diff: mel 1.5e-5, embedding 5e-5).
- **Positives.** 13,410 training clips of "hey atom" (3 augmentation rounds, giving 40k examples) and 3,000 held-out clips, from 11
  Piper voices (about 1,040 speakers), run directly through onnxruntime. Speed, noise and
  phoneme-duration parameters are randomised. Phonemes are hand-written espeak-style IPA
  covering US (flapped t), British (glottal t), careful-t and Indian-English-like vowel
  and retroflex-t realisations, and the paused form "hey, atom" (`phrases.py`). No
  espeak-ng or other GPL code is used. Test speakers are speaker ids with id % 10 == 7, plus the
  whole `en_US-sam` voice. None of them is used in training.
- **Adversarial negatives.** 8,000 TTS clips (2 augmentation rounds) of confusable phrases: "hey adam",
  "hey, adam", "adam", "atom", "the atom", "atomic", "anatomy", "hey tom", "hey autumn",
  "hey item", "hey madam", "hey at home", "hey add them", other assistants' wake words,
  and Indian names such as Aman, Amit and Ahmed. These are phonemized with our
  ARPAbet-to-IPA mapping over CMUdict. There are also 5,000 random word sequences spoken
  by the same voices, so the model cannot learn "TTS voice = positive". TTS negatives are
  placed at random positions inside 3.0 s windows, and training samples every 16-frame
  alignment. This covers what the streaming detector sees before, during and after a
  phrase. Without it, "hey" plus any word or silence triggered the model.
- **Negative audio.** For training: LibriSpeech dev-clean and dev-other (10.5 h, augmented
  twice), train-clean-100 and train-other-500 shard 0000 (15.1 h), and 15.1 h of CC0
  Freesound background audio. For validation (checkpoint selection and calibration):
  LibriSpeech test-clean (5.4 h) and train-other-500 shard 0001 (7.5 h). For the final
  numbers only: LibriSpeech test-other (5.3 h) and the 3.7 h held-out Freesound split.
- **Augmentation.** `augment.py` re-implements the openWakeWord augmentation set in
  numpy/scipy: parametric EQ, band-limiting, notch, tanh distortion, ±10 % speed and
  pitch, coloured noise, background noise or LibriSpeech babble at -3 to 20 dB SNR, and
  synthetic room impulse responses (pyroomacoustics image-source shoebox rooms, RT60
  0.12–1.0 s, 400 train / 100 test). It also randomises level from -30 to -1 dBFS and adds
  a dither floor. Each sample is a 2.0 s window with the phrase ending 0–200 ms before the
  window end, which gives 16 embeddings.
- **Classifier.** `train.py`: per-frame Linear(96→16), then flatten(256), Linear(32), LayerNorm,
  ReLU, Linear(32), LayerNorm, ReLU, Linear(1), sigmoid. That is 10,993 parameters. Training follows openWakeWord's
  schedule: BCE, negative-class weight rising linearly to `max_neg_weight`, only
  high-loss samples kept, with accumulation to at least 128 samples per step, warmup +
  hold + cosine LR, batches of 128 positives + 128 TTS negatives (weight capped at 100) + 1024 random
  16-frame windows of negative audio (weight ramping to 1500), 40k steps, seed 0. Wider
  heads (24/48 and 32/64), dropout/weight decay, 2048-window negative batches, 80k steps
  and other seeds were tried. None did better on validation. `calibrate.py` then folds the
  validation-chosen operating point into the output bias, so the app's fixed 0.5 threshold
  applies. Training ran on CPU. The bulk feature extraction ran on Apple MPS
  (`frontend_torch.py`, checked against the TFLite models to 1e-4).
- **Export.** `export_classifier.py` builds a TF graph with the same weights, converts it
  to float32 TFLite, and checks it against PyTorch (max abs diff < 1e-4).

## Reproduce

Needs macOS arm64, Homebrew `python@3.12`, about 5.5 GB of downloads and about 25 GB of scratch space.
The original run took about 3.4 h wall-clock on a heavily shared M-series Mac. Piper's
`libritts-high` voice runs at about 1.4 s per clip on CPU and dominates the run time.

```bash
brew install python@3.12
WORK=/some/scratch/dir bash training/wakeword/run_all.sh
```

`run_all.sh` creates the venv (`requirements.txt`) and downloads everything into
`$WORK/downloads/<source>/`. All scripts that read downloaded data run with `python -I`
from this folder, never from a download folder. It then builds the front-end, prepares
noise, RIRs and speech, generates TTS (`gen_tts.py`), extracts features
(`make_features.sh`), trains, calibrates, exports, evaluates, and copies the three `.tflite` files into
`voice/src/main/assets/wakeword/`. Piper "high" voices are slow on CPU, so TTS generation
is the longest step.

| Script | What it does |
|---|---|
| `build_frontend.py` | Rebuilds `melspectrogram.tflite` and `embedding_model.tflite`, and verifies them |
| `phrases.py` | Wake-phrase IPA variants, adversarial list, CMUdict ARPAbet→IPA |
| `gen_tts.py` | Piper ONNX synthesis with a train/test speaker split and a manifest CSV per output dir |
| `prep_noise.py` | CC0 Freesound export and synthetic RIRs |
| `prep_speech.py` | LibriSpeech parquet → wav, plus split lists |
| `augment.py` | Augmentation |
| `features.py`, `frontend_torch.py`, `make_features.sh` | Augmented feature extraction (MPS) |
| `train.py` | Classifier training and checkpoint selection |
| `calibrate.py` | Picks the operating point on validation and folds it into the output bias |
| `dev_eval.sh` | Development-only streaming check on the validation half |
| `export_classifier.py` | PyTorch → TFLite, with an equivalence check |
| `evaluate.py` | Full streaming TFLite evaluation (recall, false accepts per hour, adversarial phrases) |

## Caveats

- **Synthetic-only training.** Every positive is Piper TTS. Before release, test with real
  voices on real phones: Indian-English speakers, far-field, fan or TV noise, and the actual
  mic gain. Then tune the threshold. A few hundred real "hey atom" recordings from target
  users (with consent) would be the biggest single improvement.
- **"Hey Adam" is not reliably rejected.** About 45 % still trigger. In US English "atom"
  and "Adam" are both flapped (`ˈæɾəm`), so they are close to homophones. Rejecting them
  harder cost too much recall. Suggested app-side fix: after a wake, check the ASR
  transcript of the first second and drop it if it reads "adam". "adam" and "atom" on their
  own, and bare "hey", are well rejected.
- **Noisy recall is below target** (0.79 at 5–20 dB SNR). See the sensitivity trade-off
  above.
- The false-accept test set is read speech plus CC0 sound effects and ambience, 9 h in
  total. It contains no conversational TV audio, no music beyond what Freesound includes,
  and no Hindi or Hinglish speech. A false-accept rate of 0.22/h is based on 2 events, so
  the confidence interval is wide. openWakeWord's usual negatives (ACAV100M, about 2,000 h)
  are CC BY-NC-SA, so we could not use them.
- Accent coverage comes from 11 permissively licensed Piper voices (about 1,040 speakers;
  VCTK includes a few Indian-accented speakers) and from hand-written IPA variants. No
  permissively licensed Indian-English or Hindi Piper voice exists, so Indian accents are
  only approximated.
- The TFLite files come from TensorFlow 2.20's converter and use only builtin ops. Use a
  recent LiteRT/TFLite runtime on Android (2.16 or newer is safe). If an older runtime rejects
  an op version, re-export with an older TF.
- The classifier's output bias contains the calibration shift (`logit_shift = -0.847`,
  stored in `head.pt`). Retraining requires re-running `calibrate.py`.
- Build-time tools: espeak-ng and other GPL software were not used. soundfile's bundled
  libsndfile (LGPL) was used only to read and write wav files on the training machine.
