package com.nuvio.tv.ui.screens.player

import okhttp3.Response
import java.io.EOFException
import java.io.IOException

/** Reads optional warm-up windows without trusting a server to honor Range or Content-Length. */
internal object PlaybackPrewarmReader {
    data class Window(val start: Long, val total: Long, val bytes: ByteArray)

    /** Fixed labels only: never attach response headers, URLs or exception messages. */
    enum class Rejection {
        HTTP_STATUS, ICY_METADATA, CONTENT_ENCODING, CONTENT_RANGE, RANGE_GEOMETRY,
        TOTAL_CHANGED, RANGE_START, RANGE_LENGTH, MISSING_BODY, CONTENT_LENGTH,
        SHORT_BODY, OVERLONG_BODY, IO_FAILURE
    }

    private val contentRange = Regex("bytes (\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)

    /**
     * A null [start] requests a suffix; otherwise requests [maxBytes] at [start].
     * [expectedTotal] binds a head-derived tail request to the length that produced it.
     * Owns and closes [response]. Cancel before close on rejection so OkHttp does not
     * try to drain an ignored range. Transport read-ahead may add one small Okio segment;
     * the retained payload allocation never exceeds [maxBytes].
     */
    fun read(
        response: Response,
        maxBytes: Int,
        start: Long? = null,
        expectedTotal: Long? = null,
        onRejected: (Rejection) -> Unit = {},
        cancel: () -> Unit
    ): Window? {
        require(maxBytes > 0)
        require(start == null || start >= 0)
        var accepted = false
        var rejection = Rejection.IO_FAILURE
        fun reject(reason: Rejection): Window? { rejection = reason; return null }
        try {
            if (response.code != 206) return reject(Rejection.HTTP_STATUS)
            // Cached file windows cannot carry interleaved ICY metadata or its response headers.
            if (response.header("icy-metaint") != null) return reject(Rejection.ICY_METADATA)
            // header() returns only the last value. A later identity/valid range must not
            // hide an encoded representation or conflicting geometry in an earlier field.
            if (response.headers.values("Content-Encoding").any { !it.trim().equals("identity", ignoreCase = true) }) {
                return reject(Rejection.CONTENT_ENCODING)
            }
            val match = contentRange.matchEntire(response.headers.values("Content-Range").singleOrNull()?.trim() ?: "")
                ?: return reject(Rejection.CONTENT_RANGE)
            val first = match.groupValues[1].toLongOrNull() ?: return reject(Rejection.CONTENT_RANGE)
            val last = match.groupValues[2].toLongOrNull() ?: return reject(Rejection.CONTENT_RANGE)
            val total = match.groupValues[3].toLongOrNull() ?: return reject(Rejection.CONTENT_RANGE)
            if (total <= 0 || first > last || last >= total) return reject(Rejection.RANGE_GEOMETRY)
            if (expectedTotal != null && total != expectedTotal) return reject(Rejection.TOTAL_CHANGED)
            val wantedStart = start ?: (total - maxBytes).coerceAtLeast(0L)
            if (wantedStart >= total || first != wantedStart) return reject(Rejection.RANGE_START)
            // Subtract before adding: a huge total/start must not overflow range arithmetic.
            val wantedSize = minOf(maxBytes.toLong(), total - wantedStart).toInt()
            if (last - first + 1L != wantedSize.toLong()) return reject(Rejection.RANGE_LENGTH)
            val lengths = response.headers.values("Content-Length")
            if (lengths.isNotEmpty()) {
                // Optional warm-up is conservative about duplicate/list-valued lengths,
                // including identical duplicates, just like the metadata diagnostic.
                val headerLength = lengths.singleOrNull()?.trim()
                    ?.takeIf { it.isNotEmpty() && it.all { digit -> digit in '0'..'9' } }
                    ?.toLongOrNull()
                if (headerLength != wantedSize.toLong()) return reject(Rejection.CONTENT_LENGTH)
            }
            val body = response.body ?: return reject(Rejection.MISSING_BODY)
            val declaredLength = body.contentLength()
            if (declaredLength != -1L && declaredLength != wantedSize.toLong()) return reject(Rejection.CONTENT_LENGTH)

            val bytes = ByteArray(wantedSize)
            val source = body.source()
            source.readFully(bytes)
            // Detect overlong chunked/unknown-length responses without materializing them.
            if (!source.exhausted()) return reject(Rejection.OVERLONG_BODY)
            accepted = true
            return Window(first, total, bytes)
        } catch (_: EOFException) {
            return reject(Rejection.SHORT_BODY)
        } catch (_: IOException) {
            return reject(Rejection.IO_FAILURE)
        } finally {
            try {
                if (!accepted) {
                    // Observation must not prevent cancellation or response closure.
                    runCatching { onRejected(rejection) }
                    cancel()
                }
            } finally {
                response.close()
            }
        }
    }
}
