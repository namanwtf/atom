#!/usr/bin/env bash
# Augmentation + feature extraction for training/validation (the exact set used for the shipped model).
# Usage: bash make_features.sh $WORK
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$1"
PY="$1/venv/bin/python -I"
HUB=downloads/google_speech_embedding/module
F="$PY $HERE/features.py"
TR="--noise-dir work/noise/train --rir-dir work/noise/rir_train --hub-module $HUB"
TE="--noise-dir work/noise/test --rir-dir work/noise/rir_test --hub-module $HUB"
BAB="--babble-list work/lists/babble_train.txt"
mkdir -p work/feat work/lists

# validation = first half of each held-out TTS set (second half is reserved for evaluate.py)
half() { find "$1" -name "*.wav" | sort | head -n $(( $(find "$1" -name "*.wav" | wc -l) / 2 )); }
half work/tts/pos_test > work/lists/pos_val.txt
{ half work/tts/adv_test; half work/tts/rand_test; } > work/lists/neg_val_tts.txt
find work/tts/adv_train -name "*.wav" | sort > work/lists/adv_train.txt
find work/tts/rand_train -name "*.wav" | sort > work/lists/rand_train.txt
find work/noise/train -name "*.wav" | sort > work/lists/noise_train.txt

# negatives: long recordings (per-file embedding sequences)
[ -f work/feat/noise_train.npy ]   || $F long --list work/lists/noise_train.txt   --rounds 1 --out work/feat/noise_train   $TR --workers 3 --seed 5
[ -f work/feat/speech_train.npy ]  || $F long --list work/lists/speech_train.txt  --rounds 2 --out work/feat/speech_train  $TR $BAB --workers 3 --seed 6
[ -f work/feat/speech_train2.npy ] || $F long --list work/lists/speech_train2.txt --rounds 1 --out work/feat/speech_train2 $TR $BAB --workers 3 --seed 13
[ -f work/feat/speech_val.npy ]    || $F long --list work/lists/speech_val.txt    --rounds 1 --out work/feat/speech_val    $TR --workers 2 --seed 7
[ -f work/feat/speech_val2.npy ]   || $F long --list work/lists/speech_val2.txt   --rounds 1 --out work/feat/speech_val2   $TR --workers 3 --seed 14

# positives: 2.0 s windows, phrase end 0-200 ms before the window end (3 augmentation rounds)
[ -f work/feat/pos_train.npy ] || $F clips --wav-dir work/tts/pos_train --rounds 3 --out work/feat/pos_train.npy $TR $BAB --workers 3 --seed 8
# TTS negatives: 3.0 s windows with the phrase placed anywhere (every alignment is trained)
J="--total-sec 3.0 --tail-sec 0.0 --end-jitter-sec 1.8"
[ -f work/feat/adv_train_long.npy ]  || $F clips --list work/lists/adv_train.txt  --rounds 2 --out work/feat/adv_train_long.npy  $J $TR $BAB --workers 3 --seed 41
[ -f work/feat/rand_train_long.npy ] || $F clips --list work/lists/rand_train.txt --rounds 1 --out work/feat/rand_train_long.npy $J $TR $BAB --workers 3 --seed 42

# validation features (held-out speakers, held-out noise + RIRs)
[ -f work/feat/pos_val.npy ]          || $F clips --list work/lists/pos_val.txt --rounds 1 --out work/feat/pos_val.npy $TE --workers 4 --seed 11
[ -f work/feat/pos_val_long.npy ]     || $F clips --list work/lists/pos_val.txt --rounds 1 --out work/feat/pos_val_long.npy \
                                           --total-sec 2.8 --tail-sec 0.6 $TE --workers 4 --seed 31
[ -f work/feat/neg_val_tts.npy ]      || $F clips --list work/lists/neg_val_tts.txt --rounds 1 --out work/feat/neg_val_tts.npy $TE --workers 4 --seed 12
[ -f work/feat/neg_val_tts_long.npy ] || $F clips --list work/lists/neg_val_tts.txt --rounds 1 --out work/feat/neg_val_tts_long.npy $J $TE --workers 3 --seed 43
echo FEATURES_DONE
