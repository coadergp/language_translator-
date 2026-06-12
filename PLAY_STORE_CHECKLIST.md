# Google Play Publishing Checklist — EarTranslator

A step-by-step guide to get this app onto the Play Store **compliantly**. Items marked
✅ are already handled in this codebase; ⬜ are actions you must complete in Play Console
or in your own accounts.

---

## 1. Build & signing

- ✅ `targetSdk` / `compileSdk` = **35** (meets Play's API-level requirement for new apps
  and updates).
- ✅ R8 shrinking + resource shrinking enabled for `release`; ONNX Runtime classes kept
  (`proguard-rules.pro`).
- ✅ Release signing wired to `keystore.properties` (gitignored); template provided.
- ⬜ Generate an **upload key** and enroll in **App Signing by Google Play**:
  ```bash
  keytool -genkeypair -v -keystore upload-keystore.jks -keyalg RSA -keysize 2048 \
      -validity 10000 -alias eartranslator
  ```
- ⬜ Build the **Android App Bundle** (Play requires `.aab`, not `.apk`):
  ```bash
  ./gradlew :app:bundleRelease
  # output: app/build/outputs/bundle/release/app-release.aab
  ```
- ⚠️ **Size:** bundled ONNX models can push the app well past Play's **150 MB base**
  limit. Options: ship models via **Play Asset Delivery** (install-time/on-demand packs),
  or download them on first run from your own CDN. Do **not** try to ship many language
  pairs in the base AAB.

## 2. Permissions & policy

- ✅ `RECORD_AUDIO` — core feature (live translation); justified.
- ✅ `BLUETOOTH_SCAN` declared `neverForLocation` → avoids Location policy.
- ✅ `FOREGROUND_SERVICE_MICROPHONE` with `foregroundServiceType="microphone"`.
- ✅ **No `INTERNET` permission** — strongest possible privacy posture.
- ✅ **Prominent disclosure** dialog shown before the system mic prompt (in `MainActivity`).
- ⬜ In Play Console → **App content → Foreground service permissions**, declare *why* you
  use a microphone foreground service (live translation while screen is off) and upload a
  short demo video if requested.
- ⬜ Complete the **Sensitive app permissions** declarations as prompted.

## 3. Data safety form (App content → Data safety)

Because the app is fully offline, the answers are simple:

- Data collected: **None**.
- Data shared: **None**.
- Is all data encrypted in transit: **N/A (no data leaves the device)**.
- Can users request deletion: **N/A (no data stored)**.
- ⬜ Fill the form to match the wording in `PRIVACY_POLICY.md`.

## 4. Privacy policy

- ✅ `PRIVACY_POLICY.md` provided.
- ⬜ Host it at a public URL (GitHub Pages, your site, etc.) and paste the URL into
  **Store listing → Privacy Policy**. (A privacy policy URL is mandatory whenever the app
  requests `RECORD_AUDIO`.)

## 5. Licenses & attribution

- ✅ `THIRD_PARTY_NOTICES.md` lists library + model licenses.
- ✅ **opus-mt CC-BY 4.0 attribution** is surfaced in-app via the "Open-source licenses"
  screen (`LicensesActivity` → `res/raw/licenses.txt`), with the Helsinki-NLP citation.
- ⬜ For each Piper voice you ship, copy its specific license/attribution from its
  `MODEL_CARD` into **both** `res/raw/licenses.txt` and `THIRD_PARTY_NOTICES.md`.
- ⚠️ **eSpeak-ng is GPLv3.** If you bundle it (drop the `.so` in `jniLibs`), the whole app
  becomes subject to GPLv3 — you must publish your source under a compatible license. To
  keep EarTranslator closed-source, do **not** bundle eSpeak-ng; use a permissive G2P
  engine instead. The app builds and runs without it (placeholder phonemizer).

## 6. Store listing assets

- ⬜ App icon (✅ adaptive icon in repo; ⬜ 512×512 hi-res icon for the listing).
- ⬜ Feature graphic 1024×500, phone screenshots (≥2).
- ⬜ Short + full description. Be accurate: say it's offline and turn-based; don't claim
  simultaneous interpretation.
- ⬜ Content rating questionnaire (utility app; no sensitive content).
- ⬜ Target audience & "Designed for Families"? Choose adults/general — **not** child-
  directed (microphone apps shouldn't target children).

## 7. Pre-launch quality

- ⬜ Test on a physical device with two **separate** Bluetooth headsets (not one split
  TWS pair — see README).
- ⬜ Run **Play Console → Pre-launch report** (catches crashes/policy issues on real
  devices).
- ⬜ Verify the four NLP stubs in the README are replaced with real implementations before
  a production release, or the app won't actually translate.
- ⬜ Confirm graceful behavior when permissions are denied and when no BT devices are
  connected (handled in `MainActivity`).

## 8. Honesty / misrepresentation policy

- ⬜ Don't overstate accuracy or claim "real-time simultaneous" translation. Describe the
  ~1–2s, turn-based behavior plainly. Misleading claims violate the **Misrepresentation**
  policy.

---

When all ⬜ items are done, you can promote the bundle from Internal testing → Closed →
Open → Production.
