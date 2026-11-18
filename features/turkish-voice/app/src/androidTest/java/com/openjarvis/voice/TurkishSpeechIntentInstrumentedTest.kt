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
    fun missingMicrophonePermissionProducesTurkishGuidance() {
        val message = TurkishSpeechPolicy.messageFor(
            TurkishSpeechPolicy.failureForAndroidError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        )
        assertTrue(message.contains("Mikrofon izni"))
    }
}
