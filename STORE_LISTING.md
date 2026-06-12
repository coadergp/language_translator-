# Play Store Listing & Console Answers — EarTranslator

Copy/paste-ready text for the Play Console. Edit the bracketed placeholders. Keep claims
accurate (offline, turn-based) to stay clear of the Misrepresentation policy.

---

## App name (max 30 chars)
```
EarTranslator
```
Alternatives: `EarTranslator: Offline Talk`, `EarTranslator — Travel Talk`

## Short description (max 80 chars)
```
Offline two-ear voice translator. Talk naturally, hear each other's language.
```

## Full description (max 4000 chars)
```
EarTranslator turns your phone and two Bluetooth earbuds into a private, offline
interpreter. Each person wears an earbud, speaks their own language, and hears the other
person translated into their ear — no internet, no accounts, no data leaving your phone.

Perfect for:
• Travel — talk with taxi drivers, hotel staff, and shopkeepers abroad
• Meeting international clients or visitors
• Everyday conversations across a language barrier

HOW IT WORKS
Connect two separate Bluetooth earbuds, pick each person's language, and press Start.
EarTranslator listens for a finished phrase, recognizes the language automatically, and
plays the translation into the other person's ear within a second or two. Take turns
speaking naturally — no buttons to hold.

FULLY OFFLINE & PRIVATE
• All speech recognition, translation, and voice synthesis run on your device.
• Your audio is never recorded, stored, or sent anywhere.
• The app has no internet permission at all.
Great for travelers with no roaming data, and for anyone who values privacy.

LANGUAGES
Supports many languages including English, Spanish, French, German, Italian, Portuguese,
Dutch, Russian, Polish, Ukrainian, Czech, Slovak, Romanian, Hungarian, Greek, Swedish,
Danish, Finnish, Norwegian, Turkish, Catalan, Vietnamese, Chinese, and Arabic.

GOOD TO KNOW
• Works best when each person has their OWN earbud (two separate Bluetooth devices).
• It's turn-based: take turns speaking rather than talking over each other.
• Translation is automatic and on-device, so quality and speed depend on your phone.

No ads. No tracking. No sign-up.
```

> ⚠️ Tailor the language list to what you actually ship. Don't list a language unless its
> models are installed/available.

## What's new (release notes, max 500 chars)
```
First release: offline two-ear voice translation with automatic language detection and
on-device speech recognition, translation, and text-to-speech.
```

---

## Categorization
- Category: **Tools** (or **Travel & Local**)
- Tags: translator, offline, travel, bluetooth

## Contact details
- Email: [your support email]
- Website: [optional]
- Privacy Policy URL: [public URL hosting PRIVACY_POLICY.md] — **required**

---

## Data Safety form answers

**Does your app collect or share any of the required user data types?** → **No**

Because all processing is on-device and the app has no INTERNET permission, every section
is "not collected":
- Location: No
- Personal info: No
- Financial info: No
- Audio (voice or sound recordings): **Collected? No.** (Audio is processed in memory for
  live translation and never stored or transmitted.)
- App activity / Device IDs / etc.: No

- Is all collected data encrypted in transit? → **N/A** (no data leaves the device)
- Do you provide a way to request data deletion? → **N/A** (no data stored)

> If you later enable the HTTP model downloader (adds INTERNET), revisit this form: you'd
> disclose that model files are downloaded, but still "no user/personal data collected."
> Prefer Play Asset Delivery to keep these answers as-is.

---

## Foreground service permission declaration (App content → Foreground service permissions)

Permission/type: **microphone** (`FOREGROUND_SERVICE_MICROPHONE`)

Justification text:
```
EarTranslator is a real-time voice translator. While a conversation is active, the app
must continuously capture microphone audio from the user's Bluetooth earbud and play
translated speech, including when the screen is off or the user switches apps. A
microphone foreground service is required to keep this live audio pipeline running
reliably for the duration of the conversation. Audio is processed entirely on-device and
is never recorded or transmitted.
```

Short in-app usage summary (if asked): "Used to capture and translate speech in real time
during an active conversation; stops when the user presses Stop."

---

## Demo video script (in case Google requests one for the FGS/mic review)

1. (0:00) Show the app's main screen; point out the microphone disclosure dialog text.
2. (0:08) Show two Bluetooth earbuds connected; assign one to Person A, one to Person B.
3. (0:15) Select two languages; press Start; show the foreground-service notification.
4. (0:22) Person A speaks a phrase; ~1–2s later the translation plays in Person B's ear
   (show subtitles/captions on screen if added). Then reverse.
5. (0:35) Press Stop; the foreground service notification disappears.
6. (0:40) On-screen text: "All processing on-device. No internet permission. No data
   leaves the phone."

---

## Content rating questionnaire (IARC) — guidance
- App is a utility; no violence, sexual content, profanity, gambling, or user-to-user
  communication that you host. Answer "No" to those categories.
- It does NOT share location or personal info.
- Expected result: **Everyone / PEGI 3**, but answer the live questionnaire truthfully.

## Target audience & content
- Target age group: **adults / 18+** (or 13+). Do **not** mark as child-directed — mic
  apps should not target children, and "Designed for Families" is not appropriate.

## Ads
- Contains ads? → **No**.
