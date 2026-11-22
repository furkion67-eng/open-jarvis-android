package com.openjarvis.ui.player

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCaptionsContentPolicyTest {
    @Test
    fun acceptsBoundedUtf8SrtAndPreservesTurkishCaptionText() {
        val srt = "1\n00:00:00,000 --> 00:00:02,000\nİstanbul'un fethi başladı.\n"

        val restored = VideoCaptionsContentPolicy.readValidated(
            ByteArrayInputStream(srt.toByteArray(Charsets.UTF_8))
        )

        assertEquals(srt, restored)
    }

    @Test
    fun rejectsBlankAndNonSrtContent() {
        assertNull(
            VideoCaptionsContentPolicy.readValidated(ByteArrayInputStream("  \n".toByteArray()))
        )
        assertNull(
            VideoCaptionsContentPolicy.readValidated(ByteArrayInputStream("plain caption without cue".toByteArray()))
        )
    }

    @Test
    fun rejectsOversizedSrtBeforeReadingTheWholeDocument() {
        val document = ByteArray(64 * 1024) { 'x'.code.toByte() }
        var bytesRead = 0
        val source = object : InputStream() {
            private var offset = 0

            override fun read(): Int {
                if (offset >= document.size) return -1
                bytesRead++
                return document[offset++].toInt() and 0xff
            }

            override fun read(buffer: ByteArray, start: Int, length: Int): Int {
                if (offset >= document.size) return -1
                val count = minOf(length, document.size - offset)
                document.copyInto(buffer, start, offset, offset + count)
                offset += count
                bytesRead += count
                return count
            }
        }

        val result = VideoCaptionsContentPolicy.readValidated(source, maxBytes = 64)

        assertNull(result)
        assertTrue("Oversized input must stop after the first bounded buffer.", bytesRead < document.size)
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
        val prefix = "1\n00:00:00,000 --> 00:00:01,000\n"
        val padding = "a".repeat(64 - prefix.toByteArray(Charsets.UTF_8).size)
        val srt = prefix + padding

        val result = VideoCaptionsContentPolicy.readValidated(
            ByteArrayInputStream(srt.toByteArray(Charsets.UTF_8)),
            maxBytes = 64
        )

        assertEquals(64, result?.toByteArray(Charsets.UTF_8)?.size)
    }
}
