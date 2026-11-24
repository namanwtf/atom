#!/usr/bin/env bash
# Development-only streaming check on the VALIDATION half (never the test half).
# Usage: bash dev_eval.sh $WORK <model_dir_with_head.pt> [extra evaluate.py args]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; cd "$1"; M="$2"; shift 2
PY="$PWD/venv/bin/python -I"
mkdir -p "$M/release"; cp work/frontend/*.tflite "$M/release/"
$PY "$HERE/export_classifier.py" --ckpt "$M/head.pt" --out "$M/release/hey_atom.tflite" | grep exported
$PY "$HERE/evaluate.py" --half first --models "$M/release" --pos-dir work/tts/pos_test --adv-dir work/tts/adv_test \
   --speech-list work/lists/sub_speech_val.txt --noise-dir work/val_noise --rir-dir work/noise/rir_train \
   --out "$M/release/dev_eval.json" --workers 5 --max-pos 400 --max-adv 400 "$@" 2>/dev/null | grep -E "^(positives|speech|noise|adversarial)"
