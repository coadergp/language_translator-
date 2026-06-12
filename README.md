# EarTranslator

A **real-time, fully offline** language translator that runs entirely on one Android
phone with **two Bluetooth earbuds**. Person A speaks into their earbud; Person B hears
the translation in theirs, and vice-versa — no internet, no cloud.

```
shared mic ─► VAD endpoints utterance ─► Whisper (detects language) ─┬─ spoke A ─► MT A→B ─► Piper ─► B's earbud
                                                                     └─ spoke B ─► MT B→A ─► Piper ─► A's earbud
```

All four model stages (VAD / ASR / MT / TTS) run locally via **ONNX Runtime for
Android**.

### Design: one phone, turn-based, language-routed

This app targets the **realistic single-phone setup**: two people, each wearing their own
**separate** Bluetooth earbud (two distinct BT devices — *not* one split TWS pair). Since
a phone exposes only **one** Bluetooth (SCO) microphone uplink at a time, the app does not
try to capture two mics. Instead:

1. one shared mic stream is segmented into utterances by **Silero VAD**,
2. **Whisper auto-detects** which of the two chosen languages was spoken,
3. the translation is routed to the **opposite** earbud,
4. capture is **muted during playback** (feedback guard) so the app never transcribes its
   own synthesized speech.

This is **half-duplex / turn-based**: people take turns naturally (no buttons), with
~1–2 s per-utterance latency. Talking over each other will drop a turn — the same
limitation a human interpreter has. See `TranslatorService.kt` and `WhisperASR.kt`
(`transcribeAuto` / `detectLanguage`).

---

## Tech stack

| Concern        | Choice                                                            |
|----------------|-------------------------------------------------------------------|
| Language       | Kotlin                                                            |
| Inference      | `com.microsoft.onnxruntime:onnxruntime-android:1.16.3`           |
| Concurrency    | Kotlin Coroutines                                                |
| UI binding     | ViewBinding                                                      |
| Min SDK        | API 26 (Android 8.0) · target/compile 34                         |
| VAD            | Silero VAD (`silero_vad.onnx`)                                   |
| ASR            | Whisper tiny (encoder + decoder ONNX)                            |
| MT             | Helsinki-NLP opus-mt (encoder + decoder ONNX + SentencePiece)   |
| TTS            | Piper VITS (ONNX, 22050 Hz mono)                                |

---

## Project layout

```
app/src/main/
  AndroidManifest.xml
  java/com/eartranslator/
    MainActivity.kt                 UI: language + earbud spinners, start/stop, status
    config/Languages.kt             the 11 supported languages
    bluetooth/BluetoothAudioManager.kt   BT profiles, SCO lifecycle, slot→device routing
    audio/AudioCaptureManager.kt    AudioRecord 16 kHz loop → SharedFlow<ShortArray>
    audio/AudioPlaybackManager.kt   two AudioTracks pinned to each earbud
    nlp/OnnxEnv.kt                  shared OrtEnvironment + asset loaders
    nlp/SileroVAD.kt                stateful VAD (persists h/c, reset on utterance end)
    nlp/WhisperASR.kt               log-mel → encoder → greedy decode → detokenize
    nlp/OpusMTTranslator.kt         swappable language-pair encoder/decoder + SPM
    nlp/PiperTTS.kt                 text → phoneme ids → VITS → PCM
    nlp/SentencePieceProcessor.kt   tokenizer wrapper (stub — see below)
    service/TranslatorService.kt    foreground service, single turn-based VAD loop
  res/...                           layout, drawables, values
  assets/models/                    ALL .onnx / .spm / .json live here (not committed)
```

---

## How the audio routing works (and why)

A Bluetooth headset exposes two profiles:

- **A2DP** — high-quality, **output only**. No microphone. Great for music, useless for
  a two-way conversation that needs the mic.
- **SCO (HFP)** — low-bandwidth, **bidirectional** voice link (the "phone call" path).
  This is the **only** way to read the earbud microphone.

So EarTranslator forces **SCO** up via `AudioManager.startBluetoothSco()` and sets
`MODE_IN_COMMUNICATION`. Capture uses `MediaRecorder.AudioSource.VOICE_COMMUNICATION`.

