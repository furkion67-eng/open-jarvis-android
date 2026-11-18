package com.openjarvis.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurkishTtsPolicyTest {
    @Test
    fun exactTrTrOfflineVoiceWinsOverHigherQualityOtherRegion() {
        val selected = TurkishTtsPolicy.selectVoice(
            listOf(
                TurkishTtsVoiceCandidate("tr-TR-network", "tr-TR", 500, 10, true),
                TurkishTtsVoiceCandidate("tr-CY-offline", "tr-CY", 500, 5, false),
                TurkishTtsVoiceCandidate("tr-TR-offline", "tr-TR", 300, 20, false)
            )
        )
        assertEquals("tr-TR-offline", selected?.name)
    }

    @Test
    fun offlineWinsWhenExactLocaleAndQualityAreEqual() {
        val selected = TurkishTtsPolicy.selectVoice(
            listOf(
                TurkishTtsVoiceCandidate("network", "tr-TR", 500, 1, true),
                TurkishTtsVoiceCandidate("offline", "tr-TR", 500, 20, false)
            )
        )
        assertEquals("offline", selected?.name)
    }

    @Test
    fun nonTurkishVoicesAreNeverSelected() {
        val selected = TurkishTtsPolicy.selectVoice(
            listOf(TurkishTtsVoiceCandidate("english", "en-US", 500, 1, false))
        )
        assertNull(selected)
    }

    @Test
    fun compatibleTurkishLocaleIsAllowedWhenExactTrTrIsUnavailable() {
        val selected = TurkishTtsPolicy.selectVoice(
            listOf(TurkishTtsVoiceCandidate("turkish", "tr", 300, 30, false))
        )
        assertEquals("turkish", selected?.name)
        assertTrue(TurkishTtsPolicy.isTurkishLocaleTag("tr-TR"))
        assertFalse(TurkishTtsPolicy.isTurkishLocaleTag("en-GB"))
    }

    @Test
    fun unsupportedLanguageAvailabilityIsRejected() {
        assertTrue(TurkishTtsPolicy.isLanguageAvailable(0))
        assertTrue(TurkishTtsPolicy.isLanguageAvailable(1))
        assertTrue(TurkishTtsPolicy.isLanguageAvailable(2))
        assertFalse(TurkishTtsPolicy.isLanguageAvailable(-1))
        assertFalse(TurkishTtsPolicy.isLanguageAvailable(-2))
    }

    @Test
    fun missingVoiceMessageIsActionableAndTurkish() {
        val message = TurkishTtsPolicy.messageFor(TurkishTtsStatus.TurkishVoiceMissing)
        assertTrue(message.contains("Türkçe"))
        assertTrue(message.contains("Android ses ayarlarından"))
    }
    @Test
    fun initializationFailureMessageIsTurkishAndActionable() {
        val message = TurkishTtsPolicy.messageFor(TurkishTtsStatus.InitializationFailed)
        assertTrue(message.contains("Android metin okuma motoru"))
        assertTrue(message.contains("tekrar dene"))
    }

    @Test
    fun recognizesAndroidNetworkSynthesisFeatureNames() {
        assertTrue(TurkishTtsPolicy.requiresNetwork(setOf("networkTts")))
        assertTrue(TurkishTtsPolicy.requiresNetwork(setOf("network")))
        assertFalse(TurkishTtsPolicy.requiresNetwork(setOf("embeddedTts")))
    }


    @Test
    fun stopOrReleaseDrainsPendingHandoffsExactlyOnce() {
        val callbacks = PendingUtteranceCallbacks()
        var completed = 0
        callbacks.register("first") { completed += 1 }
        callbacks.register("second") { completed += 1 }

        callbacks.completeAll()
        callbacks.completeAll()

        assertEquals("Every pending handoff is completed once", 2, completed)
    }

    @Test
    fun replacingAnUtteranceDiscardsOnlyTheOlderHandoff() {
        val callbacks = PendingUtteranceCallbacks()
        var oldCompleted = 0
        var latestCompleted = 0
        callbacks.register("old") { oldCompleted += 1 }

        callbacks.discardAll()
        callbacks.register("latest") { latestCompleted += 1 }
        callbacks.completeAll()

        assertEquals(0, oldCompleted)
        assertEquals(1, latestCompleted)
    }
}
