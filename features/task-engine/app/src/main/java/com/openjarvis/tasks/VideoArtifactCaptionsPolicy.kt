package com.openjarvis.tasks

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Bounded, strict validator for renderer-produced SRT content.
 *
 * This is the shared trust boundary used before a rendered artifact can be persisted and again
 * by the in-app player after process recreation. It rejects malformed UTF-8 and stops as soon as
 * the byte ceiling would be exceeded; it never calls readText() on an untrusted content URI.
 */
internal object VideoArtifactCaptionsPolicy {
    const val MAX_CAPTIONS_BYTES: Int = 2 * 1024 * 1024
    private val timestampPattern = Regex("""^(\d{2,}):([0-5]\d):([0-5]\d),(\d{3})$""")

    fun readValidated(
        input: InputStream,
        maxBytes: Int = MAX_CAPTIONS_BYTES
    ): String? {
        if (maxBytes <= 0) return null

        val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
        val buffer = ByteArray(8 * 1024)
        var totalBytes = 0

        while (true) {
            val remainingBytes = maxBytes - totalBytes
            // Keep the final overflow probe to one byte. Reading the full buffer here could
            // consume several KiB beyond the configured limit even though none is retained.
            val requestedBytes = if (remainingBytes >= buffer.size) {
                buffer.size
            } else {
                remainingBytes + 1
            }
            val count = input.read(buffer, 0, requestedBytes)
            if (count < 0) break
            if (count == 0 || count > remainingBytes) return null
            output.write(buffer, 0, count)
            totalBytes += count
        }

        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = runCatching {
            decoder.decode(ByteBuffer.wrap(output.toByteArray())).toString()
        }.getOrNull() ?: return null

        return text.takeIf(::containsValidSrtCues)
    }

    private fun containsValidSrtCues(content: String): Boolean {
        val normalized = content.removePrefix("\uFEFF").trim()
        if (normalized.isEmpty()) return false
        val blocks = normalized.split(Regex("\\r?\\n\\s*\\r?\\n"))
        if (blocks.isEmpty()) return false

        return blocks.all { block ->
            val lines = block.lines().map { it.trim() }
            if (lines.size < 3) {
                false
            } else {
                val cueNumber = lines[0].toIntOrNull()
                val timeRange = lines[1].split(" --> ")
                val start = timeRange.getOrNull(0)?.let(::parseTimestamp)
                val end = timeRange.getOrNull(1)?.let(::parseTimestamp)
                cueNumber != null &&
                    cueNumber > 0 &&
                    timeRange.size == 2 &&
                    start != null &&
                    end != null &&
                    end > start &&
                    lines.drop(2).any { it.isNotBlank() }
            }
        }
    }

    private fun parseTimestamp(value: String): Long? {
        val match = timestampPattern.matchEntire(value) ?: return null
        val hours = match.groupValues[1].toLongOrNull() ?: return null
        val minutes = match.groupValues[2].toLongOrNull() ?: return null
        val seconds = match.groupValues[3].toLongOrNull() ?: return null
        val millis = match.groupValues[4].toLongOrNull() ?: return null
        if (hours > 100_000L) return null
        return hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + millis
    }
}
