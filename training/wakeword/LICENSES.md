# Third-party artifacts used for the "Hey Atom" wake-word model

Every model, voice, dataset, dictionary and tool that touched the shipped files or their
training data, with its licence. "Shipped" means it ends up inside the APK
(`voice/src/main/assets/wakeword/`). Everything else was used only at build time on the
training machine and is not redistributed.

Last checked: 2026-11-25.

## Shipped files and where they come from

| Shipped file | Derived from | Licence of the source | Notes |
|---|---|---|---|
| `melspectrogram.tflite` | No learned weights. A fixed DSP transform (STFT, Hann window, Slaney mel filterbank, log) rebuilt in `build_frontend.py`, using the parameters documented in openWakeWord's `notebooks/converting_google_speech_embedding_model.ipynb` | openWakeWord code and notebooks: **Apache-2.0** | Built by us from source. Numerically identical to openWakeWord v0.5.1 `melspectrogram.tflite` (max abs diff 1.5e-5 dB), so it is a drop-in replacement. |
| `embedding_model.tflite` | Weights of Google `speech_embedding/1` (TF-Hub / Kaggle Models, `google/speech-embedding`), laid out as in the openWakeWord notebook above | Google speech_embedding: **Apache-2.0** (Kaggle model metadata: `licenseName: Apache 2.0`). openWakeWord notebook: **Apache-2.0** | Built by us from Google's checkpoint. Numerically identical to openWakeWord v0.5.1 `embedding_model.tflite` (max abs diff 5e-5). |
| `hey_atom.tflite` | Our own classifier, trained from scratch on the data listed below | Ours | It does not use or derive from any openWakeWord pre-trained wake-word model. |

Attribution owed in the app's open-source notices screen:
- Google LLC, "speech_embedding" model, Apache License 2.0 (J. Lin, K. Kilgour, D. Roblek, M. Sharifi, *Training Keyword Spotters with Limited and Synthesized Speech Data*, ICASSP 2020).
- David Scripka, openWakeWord (method, front-end layout and streaming design), Apache License 2.0.

### Why we rebuilt the two feature models instead of copying openWakeWord's
openWakeWord's README puts its code under Apache-2.0 and "all of the included pre-trained
models" under CC BY-NC-SA 4.0 (non-commercial). It does not say which side the two
shared feature files fall on. Issues #313, #338, #346, #348 and #357 ask this, and as of
2026-11-25 none has an answer from the maintainer. To avoid depending on that, we rebuilt
both files from their Apache-2.0 sources. The v0.5.1 release files were downloaded
only as a numerical reference for `build_frontend.py --reference-dir`. They are not
shipped, and nothing is derived from them.

## Training data (build time only, not shipped)