Output is routed per-ear with **`AudioTrack.setPreferredDevice(audioDeviceInfo)`**, where
the `AudioDeviceInfo` is resolved by matching the assigned `BluetoothDevice` address
against `AudioManager.getDevices(GET_DEVICES_OUTPUTS)`. The mic is similarly pinned with
`AudioRecord.setPreferredDevice(...)`.

> ⚠️ **Single SCO uplink:** most phones expose only **one** SCO microphone at a time, so
> two simultaneous earbud-mic captures usually aren't possible. The service captures one
> shared mic stream and routes each direction's **output** to the correct opposite ear
> (the part hardware reliably supports). The per-direction coroutine structure is kept so
> that, on stacks exposing dual SCO mics, you can give each capture its own
> `setPreferredDevice` and run them fully in parallel. See the note in
> `TranslatorService.kt`.

---

## ⚠️ Stubs you must replace before it actually translates

This repo is a **buildable scaffold**. The ONNX graphs are wired end-to-end with the
correct tensor shapes, but four pieces are deliberately stubbed (each logs a warning and
has a comment pointing at the real implementation):

| Stub | File | Replace with |
|------|------|--------------|
| ✅ `computeLogMelSpectrogram()` | `WhisperASR.kt` / `MelSpectrogram.kt` | **DONE** — real pure-Kotlin Whisper log-mel (n_fft=400, hop=160, Slaney mel) |
| ✅ `decodeTokens()` | `WhisperASR.kt` | **DONE** — real GPT-2 byte-level BPE decode via `vocab.json` |
| ✅ SentencePiece encode/decode | `SentencePieceProcessor.kt` | **DONE** — real Unigram tokenizer (parses `.spm` protobuf, Viterbi segmentation) + `vocab.json` id-mapping in `OpusMTTranslator` |
| 🔌 `textToPhonemeIds()` | `PiperTTS.kt` / `LexiconG2P.kt` / `EspeakPhonemizer.kt` | **Two real backends wired:** permissive `LexiconG2P` dictionary (no GPL, closed-source-friendly) preferred, else eSpeak-ng JNI (GPLv3), else char placeholder. You supply a `lexicon.txt` or the eSpeak `.so`. See "Permissive phonemizer" / "Phonemizer (eSpeak-ng)". |

The audio front-end, the **Whisper detokenizer**, and the **translation tokenizer** are now
real. So **VAD → log-mel → Whisper → BPE text** and **SentencePiece → opus-mt →
SentencePiece** run for real. The phonemizer is fully wired to **eSpeak-ng via JNI** —
once you drop in the native artifacts it produces real IPA; until then Piper uses a
placeholder.

Until these are replaced the app builds, requests permissions, brings up SCO, runs every
ONNX session, and plays audio — but the transcription/translation/speech content will be
placeholder-quality.

---

## Model preparation

All commands assume Python 3.10+, and produce **INT8-quantized** ONNX where it helps. Put
the outputs under `app/src/main/assets/models/` per the layout printed in
`app/src/main/assets/models/PLACE_MODELS_HERE.txt`.

```bash
python -m venv .venv && source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install --upgrade pip
pip install "optimum[exporters,onnxruntime]" onnx onnxruntime transformers sentencepiece
```

### 1) Silero VAD

Silero ships a ready ONNX file — no export needed.

```bash
mkdir -p app/src/main/assets/models
# v4 h/c variant (separate hidden/cell state tensors, as this app expects):
curl -L -o app/src/main/assets/models/silero_vad.onnx \
  https://github.com/snakers4/silero-vad/raw/v4.0/files/silero_vad.onnx
```

> If you grab a newer single-file export that uses a combined `state` tensor + `state`
> output instead of `h`/`c`, update `SileroVAD.kt` accordingly (one tensor instead of two).

### 2) Whisper tiny (ASR)

```bash
optimum-cli export onnx \
  --model openai/whisper-tiny \
  --task automatic-speech-recognition \
  app/src/main/assets/models/whisper/

# optimum emits encoder_model.onnx + decoder_model.onnx (+ tokenizer files).
# The app expects vocab.json alongside them:
python - <<'PY'
from transformers import WhisperTokenizer
import json, os
tok = WhisperTokenizer.from_pretrained("openai/whisper-tiny")
out = "app/src/main/assets/models/whisper/vocab.json"
json.dump(tok.get_vocab(), open(out, "w", encoding="utf-8"), ensure_ascii=False)
print("wrote", out)
PY
```

