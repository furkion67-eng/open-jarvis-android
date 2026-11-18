# Turkish-first Android TTS (Issue #28)

This overlay replaces the packaged `AndroidTTSEngine` with a Turkish-only implementation and exposes its readiness through `VoiceManager.ttsStatus`.

## Behaviour

- Waits for Android `TextToSpeech` initialization before accepting speech.
- Checks both `isLanguageAvailable(Locale.forLanguageTag("tr-TR"))` and `setLanguage` results.
- Selects only installed voices whose locale language is Turkish. Exact `tr-TR` wins, then offline synthesis, quality, latency and a stable name tie-breaker.
- Never falls back silently to English. Missing Turkish voice data and initialization errors are shown in Turkish, with a user-triggered `ACTION_INSTALL_TTS_DATA` route.
- Keeps the existing 0.96 speech-rate and 1.0 pitch defaults; values remain clamped to Android's supported 0.5–2.0 range.
- Serializes TextToSpeech operations and callbacks on the main thread.
- Resumes AUTO_VAD only after utterance completion/error or an explicit stop; a missing voice completes the callback immediately so the voice screen does not stall. Lifecycle release discards pending callbacks so a destroyed Activity cannot restart its microphone.
- Clears stale callbacks before `QUEUE_FLUSH`, preventing an older reply from restarting STT during a newer reply. `stop()` and `release()` drain the current handoff exactly once so AUTO_VAD callers cannot remain stuck when playback is interrupted.
- Shuts down the engine when the voice activity is destroyed.

## Validation

- JVM tests cover locale filtering, deterministic selection, availability and Turkish fallback text.
- Instrumentation test accepts either a verified Turkish voice or the honest missing-data state; if speech is available, it verifies the utterance completion/error handoff.
- A physical TECNO Spark 20 Pro / HiOS listening test is still required to accept naturalness and OEM-specific behaviour. Emulator voice quality is not device evidence.
- Releases the per-activity TTS engine from the voice and settings screens, and releases one-shot ScreenWatcher engines after their utterance callback.
- Instrumentation also checks repeated replies, stop/release during an active utterance (the active-voice cases are skipped on emulator images without a Turkish voice), and that a released engine cannot strand a new caller handoff.
