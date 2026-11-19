package com.openjarvis.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoTaskArtifactKeyTest {
    @Test
    fun sameDurableRequestKeyProducesSameOpaqueArtifactToken() {
        val first = VideoTaskArtifactKey.from("video-request-001")
        val retry = VideoTaskArtifactKey.from("video-request-001")
        assertEquals(first, retry)
        assertTrue(first.matches(Regex("[a-f0-9]{32}")))
        assertTrue(!first.contains("video-request"))
    }

    @Test
    fun differentRequestKeysProduceDifferentArtifactTokens() {
        assertNotEquals(
            VideoTaskArtifactKey.from("video-request-001"),
            VideoTaskArtifactKey.from("video-request-002")
        )
    }
}