Quantize both Whisper graphs to INT8:

```bash
python - <<'PY'
from onnxruntime.quantization import quantize_dynamic, QuantType
base = "app/src/main/assets/models/whisper"
for name in ("encoder_model", "decoder_model"):
    quantize_dynamic(f"{base}/{name}.onnx", f"{base}/{name}.onnx",
                     weight_type=QuantType.QInt8)
    print("quantized", name)
PY
```

### 3) opus-mt (MT) — English-pivot, so only `en-X` and `X-en`

**Translation routes through English** (`X → en → Y`), so you do **not** export every
pairwise combination — only the `en-<code>` and `<code>-en` directions for each language.
That turns N×(N−1) models into 2×(N−1). The folder name is still `<src>-<tgt>`.

```bash
# Languages you want to ship (codes from config/Languages.kt). English is the hub.
LANGS=(es fr de it pt nl ru pl uk cs sk ro hu el sv da fi no tr ca vi zh ar)

for c in "${LANGS[@]}"; do
  for pair in "en-$c" "$c-en"; do
    optimum-cli export onnx --model "Helsinki-NLP/opus-mt-$pair" \
      --task text2text-generation \
      "app/src/main/assets/models/opus-mt/$pair/" || \
      echo "WARN: opus-mt-$pair not available — check the model hub for this language"
  done
done
```

> If `opus-mt-en-<code>` or `opus-mt-<code>-en` doesn't exist for a language, that language
> can't translate via this pivot — remove it from `Languages.kt` or find an alternative
> model. Some codes differ on the hub (e.g. grouped models); verify each.

opus-mt models carry SentencePiece vocabs. Copy them in as `source.spm` / `target.spm`
for every exported pair:

```bash
python - <<'PY'
from huggingface_hub import hf_hub_download
import shutil, os, glob
for d in glob.glob("app/src/main/assets/models/opus-mt/*"):
    pair = os.path.basename(d)
    repo = f"Helsinki-NLP/opus-mt-{pair}"
    try:
        for name in ("source.spm", "target.spm"):
            shutil.copy(hf_hub_download(repo, name), os.path.join(d, name))
        print("spm copied for", pair)
    except Exception as e:
        print("skip", pair, e)
PY
```

Quantize each pair's encoder/decoder:

```bash
python - <<'PY'
from onnxruntime.quantization import quantize_dynamic, QuantType
import glob, os
for enc in glob.glob("app/src/main/assets/models/opus-mt/*/encoder_model.onnx"):
    d = os.path.dirname(enc)
    for name in ("encoder_model", "decoder_model"):
        p = f"{d}/{name}.onnx"
        quantize_dynamic(p, p, weight_type=QuantType.QInt8)
    print("quantized", d)
PY
```

> **Keep `vocab.json`.** The `optimum-cli` export writes the tokenizer files (including
> `vocab.json`) into each pair's output folder automatically. The app's tokenizer
> ([`SentencePieceProcessor`](app/src/main/java/com/eartranslator/nlp/SentencePieceProcessor.kt)
> + `OpusMTTranslator`) needs `source.spm`, `target.spm`, **and** `vocab.json` in every
> `opus-mt/<pair>/` folder — don't delete `vocab.json`.

### 4) Piper TTS — one voice per language

Piper publishes pre-trained ONNX voices (each is `<voice>.onnx` + `<voice>.onnx.json`).
Download the voices matching `Language.piperVoice` in `config/Languages.kt`:

```bash
# Example: English (lessac) and Spanish (mls_10246)
BASE=https://huggingface.co/rhasspy/piper-voices/resolve/main

mkdir -p app/src/main/assets/models/piper/en app/src/main/assets/models/piper/es

curl -L -o app/src/main/assets/models/piper/en/en_US-lessac-medium.onnx \
  "$BASE/en/en_US/lessac/medium/en_US-lessac-medium.onnx"
curl -L -o app/src/main/assets/models/piper/en/en_US-lessac-medium.onnx.json \
  "$BASE/en/en_US/lessac/medium/en_US-lessac-medium.onnx.json"

curl -L -o app/src/main/assets/models/piper/es/es_ES-mls_10246-medium.onnx \
  "$BASE/es/es_ES/mls_10246/medium/es_ES-mls_10246-medium.onnx"
curl -L -o app/src/main/assets/models/piper/es/es_ES-mls_10246-medium.onnx.json \
  "$BASE/es/es_ES/mls_10246/medium/es_ES-mls_10246-medium.onnx.json"
```

