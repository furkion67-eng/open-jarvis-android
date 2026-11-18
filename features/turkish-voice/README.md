# Turkish-first speech recognition policy

This overlay makes every Android SpeechRecognizer entry point request Turkish (tr-TR) through one shared intent factory. It also centralizes Turkish error messages and rejects blank transcripts.

The overlay is copied into the extracted Android project by scripts/repair_sources.py. It changes STT only; AndroidTTSEngine and its existing Turkish TTS voice/rate selection are intentionally untouched.

Coverage:
- JVM tests for transcript normalization and localized error messages.
- Android instrumentation tests for the exact RecognizerIntent language extras.
- Runtime preflight for RECORD_AUDIO permission and recognition-service availability.

This does not provide offline speech recognition or guarantee that a device's installed recognition provider has a Turkish model. Those remain device/provider capabilities.
