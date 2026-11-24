#!/usr/bin/env bash
# Reproduce the "Hey Atom" wake-word model end to end (macOS arm64, ~5.5 GB downloads).
# Usage: WORK=/path/outside/repo bash training/wakeword/run_all.sh
# Every step is idempotent enough to re-run individually; see README.md for details.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
WORK="${WORK:?set WORK to a scratch directory outside the repo}"
DL="$WORK/downloads"; mkdir -p "$DL" "$WORK/work" "$WORK/logs"
PY="$WORK/venv/bin/python -I"
cd "$WORK"

# 0. environment ---------------------------------------------------------------------------
if [ ! -x "$WORK/venv/bin/python" ]; then
  /opt/homebrew/bin/python3.12 -m venv "$WORK/venv"   # brew install python@3.12
  "$WORK/venv/bin/pip" install -r "$HERE/requirements.txt"
fi

# 1. downloads (each into its own directory; nothing is executed from them) -----------------
mkdir -p "$DL/google_speech_embedding/module" "$DL/oww_release" "$DL/piper_voices" "$DL/freesound_tiny" \
         "$DL/librispeech_hf" "$DL/cmudict"
[ -f "$DL/google_speech_embedding/module/variables/variables.index" ] || {
  curl -sSL -o "$DL/google_speech_embedding/speech_embedding_1.tar.gz" \
    "https://tfhub.dev/google/speech_embedding/1?tf-hub-format=compressed"
  tar xzf "$DL/google_speech_embedding/speech_embedding_1.tar.gz" -C "$DL/google_speech_embedding/module"; }
for f in melspectrogram.tflite embedding_model.tflite; do   # numerical reference only, not shipped
  [ -f "$DL/oww_release/$f" ] || curl -sSL -o "$DL/oww_release/$f" \
    "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/$f"; done
for v in en/en_US/libritts/high/en_US-libritts-high en/en_GB/vctk/medium/en_GB-vctk-medium \
         en/en_GB/aru/medium/en_GB-aru-medium en/en_US/hifi_tts_92/medium/en_US-hifi_tts_92-medium \
         en/en_GB/alba/medium/en_GB-alba-medium en/en_US/ljspeech/medium/en_US-ljspeech-medium \
         en/en_US/kristin/medium/en_US-kristin-medium en/en_US/norman/medium/en_US-norman-medium \
         en/en_US/joe/medium/en_US-joe-medium en/en_US/mike/medium/en_US-mike-medium \
         en/en_US/sam/medium/en_US-sam-medium; do
  b=$(basename "$v"); d=$(dirname "$v")
  for ext in onnx onnx.json; do [ -f "$DL/piper_voices/$b.$ext" ] || curl -sSL -o "$DL/piper_voices/$b.$ext" \
    "https://huggingface.co/rhasspy/piper-voices/resolve/main/$v.$ext"; done
  [ -f "$DL/piper_voices/$b.MODEL_CARD" ] || curl -sSL -o "$DL/piper_voices/$b.MODEL_CARD" \
    "https://huggingface.co/rhasspy/piper-voices/resolve/main/$d/MODEL_CARD"
done
for i in 0 1; do f="train-0000$i-of-00004.parquet"; [ -f "$DL/freesound_tiny/$f" ] || curl -sSL -o "$DL/freesound_tiny/$f" \
  "https://huggingface.co/datasets/benjamin-paine/freesound-laion-640k-commercial-16khz-tiny/resolve/main/data/$f"; done
for s in validation.clean validation.other test.clean test.other; do [ -f "$DL/librispeech_hf/$s.parquet" ] || \
  curl -sSL -o "$DL/librispeech_hf/$s.parquet" "https://huggingface.co/datasets/openslr/librispeech_asr/resolve/main/all/$s/0000.parquet"; done
for s in train.clean.100/0000 train.other.500/0000 train.other.500/0001; do f="$DL/librispeech_hf/${s/\//-}.parquet"
  [ -f "$f" ] || curl -sSL -o "$f" "https://huggingface.co/datasets/openslr/librispeech_asr/resolve/main/all/$s.parquet"; done
[ -f "$DL/cmudict/cmudict.dict" ] || curl -sSL -o "$DL/cmudict/cmudict.dict" \
  https://raw.githubusercontent.com/cmusphinx/cmudict/master/cmudict.dict

# 2. feature front-end (rebuilt from Apache-2.0 sources, verified against openWakeWord) -----
$PY "$HERE/build_frontend.py" --hub-module "$DL/google_speech_embedding/module" --out-dir work/frontend \
    --reference-dir "$DL/oww_release"

# 3. data preparation ------------------------------------------------------------------------
$PY "$HERE/prep_noise.py" --parquet "$DL"/freesound_tiny/train-0000[01]-of-00004.parquet --out work/noise
$PY "$HERE/prep_speech.py" --parquet-dir "$DL/librispeech_hf" --out work/speech --lists work/lists
$PY "$HERE/prep_speech.py" --parquet-dir "$DL/librispeech_hf" --out work/speech --lists work/lists \
    --shards train.clean.100-0000:train2 train.other.500-0000:train2
$PY "$HERE/prep_speech.py" --parquet-dir "$DL/librispeech_hf" --out work/speech --lists work/lists \
    --shards train.other.500-0001:val2

G="$PY $HERE/gen_tts.py --voices-dir $DL/piper_voices --cmudict $DL/cmudict/cmudict.dict --workers 9"
$G --kind pos  --split test  --n 3000  --out work/tts/pos_test  --seed 11
$G --kind adv  --split test  --n 2000  --out work/tts/adv_test  --seed 12
$G --kind rand --split test  --n 1500  --out work/tts/rand_test --seed 13
$G --kind pos  --split train --n 12000 --out work/tts/pos_train --seed 21
$G --kind adv  --split train --n 8000  --out work/tts/adv_train --seed 22
$G --kind rand --split train --n 5000  --out work/tts/rand_train --seed 23

# 4. augmentation + features -----------------------------------------------------------------
bash "$HERE/make_features.sh" "$WORK"

# 5. training, calibration on validation data, export, held-out evaluation ---------------------
$PY "$HERE/train.py" --feat-dir work/feat --out work/model --steps 40000 --target-fa 0.2 --seed 0 \
    --long-train speech_train speech_train2 noise_train --max-neg-weight 1500 \
    --tts-long adv_train_long rand_train_long --tts-neg-weight-max 100
$PY "$HERE/calibrate.py" --feat-dir work/feat --ckpt work/model/head.pt --out work/model/head_cal.pt \
    --target-fa 0.3 --target-adv 0.12 --speech-val speech_val speech_val2
mkdir -p work/release
cp work/frontend/melspectrogram.tflite work/frontend/embedding_model.tflite work/release/
$PY "$HERE/export_classifier.py" --ckpt work/model/head_cal.pt --out work/release/hey_atom.tflite
$PY "$HERE/evaluate.py" --models work/release --pos-dir work/tts/pos_test --adv-dir work/tts/adv_test \
    --speech-list work/lists/speech_test.txt --noise-dir work/noise/test --rir-dir work/noise/rir_test \
    --out work/release/eval_report.json --half second --workers 8
mkdir -p "$REPO/voice/src/main/assets/wakeword"
cp work/release/*.tflite "$REPO/voice/src/main/assets/wakeword/"
