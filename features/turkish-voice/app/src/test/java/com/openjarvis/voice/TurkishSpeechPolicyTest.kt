package com.openjarvis.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurkishSpeechPolicyTest {
    @Test
    fun languageTagIsExplicitlyTurkish() {
        assertEquals("tr-TR", TurkishSpeechPolicy.LANGUAGE_TAG)
    }

    @Test
    fun blankRecognitionResultsAreDiscardedAndTextIsTrimmed() {
        assertNull(TurkishSpeechPolicy.normalizeTranscript(null))
        assertNull(TurkishSpeechPolicy.normalizeTranscript("  \n  "))
        assertEquals("Merhaba Jarvis", TurkishSpeechPolicy.normalizeTranscript("  Merhaba Jarvis  "))
    }

    @Test
    fun everyRecognitionFailureHasActionableTurkishCopy() {
        RecognitionFailure.values().forEach { failure ->
            val message = TurkishSpeechPolicy.messageFor(failure)
            assertTrue("Missing Turkish message for $failure", message.isNotBlank())
            assertFalse("Unexpected English error copy for $failure", message.contains("error", ignoreCase = true))
        }
    }

    @Test
    fun permissionAndNetworkFailuresAreClearlyExplained() {
        assertTrue(TurkishSpeechPolicy.messageFor(RecognitionFailure.PERMISSION).contains("Mikrofon izni"))
        assertTrue(TurkishSpeechPolicy.messageFor(RecognitionFailure.NETWORK).contains("internet"))
    }
}
