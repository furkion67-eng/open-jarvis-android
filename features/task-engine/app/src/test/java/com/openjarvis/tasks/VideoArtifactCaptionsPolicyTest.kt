package com.openjarvis.tasks

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoArtifactCaptionsPolicyTest {
    @Test
    fun acceptsBoundedTurkishUtf8Srt() {
        val srt = "1\n00:00:00,000 --> 00:00:02,000\nİstanbul'un fethi başladı.\n"

        assertEquals(
            srt,
            VideoArtifactCaptionsPolicy.readValidated(ByteArrayInputStream(srt.toByteArray(Charsets.UTF_8)))
        )
    }

    @Test
    fun rejectsBlankAndNonSrtContent() {
        assertNull(VideoArtifactCaptionsPolicy.readValidated(ByteArrayInputStream("  \n".toByteArray())))
        assertNull(VideoArtifactCaptionsPolicy.readValidated(ByteArrayInputStream("plain text".toByteArray())))
    }

    @Test
    fun rejectsOversizedInputBeforeReadingTheWholeDocument() {
        val document = ByteArray(64 * 1024) { 'x'.code.toByte() }
        val source = CountingInputStream(document)

        assertNull(VideoArtifactCaptionsPolicy.readValidated(source, maxBytes = 64))
        assertTrue("The validator must stop at the first bounded read.", source.bytesRead < document.size)
    }

    @Test
    fun rejectsInvalidTimestampsAndCuesWithoutCaptionText() {
        val reversed = "1\n00:00:02,000 --> 00:00:01,000\ncaption\n"
        val missingText = "1\n00:00:00,000 --> 00:00:01,000\n"
        val malformedTimestamp = "1\n00:00:00 --> 00:00:01\ncaption\n"

        assertNull(VideoArtifactCaptionsPolicy.readValidated(ByteArrayInputStream(reversed.toByteArray())))
        assertNull(VideoArtifactCaptionsPolicy.readValidated(ByteArrayInputStream(missingText.toByteArray())))
        assertNull(VideoArtifactCaptionsPolicy.readValidated(ByteArrayInputStream(malformedTimestamp.toByteArray())))
    }

    @Test
    fun rejectsMalformedUtf8InsteadOfReplacingBytes() {
        val malformed = byteArrayOf(
            0xC3.toByte(), 0x28, 0x0A,
            0x30, 0x30, 0x3A, 0x30, 0x30, 0x3A, 0x30, 0x30, 0x2C, 0x30, 0x30, 0x30,
            0x20, 0x2D, 0x2D, 0x3E, 0x20,
            0x30, 0x30, 0x3A, 0x30, 0x30, 0x3A, 0x30, 0x32, 0x2C, 0x30, 0x30, 0x30
        )

        assertNull(VideoArtifactCaptionsPolicy.readValidated(ByteArrayInputStream(malformed)))
    }

    @Test
    fun exactByteLimitIsAccepted() {
        val prefix = "1\n00:00:00,000 --> 00:00:01,000\n"
        val padding = "a".repeat(64 - prefix.toByteArray(Charsets.UTF_8).size)
        val srt = prefix + padding

        val restored = VideoArtifactCaptionsPolicy.readValidated(
            ByteArrayInputStream(srt.toByteArray(Charsets.UTF_8)),
            maxBytes = 64
        )

        assertEquals(64, restored?.toByteArray(Charsets.UTF_8)?.size)
    }

    private class CountingInputStream(private val bytes: ByteArray) : InputStream() {
        private var offset = 0
        var bytesRead: Int = 0
            private set

        override fun read(): Int {
            if (offset >= bytes.size) return -1
            bytesRead++
            return bytes[offset++].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, start: Int, length: Int): Int {
            if (offset >= bytes.size) return -1
            val count = minOf(length, bytes.size - offset)
            bytes.copyInto(buffer, start, offset, offset + count)
            offset += count
            bytesRead += count
            return count
        }
    }
}
