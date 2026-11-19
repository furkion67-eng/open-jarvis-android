# HiOS speech-recognition session recovery

The user-provided TECNO Spark 20 Pro screen recording shows that Turkish speech recognition succeeds once, JARVIS speaks its reply, and the next automatic listening session fails with a client/start error.

This overlay updates the packaged Android source during the existing `scripts/repair_sources.py` build:
- Retain the Compose RecognitionListener so a replacement SpeechRecognizer remains connected to the same UI state.
- Cancel and destroy the previous recognizer before starting a new listening session after TTS or a previous recognition callback.
- Recreate SpeechRecognizer on the Activity main thread and reattach the listener before `startListening`.
- Add Android 11+ launcher-intent package visibility so registered app-launch commands can resolve installed apps.
- Add the Android 14+ dataSync foreground-service permission required by the existing DownloadService declaration.

The patch does not change API keys, prompts, user messages, or public posting behavior. Emulator CI is not a substitute for a physical TECNO/HiOS retest.