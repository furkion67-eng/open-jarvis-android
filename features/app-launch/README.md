# Allowlisted Android app launch (Issue #16)

This feature routes typed and recognized Turkish speech through the existing TurkishCommandRouter and DeviceActionCoordinator, then dispatches only a registered app alias to an explicit package allowlist.

## Supported aliases

- WhatsApp: whatsapp, whats app, vatsap
- YouTube: youtube, you tube, yutub
- Chrome: chrome, google chrome
- Gmail: gmail, google mail, e posta
- Haritalar: haritalar, google maps, maps
- Ayarlar: ayarlar, settings
- Telegram, Instagram, Spotify, TikTok, Play Store and Google Fotoğraflar.

The dispatcher does not accept a raw package name from a user or model. SMS, calls, WhatsApp message composition, account changes and public publishing are not part of this capability.

## Result handling

The Android adapter checks package installation, getLaunchIntentForPackage, resolveActivity, and current app visibility before startActivity. It reports separate Turkish outcomes for not installed, no launcher activity, unresolvable intent, background-activity restriction and generic launch failure. A successful call is worded as an Android launch request; it does not claim that a foreground window was independently verified.

A visible Activity or an approved overlay permission is required to request a background launch. A foreground service by itself is not treated as sufficient. App launch does not require Accessibility permission when the JARVIS Activity is foregrounded.

## Tests

- JVM: alias allowlist, raw-package rejection, typed/speech router parity, and every result category.
- Instrumentation: PackageManager resolves the app's launcher intent and starts the known MainActivity using an injected test-only registry entry.

## Physical TECNO Spark 20 Pro / HiOS checklist (not yet run)

1. Install the exact PR APK and verify WhatsApp, YouTube and Ayarlar are present.
2. In VoiceCommandActivity, type "WhatsApp'ı aç"; confirm the WhatsApp launch request is shown and WhatsApp appears.
3. Return to JARVIS, speak "WhatsApp'ı aç"; confirm the same dispatcher and Turkish result are used.
4. Repeat with "YouTube'u başlat" and "Ayarlar'ı aç".
5. Try an unsupported app name and a raw package string; both must be rejected without launching anything.
6. With JARVIS in the background and overlay permission disabled, issue an app-launch request from a background service/overlay path; confirm the Turkish background-restriction message appears and no false success is reported.
7. Bring JARVIS to the foreground and repeat; confirm the launch request is accepted.
8. If a listed app is absent, confirm the result says it is not installed; do not infer device compatibility from emulator tests.
