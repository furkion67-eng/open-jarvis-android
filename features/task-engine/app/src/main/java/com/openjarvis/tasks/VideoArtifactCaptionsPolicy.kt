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

    fun readValidated(
        input: InputStream,
        maxBytes: Int = MAX_CAPTIONS_BYTES
    ): String? {
        if (maxBytes <= 0) return null

        val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
        val buffer = ByteArray(8 * 1024)
        var totalBytes = 0

        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0 || count > maxBytes - totalBytes) return null
            output.write(buffer, 0, count)
            totalBytes += count
        }

        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = runCatching {
            decoder.decode(ByteBuffer.wrap(output.toByteArray())).toString()
        }.getOrNull() ?: return null

        return text.takeIf { it.isNotBlank() && it.contains("-->") }
    }
}
