# Third-Party Notices & Model Attributions

EarTranslator bundles or uses several open-source components and pre-trained models.
**Some carry attribution requirements you MUST honor before publishing** (notably the
opus-mt models, which are CC-BY 4.0). Keep this file in the app (e.g. an "Open-source
licenses" screen) and in your repository.

> ⚠️ Verify the exact license of every specific model/voice you actually ship — licenses
> can vary per language pair and per Piper voice. The notes below are the common cases.

## Runtime libraries

| Component | License |
|-----------|---------|
| ONNX Runtime (`com.microsoft.onnxruntime:onnxruntime-android`) | MIT |
| AndroidX / Jetpack | Apache-2.0 |
| Material Components for Android | Apache-2.0 |
| Kotlin & kotlinx.coroutines | Apache-2.0 |
| `org.json` | JSON License / public-domain variants |

## Models

### Silero VAD (`silero_vad.onnx`)
- Author: Silero Team. License: **MIT**. No attribution screen strictly required, but
  retain the copyright notice.

### Whisper (OpenAI) — ASR
- Model weights and code released by OpenAI under the **MIT** license. Retain the MIT
  notice.

### Helsinki-NLP opus-mt — Machine Translation  ⚠️ ATTRIBUTION REQUIRED
- Released by the Language Technology Research Group at the University of Helsinki under
  **CC-BY 4.0**. CC-BY **requires attribution**. Include text such as:
  > "Translation models: Helsinki-NLP / Opus-MT (Tiedemann & Thottingal), licensed under
  > CC-BY 4.0."
- Cite: Jörg Tiedemann and Santhosh Thottingal, "OPUS-MT — Building open translation
  services for the World," EAMT 2020.
- You must keep this attribution visible in the app and not imply endorsement.

### eSpeak-ng (phonemizer for Piper)  ⚠️ GPLv3 — AFFECTS YOUR WHOLE APP
- Used (optionally) to convert text → IPA phonemes for Piper TTS, via the JNI bridge in
  `app/src/main/cpp/espeak_jni.cpp`.
- Licensed under the **GNU GPL v3**. This is "viral": distributing an app that links
  eSpeak-ng (dynamically or statically) generally requires the **entire app** to be
  released under a GPLv3-compatible license, with source offered to users.
- Implications for publishing:
  - If you are OK open-sourcing EarTranslator under GPLv3 → fine, include the eSpeak-ng
    copyright + GPLv3 text and a written offer for source.
  - If you want to stay closed-source → do **not** bundle eSpeak-ng. Use a permissively
    licensed grapheme-to-phoneme engine, or run eSpeak-ng as a separate process/app.
- The app is designed so eSpeak-ng is **optional** (the build and phonemizer degrade
  gracefully when it is absent), specifically so you can make this licensing choice
  deliberately.

### Piper TTS voices  ⚠️ CHECK PER VOICE
- Piper engine (rhasspy/piper): **MIT**.
- Individual **voices** are trained on various datasets with **varying licenses** (some
  CC-BY, some CC0, some dataset-specific). For each voice you ship, open its
  `MODEL_CARD` on the rhasspy/piper-voices repository and reproduce the required
  attribution/license here before release.

## How this is surfaced in-app

✅ Implemented. `MainActivity` has an **"Open-source licenses"** button that opens
`LicensesActivity`, which displays `res/raw/licenses.txt` (the in-app copy of these
notices, including the required Helsinki-NLP / opus-mt CC-BY attribution).

When you add or change a shipped model/voice, update **both** `res/raw/licenses.txt`
(shown to users) and this file (kept with the source).
