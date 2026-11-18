package com.openjarvis.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

/**
 * Turkish-only Android TTS adapter.
 * All TextToSpeech lifecycle and playback calls are serialized on the main thread.
 */
class AndroidTTSEngine(context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val appContext = context.applicationContext
    private val mutableStatus = MutableStateFlow<TurkishTtsStatus>(TurkishTtsStatus.Initializing)
    val status: StateFlow<TurkishTtsStatus> = mutableStatus.asStateFlow()

    // Accessed only on the main thread.
    private var tts: TextToSpeech? = null
    private var released = false
    private val pendingCallbacks = linkedMapOf<String, () -> Unit>()

    @Volatile private var speaking = false
    @Volatile private var speechRate = TurkishTtsPolicy.DEFAULT_SPEECH_RATE
    @Volatile private var pitch = TurkishTtsPolicy.DEFAULT_PITCH

    init {
        onMain { initialize() }
    }

    private fun initialize() {
        if (released) return
        try {
            tts = TextToSpeech(appContext) { result ->
                // Always enqueue: the constructor must assign `tts` before its init callback runs.
                mainHandler.post {
                    if (released) {
                        tts?.shutdown()
                        tts = null
                    } else if (result != TextToSpeech.SUCCESS) {
                        mutableStatus.value = TurkishTtsStatus.InitializationFailed
                    } else {
                        configureTurkishVoice()
                    }
                }
            }
        } catch (_: Throwable) {
            mutableStatus.value = TurkishTtsStatus.InitializationFailed
        }
    }

    private fun configureTurkishVoice() {
        val engine = tts
        if (engine == null) {
            mutableStatus.value = TurkishTtsStatus.InitializationFailed
            return
        }

        val locale = Locale.forLanguageTag("tr-TR")
        val languageAvailability = runCatching { engine.isLanguageAvailable(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        val languageSelection = runCatching { engine.setLanguage(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (!TurkishTtsPolicy.isLanguageAvailable(languageAvailability) ||
            !TurkishTtsPolicy.isLanguageAvailable(languageSelection)) {
            mutableStatus.value = TurkishTtsStatus.TurkishVoiceMissing
            return
        }

        val installedVoices = runCatching { engine.voices.orEmpty() }.getOrDefault(emptySet())
        val candidates = installedVoices.map { voice ->
            TurkishTtsVoiceCandidate(
                name = voice.name,
                localeTag = voice.locale.toLanguageTag(),
                quality = voice.quality,
                latency = voice.latency,
                requiresNetwork = TurkishTtsPolicy.requiresNetwork(voice.features)
            )
        }
        val selected = TurkishTtsPolicy.selectVoice(candidates)
        val selectedVoice = selected?.let { candidate -> installedVoices.firstOrNull { it.name == candidate.name } }
        if (selected == null || selectedVoice == null ||
            runCatching { engine.setVoice(selectedVoice) }.getOrDefault(TextToSpeech.ERROR) != TextToSpeech.SUCCESS) {
            mutableStatus.value = TurkishTtsStatus.TurkishVoiceMissing
            return
        }

        val actualLocale = runCatching { engine.voice?.locale ?: selectedVoice.locale }.getOrDefault(selectedVoice.locale)
        if (!TurkishTtsPolicy.isTurkishLocaleTag(actualLocale.toLanguageTag())) {
            mutableStatus.value = TurkishTtsStatus.TurkishVoiceMissing
            return
        }

        runCatching { engine.setSpeechRate(speechRate) }
        runCatching { engine.setPitch(pitch) }
        val listenerInstalled = runCatching {
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    speaking = true
                }

                override fun onDone(utteranceId: String?) {
                    speaking = false
                    utteranceId?.let { completeCallback(it) }
                }

                override fun onError(utteranceId: String?) {
                    speaking = false
                    utteranceId?.let { completeCallback(it) }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    speaking = false
                    utteranceId?.let { completeCallback(it) }
                }
            })
        }.isSuccess
        if (!listenerInstalled) {
            mutableStatus.value = TurkishTtsStatus.InitializationFailed
            return
        }
        mutableStatus.value = TurkishTtsStatus.Ready(
            voiceLocale = actualLocale.toLanguageTag(),
            offline = !selected.requiresNetwork
        )
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        onMain {
            val engine = tts
            if (released || mutableStatus.value !is TurkishTtsStatus.Ready || engine == null) {
                onDone?.invoke()
                return@onMain
            }

            val clean = cleanForSpeech(text)
            if (clean.isBlank()) {
                onDone?.invoke()
                return@onMain
            }

            // QUEUE_FLUSH cancels older utterances. Drop their callbacks first so an
            // old AUTO_VAD handoff cannot restart STT while the new utterance is speaking.
            pendingCallbacks.clear()
            engine.stop()
            speaking = false

            val utteranceId = "jarvis_${UUID.randomUUID()}"
            if (onDone != null) pendingCallbacks[utteranceId] = onDone
            val result = runCatching {
                engine.speak(clean, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            }.getOrDefault(TextToSpeech.ERROR)
            if (result == TextToSpeech.ERROR) {
                completeCallback(utteranceId)
            }
        }
    }

    fun speakQueued(text: String) {
        onMain {
            val engine = tts
            if (released || mutableStatus.value !is TurkishTtsStatus.Ready || engine == null) return@onMain
            val clean = cleanForSpeech(text)
            if (clean.isNotBlank()) {
                runCatching { engine.speak(clean, TextToSpeech.QUEUE_ADD, null, "jarvis_${UUID.randomUUID()}") }
            }
        }
    }

    fun stop() {
        onMain {
            pendingCallbacks.clear()
            speaking = false
            runCatching { tts?.stop() }
        }
    }

    fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(0.5f, 2.0f)
        onMain { runCatching { tts?.setSpeechRate(speechRate) } }
    }

    fun setPitch(pitchValue: Float) {
        pitch = pitchValue.coerceIn(0.5f, 2.0f)
        onMain { runCatching { tts?.setPitch(pitch) } }
    }

    fun getSpeechRate(): Float = speechRate
    fun getPitch(): Float = pitch
    fun isSpeaking(): Boolean = speaking

    fun release() {
        onMain {
            if (released) return@onMain
            released = true
            pendingCallbacks.clear()
            speaking = false
            runCatching { tts?.stop() }
            runCatching { tts?.shutdown() }
            tts = null
            mutableStatus.value = TurkishTtsStatus.Released
        }
    }

    private fun completeCallback(utteranceId: String) {
        onMain {
            pendingCallbacks.remove(utteranceId)?.let { callback ->
                runCatching { callback() }
            }
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun cleanForSpeech(text: String): String = text
        .replace(Regex("\\[\\{.*?\\}\\]"), "")
        .replace(Regex("[\\[\\]{}\\\":]"), " ")
        .trim()
}
