package com.openjarvis.ui.player

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoCaptionsContentPolicyTest {
    @Test
    fun acceptsBoundedUtf8SrtAndPreservesTurkishCaptionText() {
        val srt = "1\\n00:00:00,000 --> 00:00:02,000\\nİstanbul'un fethi başladı.\\n"

        val restored = VideoCaptionsContentPolicy.readValidated(
            ByteArrayInputStream(srt.toByteArray(Charsets.UTF_8))
        )

        assertEquals(srt, restored)
    }

    @Test
    fun rejectsBlankAndNonSrtContent() {
        assertNull(
            VideoCaptionsContentPolicy.readValidated(ByteArrayInputStream("  \\n".toByteArray()))
        )
        assertNull(
            VideoCaptionsContentPolicy.readValidated(ByteArrayInputStream("plain caption without cue".toByteArray()))
        )
    }

    @Test
    fun rejectsOversizedSrtAfterOnlyOneOverflowProbeByte() {
        val maxBytes = 64
        val document = ByteArray(64 * 1024) { 'x'.code.toByte() }
        val source = CountingInputStream(document)

        val result = VideoCaptionsContentPolicy.readValidated(source, maxBytes = maxBytes)

        assertNull(result)
        assertEquals(
            "The player adapter must consume no more than the limit plus one probe byte.",
            maxBytes + 1,
            source.bytesRead
        )
        assertEquals(
            "The final bounded read must request exactly one overflow probe.",
            listOf(maxBytes + 1),
            source.requestedLengths
        )
    }

    @Test
    fun rejectsMalformedUtf8InsteadOfSilentlyReplacingBytes() {
        val malformed = byteArrayOf(
            0xC3.toByte(),
            0x28,
            0x0A,
            0x30,
            0x30,
            0x3A,
            0x30,
            0x30,
            0x3A,
            0x30,
            0x30,
            0x2C,
            0x30,
            0x30,
            0x30,
            0x20,
            0x2D,
            0x2D,
            0x3E,
            0x20,
            0x30,
            0x30,
            0x3A,
            0x30,
            0x30,
            0x3A,
            0x30,
            0x32,
            0x2C,
            0x30,
            0x30,
            0x30
        )

        assertNull(VideoCaptionsContentPolicy.readValidated(ByteArrayInputStream(malformed)))
    }

    @Test
    fun exactMaximumSizeIsAcceptedWhenItContainsASrtCue() {
        val prefix = "1\\n00:00:00,000 --> 00:00:01,000\\n"
        val padding = "a".repeat(64 - prefix.toByteArray(Charsets.UTF_8).size)
        val srt = prefix + padding

        val result = VideoCaptionsContentPolicy.readValidated(
            ByteArrayInputStream(srt.toByteArray(Charsets.UTF_8)),
            maxBytes = 64
        )

        assertEquals(64, result?.toByteArray(Charsets.UTF_8)?.size)
    }

    private class CountingInputStream(private val bytes: ByteArray) : InputStream() {
        private var offset = 0
        var bytesRead: Int = 0
            private set
        val requestedLengths = mutableListOf<Int>()

        override fun read(): Int {
            if (offset >= bytes.size) return -1
            bytesRead++
            return bytes[offset++].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, start: Int, length: Int): Int {
            requestedLengths += length
            if (offset >= bytes.size) return -1
            val count = minOf(length, bytes.size - offset)
            bytes.copyInto(buffer, start, offset, offset + count)
            offset += count
            bytesRead += count
            return count
        }
    }
}
