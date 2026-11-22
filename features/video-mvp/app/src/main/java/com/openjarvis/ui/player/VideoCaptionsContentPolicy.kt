package com.openjarvis.ui.player

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Reads the renderer's persisted SRT with a strict byte ceiling.
 *
 * The player validates artifacts again on every reopen. A truncated, unexpectedly large, or
 * malformed captions document must produce an unavailable state instead of allocating an
 * unbounded String while the Dashboard is opening.
 */
internal object VideoCaptionsContentPolicy {
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
