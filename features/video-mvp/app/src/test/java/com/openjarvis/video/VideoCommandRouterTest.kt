package com.openjarvis.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCommandRouterTest {
    @Test
    fun typedAndRecognizedSpeechResolveTheSameTurkishTopic() {
        val typed = VideoCommandRouter.parse(
            "İstanbul'un Fethi hakkında video oluştur",
            VideoCommandOrigin.TYPED
        )
        val speech = VideoCommandRouter.parse(
            "İstanbul'un Fethi hakkında video oluştur",
            VideoCommandOrigin.RECOGNIZED_SPEECH
        )

        assertNotNull(typed)
        assertNotNull(speech)
        assertEquals("İstanbul'un Fethi", typed?.topic)
        assertEquals(typed?.topic, speech?.topic)
        assertEquals(VideoCommandOrigin.TYPED, typed?.origin)
        assertEquals(VideoCommandOrigin.RECOGNIZED_SPEECH, speech?.origin)
    }

    @Test
    fun acceptsTurkishInflectionAndVideoFirstOrder() {
        val parsed = VideoCommandRouter.parse(
            "Lütfen bir video hazırlayalım: 1453'te İstanbul'un Fethi",
            VideoCommandOrigin.TYPED
        )
        assertEquals("1453'te İstanbul'un Fethi", parsed?.topic)
    }

    @Test
    fun stripsTrailingPunctuationAndPoliteTurkishVerbForms() {
        val punctuated = VideoCommandRouter.parse(
            "İstanbul'un Fethi hakkında video oluştur.",
            VideoCommandOrigin.RECOGNIZED_SPEECH
        )
        val polite = VideoCommandRouter.parse(
            "İstanbul'un Fethi hakkında video hazırlar mısın?",
            VideoCommandOrigin.TYPED
        )
        assertEquals("İstanbul'un Fethi", punctuated?.topic)
        assertEquals("İstanbul'un Fethi", polite?.topic)
    }

    @Test
    fun videoCommandWithoutTopicRequestsMoreInformation() {
        val parsed = VideoCommandRouter.parse("Video oluştur", VideoCommandOrigin.RECOGNIZED_SPEECH)
        assertNotNull(parsed)
        assertNull(parsed?.topic)
    }

    @Test
    fun ordinaryConversationAboutVideoIsNotACommand() {
        assertNull(VideoCommandRouter.parse("Video hakkında konuşalım", VideoCommandOrigin.TYPED))
        assertNull(VideoCommandRouter.parse("Bu videoyu beğendim", VideoCommandOrigin.RECOGNIZED_SPEECH))
        assertNull(VideoCommandRouter.parse("Video oluşturma hakkında konuş", VideoCommandOrigin.TYPED))
    }


    @Test
    fun typedAndSpeechRequestsShareStableKeyWithinDedupWindow() {
        val typed = VideoCommandRouter.parse(
            "İstanbul'un Fethi hakkında video oluştur",
            VideoCommandOrigin.TYPED
        )!!
        val speech = VideoCommandRouter.parse(
            "İstanbul'un Fethi hakkında video oluştur",
            VideoCommandOrigin.RECOGNIZED_SPEECH
        )!!

        val typedKey = VideoCommandRouter.requestIdempotencyKey(typed.topic!!, 10_000L)
        val speechKey = VideoCommandRouter.requestIdempotencyKey(speech.topic!!, 29_999L)
        val normalizedKey = VideoCommandRouter.requestIdempotencyKey(
            "  İSTANBUL'un   FETHİ  ",
            10_000L
        )

        assertEquals(typedKey, speechKey)
        assertEquals(typedKey, normalizedKey)
        assertEquals(64, typedKey.length)
    }

    @Test
    fun sameTopicCanBeRequestedAgainAfterDedupWindow() {
        val beforeBoundary = VideoCommandRouter.requestIdempotencyKey("İstanbul'un Fethi", 29_999L)
        val afterBoundary = VideoCommandRouter.requestIdempotencyKey("İstanbul'un Fethi", 30_000L)
        assertTrue(beforeBoundary != afterBoundary)
    }

    @Test
    fun differentTopicsNeverShareRequestKeyInSameWindow() {
        val first = VideoCommandRouter.requestIdempotencyKey("İstanbul'un Fethi", 10_000L)
        val second = VideoCommandRouter.requestIdempotencyKey("Kedilerin bakımı", 10_000L)
        assertTrue(first != second)
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankTopicCannotProduceRequestKey() {
        VideoCommandRouter.requestIdempotencyKey("   ", 10_000L)
    }

    @Test
    fun canonicalTaskGoalCanBeResolvedAgainByExecutor() {
        val parsed = VideoCommandRouter.parse(
            "Kedilerin bakımı hakkında video oluştur",
            VideoCommandOrigin.TASK
        )
        assertEquals("Kedilerin bakımı", parsed?.topic)
        assertTrue(parsed?.origin == VideoCommandOrigin.TASK)
    }
}
