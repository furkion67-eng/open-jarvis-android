package com.openjarvis.voice

import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TurkishSpeechIntentInstrumentedTest {
    @Test
    fun sharedRecognizerIntentAlwaysRequestsTurkish() {
        val intent = TurkishSpeechPolicy.createRecognizerIntent()

        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, intent.action)
        assertEquals(
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL)
        )
        assertEquals("tr-TR", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertEquals("tr-TR", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE))
        assertEquals(1, intent.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 0))
        assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
    }

    @Test
    fun platformRecognitionErrorsMapToTheExpectedTurkishFailureCategories() {
        val cases = listOf(
            SpeechRecognizer.ERROR_AUDIO to RecognitionFailure.AUDIO,
            SpeechRecognizer.ERROR_CLIENT to RecognitionFailure.CLIENT,
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS to RecognitionFailure.PERMISSION,
            SpeechRecognizer.ERROR_NETWORK to RecognitionFailure.NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT to RecognitionFailure.NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_NO_MATCH to RecognitionFailure.NO_MATCH,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY to RecognitionFailure.BUSY,
            SpeechRecognizer.ERROR_SERVER to RecognitionFailure.SERVER,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT to RecognitionFailure.SPEECH_TIMEOUT
        )

        cases.forEach { (platformError, expected) ->
            assertEquals(expected, TurkishSpeechPolicy.failureForAndroidError(platformError))
            assertTrue(TurkishSpeechPolicy.messageFor(expected).isNotBlank())
        }
    }

    @Test
    fun missingMicrophonePermissionProducesTurkishGuidance() {
        val message = TurkishSpeechPolicy.messageFor(
            TurkishSpeechPolicy.failureForAndroidError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        )
        assertTrue(message.contains("Mikrofon izni"))
    }
}