| Artifact | Used for | Licence | Lineage checked |
|---|---|---|---|
| Piper voices, `rhasspy/piper-voices` on Hugging Face (repo licence MIT). Per-voice dataset licences below | Synthetic "hey atom" positives and adversarial negatives | see rows | each MODEL_CARD read |
| `en_US-libritts-high` (904 speakers) | TTS | LibriTTS, **CC BY 4.0** | trained from scratch |
| `en_GB-vctk-medium` (109 speakers) | TTS | VCTK 0.92, **CC BY 4.0** | fine-tuned from `hifi_tts_92` |
| `en_GB-aru-medium` (12 speakers) | TTS | Univ. of Liverpool ARU speech corpus, **CC BY 4.0** | fine-tuned from `hifi_tts_92` |
| `en_US-hifi_tts_92-medium` | TTS | Hi-Fi Multi-Speaker English TTS (NVIDIA), **CC BY 4.0** | trained from scratch (base model) |
| `en_GB-alba-medium` | TTS | Edinburgh DataShare 10283/3270, **CC BY 4.0** | fine-tuned from `hifi_tts_92` |
| `en_US-ljspeech-medium` | TTS | LJ Speech, **public domain** | trained from scratch |
| `en_US-kristin-medium` | TTS | LibriVox, **public domain** | trained from scratch |
| `en_US-norman-medium` | TTS | LibriVox, **public domain** | trained from scratch |
| `en_US-joe-medium`, `en_US-mike-medium` | TTS | OHF-Voice voice-datasets, **CC0** | fine-tuned from `nl_BE-rdh` (CC0, trained from scratch) |
| `en_US-sam-medium` (held out, test only) | TTS | Sam non-binary voice files, **Apache-2.0** | fine-tuned from `nl_BE-rdh` (CC0) |
| LibriSpeech dev-clean, dev-other, test-clean, test-other, plus parquet shards `train.clean.100/0000`, `train.other.500/0000` and `train.other.500/0001` (via `openslr/librispeech_asr` on Hugging Face) | Negative speech for training (dev-*, train shards 0000), validation (test-clean, train.other.500/0001) and the held-out false-accept test (test-other only); babble noise | **CC BY 4.0** (Panayotov, Chen, Povey, Khudanpur, ICASSP 2015) | |
| Freesound clips from `benjamin-paine/freesound-laion-640k-commercial-16khz-tiny`, shards 0 and 1, **filtered to clips whose own licence is CC0-1.0** (5,367 clips, 18.8 h) | Background noise and noise negatives | each clip **CC0-1.0**. Dataset card: CC BY 4.0 (compilation by benjamin-paine, from LAION's freesound-640k index) | CC-BY, CC-BY-NC and Sampling+ clips were dropped. Per-clip ids and usernames are in `work/noise/freesound_manifest.csv` |
| CMU Pronouncing Dictionary (`cmudict.dict`, raw file from github.com/cmusphinx/cmudict) | Phonemizing adversarial and random negative text | **BSD-2-Clause-style** (Carnegie Mellon University) | read by our own parser |
| Room impulse responses | Reverb augmentation | generated by us with **pyroomacoustics (MIT)** | no third-party recordings |

## Build-time software (not shipped)

| Package | Licence |
|---|---|
| Python 3.12 (Homebrew) | PSF-2.0 |
| tensorflow 2.20 (TFLite conversion, interpreter for validation) | Apache-2.0 |
| torch 2.x (training, bulk feature extraction on Apple MPS) | BSD-3-Clause (plus bundled permissive licences) |
| onnxruntime (running Piper voices) | MIT |
| numpy, scipy | BSD-3-Clause |
| soundfile | BSD-3-Clause (bundles libsndfile, LGPL-2.1, used as a build tool only) |
| pyarrow | Apache-2.0 |
| pyroomacoustics | MIT |
| piper-sample-generator (rhasspy) | MIT. Read for reference only. `gen_tts.py` re-implements its phoneme-id construction (`^ _ p _ … $`) and does not import it. |

## Deliberately NOT used

| Artifact | Reason |
|---|---|
| openWakeWord pre-trained wake-word models (alexa, hey_jarvis, …) | CC BY-NC-SA 4.0 |
| openWakeWord v0.5.1 `melspectrogram.*` / `embedding_model.*` release files | Licence unclear (see above). Used only as a numerical reference, not shipped |
| openWakeWord ACAV100M precomputed features and `validation_set_features.npy` | CC BY-NC-SA 4.0 |
| AudioSet, FMA (the standard openWakeWord noise sources) | YouTube-sourced or mixed per-track licences, including NC |
| MIT IR Survey (Traer & McDermott) RIRs | No licence stated on the source page. Downloaded, then deleted unused |
| `piper-sample-generator` LibriTTS-R generator (`en_US-libritts_r-medium.pt`) and all lessac-derived voices (libritts_r, arctic, kusal, alan, hfc_male, reza_ibrahim) | Fine-tuned from the lessac voice, whose Blizzard-2013 data licence is research-only and excludes commercial voice and speech products |
| NC or share-alike voices: ryan, hfc_female, semaine, l2arctic, Hindi priyamvada and pratham (CC BY-NC-SA), Hindi rohan (custom IndicTTS licence), northern/southern_english (CC BY-SA) | Non-commercial or share-alike terms |
| espeak-ng, piper-tts / piper1-gpl, piper-phonemize | GPL-3.0. Phonemes were hand-written or derived from CMUdict instead |
| `cmudict` and `pronouncing` Python packages | The `cmudict` wrapper is GPL-3.0. Uninstalled; the raw BSD dictionary file is read directly |
| Freesound clips under CC-BY, CC-BY-NC or Sampling+ | Kept only CC0, to avoid NC terms and per-clip attribution |
| ESC-50, UrbanSound8K, WHAM!, DEMAND, MUSAN, DiPCo | NC, share-alike, or too large |
