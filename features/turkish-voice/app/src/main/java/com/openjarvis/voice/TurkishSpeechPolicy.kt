package com.openjarvis.voice

import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

enum class RecognitionFailure {
    AUDIO,
    CLIENT,
    PERMISSION,
    NETWORK,
    NETWORK_TIMEOUT,
    NO_MATCH,
    BUSY,
    SERVER,
    SPEECH_TIMEOUT,
    UNKNOWN
}

/**
 * Shared language and error policy for every Android SpeechRecognizer entry point.
 * Keep the requested language explicit: device-wide Locale defaults vary by user settings.
 */
object TurkishSpeechPolicy {
    const val LANGUAGE_TAG = "tr-TR"

    fun createRecognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE_TAG)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, LANGUAGE_TAG)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

    fun failureForAndroidError(error: Int): RecognitionFailure = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> RecognitionFailure.AUDIO
        SpeechRecognizer.ERROR_CLIENT -> RecognitionFailure.CLIENT
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> RecognitionFailure.PERMISSION
        SpeechRecognizer.ERROR_NETWORK -> RecognitionFailure.NETWORK
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> RecognitionFailure.NETWORK_TIMEOUT
        SpeechRecognizer.ERROR_NO_MATCH -> RecognitionFailure.NO_MATCH
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> RecognitionFailure.BUSY
        SpeechRecognizer.ERROR_SERVER -> RecognitionFailure.SERVER
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> RecognitionFailure.SPEECH_TIMEOUT
        else -> RecognitionFailure.UNKNOWN
    }

    fun messageFor(failure: RecognitionFailure): String = when (failure) {
        RecognitionFailure.AUDIO -> "Mikrofon ses akışında sorun oluştu. Tekrar dene."
        RecognitionFailure.CLIENT -> "Ses tanıma başlatılamadı. Tekrar dene."
        RecognitionFailure.PERMISSION -> "Mikrofon izni gerekli. Android ayarlarından JARVIS için mikrofon iznini aç."
        RecognitionFailure.NETWORK -> "Ses tanıma için internet bağlantısı gerekli."
        RecognitionFailure.NETWORK_TIMEOUT -> "Ses tanıma sunucusuna zamanında ulaşılamadı. Bağlantını kontrol edip tekrar dene."
        RecognitionFailure.NO_MATCH -> "Konuşma algılanamadı. Daha net konuşup tekrar dene."
        RecognitionFailure.BUSY -> "Ses tanıma hizmeti meşgul. Birkaç saniye sonra tekrar dene."
        RecognitionFailure.SERVER -> "Ses tanıma sunucusunda geçici bir sorun oluştu. Tekrar dene."
        RecognitionFailure.SPEECH_TIMEOUT -> "Konuşma algılanmadı. Mikrofona konuşup tekrar dene."
        RecognitionFailure.UNKNOWN -> "Ses tanıma sırasında beklenmeyen bir hata oluştu."
    }

    fun normalizeTranscript(text: String?): String? =
        text?.trim()?.takeIf { it.isNotEmpty() }
}