Piper voices are already small VITS models; quantizing is optional and can hurt quality,
so it is left out by default.

> The `.onnx.json` config carries `phoneme_id_map` and the inference `scales`
> (`noise_scale`, `length_scale`, `noise_w`) — `PiperTTS.kt` reads both at load time.

---

## Build & run

```bash
# from the project root
./gradlew :app:assembleDebug
# install on a connected device
./gradlew :app:installDebug
# run the JVM unit tests (tokenizers, mel spectrogram, lexicon)
./gradlew :app:testDebugUnitTest
```

## Model delivery (keeping the APK small)

Models are resolved **internal storage first, then bundled assets** (see `OnnxEnv`), so you
can ship a tiny APK and deliver models after install. [`ModelManifest`](app/src/main/java/com/eartranslator/nlp/ModelManifest.kt)
computes the exact files needed for a language pair; `MainActivity` checks them before
starting and reports what's missing.

Two delivery options:
- **Play Asset Delivery (recommended)** — Google serves large model packs with **no
  INTERNET permission**, preserving the offline-privacy story. Copy delivered files to
  `filesDir/models/...`.
- **First-run HTTP download** — set `MainActivity.MODEL_BASE_URL` to your CDN and add the
  `INTERNET` permission. [`ModelDownloader`](app/src/main/java/com/eartranslator/nlp/ModelDownloader.kt)
  fetches missing files with progress. ⚠️ Adding INTERNET changes your Data Safety answers
  and the "no internet" claim — update `PRIVACY_POLICY.md` accordingly (only model files
  are fetched; user audio still never leaves the device).

Then on the phone:

1. Pair and connect **two** Bluetooth earbuds.
2. Open EarTranslator, grant mic + Bluetooth permissions.
3. Tap **Refresh Bluetooth devices**, assign one earbud to **Person A**, the other to
   **Person B**, and pick each person's language.
4. Tap **Start**. A foreground-service notification appears; the SCO link comes up and
   translation begins.

---

## Permissions

Declared in `AndroidManifest.xml` and requested at runtime in `MainActivity`:

`RECORD_AUDIO`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, `BLUETOOTH` (maxSdk 30),
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `MODIFY_AUDIO_SETTINGS`.

---

## Permissive phonemizer (no GPL) — keep the app closed-source

If you want to publish a **closed-source** app, avoid bundling eSpeak-ng (GPLv3). Instead
use the built-in **dictionary phonemizer** [`LexiconG2P`](app/src/main/java/com/eartranslator/nlp/LexiconG2P.kt)
— pure Kotlin, no native code, no GPL. Drop a `lexicon.txt` per language under
`assets/models/g2p/<lang>/`:

```
hello␉həˈloʊ
world␉wɜːld
```

`PiperTTS` prefers the lexicon when present (falls back to eSpeak only if no lexicon, then
to a char placeholder).

**The catch:** the IPA in the lexicon must use the **same phoneme inventory the Piper
voice was trained on** (the keys of `<voice>.onnx.json` → `phoneme_id_map`), which for
standard voices is eSpeak's IPA set. Practical ways to get a compatible, permissively-
licensed lexicon:

- **Pre-generate it on your dev machine** for your app's vocabulary. The phoneme *data*
  ships with your app; the GPL tool does not run in (or ship with) the app. Example:
  ```bash
  # build a lexicon for a word list using eSpeak on your machine (not in the app)
  while read w; do printf "%s\t%s\n" "$w" "$(espeak-ng -q --ipa -v en-us "$w" | tr -d '\n' | sed 's/^ //')"; done \
    < wordlist_en.txt > app/src/main/assets/models/g2p/en/lexicon.txt
  ```
  > Whether eSpeak-*generated phoneme data* carries GPL obligations is a licensing
  > question (data vs. derivative work) — confirm with counsel for your situation.
- **DeepPhonemizer** (MIT) — train/run a neural G2P to emit IPA and dump a lexicon, or
  export it to ONNX and add an ONNX `Phonemizer` implementation (the
  [`Phonemizer`](app/src/main/java/com/eartranslator/nlp/Phonemizer.kt) interface is the
  extension point). https://github.com/as-ideas/DeepPhonemizer
- **Open IPA dictionaries** (e.g. Wiktionary-derived sets) — check each language's source
  license, and remap symbols to the voice's inventory.

This route trades coverage (OOV words are skipped) for a clean permissive license. For a
phrasebook/traveler vocabulary it can be very usable; for open-ended speech, eSpeak-ng has
far better coverage (at the GPL cost).

## Phonemizer (eSpeak-ng) — optional native integration

Piper needs **IPA phonemes**, produced by eSpeak-ng. The integration is fully wired
(`PiperTTS` → `EspeakPhonemizer` → JNI bridge in `app/src/main/cpp/espeak_jni.cpp`); you
just supply the native artifacts. The NDK build **auto-activates** once they're present —
until then the app builds fine and falls back to a placeholder phonemizer.

Drop in three things:

```
app/src/main/jniLibs/arm64-v8a/libespeak-ng.so        # prebuilt, per ABI
app/src/main/jniLibs/armeabi-v7a/libespeak-ng.so      # optional (32-bit)
app/src/main/cpp/include/espeak-ng/speak_lib.h        # eSpeak-ng headers
app/src/main/assets/espeak-ng-data/...                # phoneme tables / voices / dicts
```

Build `libespeak-ng.so` for Android with the NDK:

```bash
git clone https://github.com/espeak-ng/espeak-ng
cd espeak-ng
cmake -B build-android \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DBUILD_SHARED_LIBS=ON -DESPEAK_BLD_ESPEAK=OFF
cmake --build build-android
# copy build-android/src/libespeak-ng/libespeak-ng.so → app/src/main/jniLibs/arm64-v8a/
# copy src/include/espeak-ng/*.h                       → app/src/main/cpp/include/espeak-ng/
# copy the generated espeak-ng-data/                   → app/src/main/assets/espeak-ng-data/
```

The eSpeak voice per Piper voice is read automatically from each `<voice>.onnx.json`
(`espeak.voice`, e.g. `en-us`, `fr`, `de`).

> ⚠️ **License:** eSpeak-ng is **GPLv3**. Statically or dynamically bundling it makes the
> distributed app subject to GPLv3 (you must offer your app's source under a compatible
> license). If you want to keep EarTranslator closed-source, use a permissively-licensed
> grapheme-to-phoneme alternative instead, or run eSpeak-ng out-of-process. See
> `THIRD_PARTY_NOTICES.md`.

## Publishing to Google Play (safety & compliance)

This project is set up to be **store-ready and policy-compliant**:

- **No internet:** the app declares no `INTERNET` permission — audio/text physically
  cannot leave the device.
- **Prominent disclosure:** a consent dialog explains microphone use *before* the system
  prompt (Play User Data policy).
- **Permissions:** `BLUETOOTH_SCAN` is flagged `neverForLocation`; microphone is justified
  as the core feature with a `microphone` foreground-service type.
- **Backup rules:** on-device model caches are excluded from cloud backup / device
  transfer (no user data exists to back up).
- **Release build:** `targetSdk 35`, R8 shrinking + resource shrinking, signing via a
  gitignored `keystore.properties` (template provided).

Read these before you submit:

| Doc | Purpose |
|-----|---------|
| [`PLAY_STORE_CHECKLIST.md`](PLAY_STORE_CHECKLIST.md) | Step-by-step submission checklist (AAB, Data Safety, app size, etc.) |
| [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md) | Privacy policy text to host at a public URL (required for mic apps) |
| [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) | License attributions — **opus-mt is CC-BY 4.0 and requires attribution** |

> ⚠️ **App size:** bundled ONNX models will likely exceed Play's 150 MB base limit. Use
> **Play Asset Delivery** or download models on first run. Don't pack many language pairs
> into the base bundle.

## Tuning knobs

- **`TranslatorService.SILENCE_FRAMES_THRESHOLD`** (default 15 ≈ 480 ms) — how long a
  pause must be before an utterance is flushed to ASR. Lower = snappier but more
  fragmentation.
- **`SileroVAD.SPEECH_THRESHOLD`** (default 0.5) — speech-probability cutoff.
- Piper `scales` in the voice `.onnx.json` control speed/expressiveness.
```
