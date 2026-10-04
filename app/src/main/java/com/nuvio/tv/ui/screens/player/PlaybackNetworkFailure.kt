package com.nuvio.tv.ui.screens.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

@androidx.annotation.OptIn(UnstableApi::class)
internal object PlaybackNetworkFailure {
    private const val MAX_CAUSE_DEPTH = 12

    fun findNetworkCause(error: Throwable?): Throwable? {
        var current = error
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            when (current) {
                is HttpDataSource.HttpDataSourceException ->
                    if (current.reason in NETWORK_ERROR_CODES) return current
                is SocketTimeoutException,
                is SocketException,
                is UnknownHostException,
                is TimeoutException -> return current
            }
            current = current.cause
            depth++
        }
        return null
    }

    fun hasNetworkCause(error: Throwable?): Boolean = findNetworkCause(error) != null

    fun errorCodeFor(networkCause: Throwable): Int = when (networkCause) {
        is HttpDataSource.HttpDataSourceException ->
            if (networkCause.reason == PlaybackException.ERROR_CODE_IO_UNSPECIFIED) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            } else {
                networkCause.reason
            }
        is SocketTimeoutException,
        is TimeoutException -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        else -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
    }

    /**
     * A chunk that failed for a network reason is reported as an HTTP read failure, so the
     * player sees a network error code instead of the unspecified one it uses for broken files.
     */
    fun chunkFailure(message: String, error: Throwable, dataSpec: DataSpec?): IOException {
        val networkCause = findNetworkCause(error)
        if (networkCause == null || dataSpec == null) return IOException(message, error)
        val ioCause = generateSequence(error) { it.cause }
            .take(MAX_CAUSE_DEPTH)
            .filterIsInstance<IOException>()
            .firstOrNull()
            ?: IOException(error)
        return HttpDataSource.HttpDataSourceException(
            message,
            ioCause,
            dataSpec,
            errorCodeFor(networkCause),
            HttpDataSource.HttpDataSourceException.TYPE_READ
        )
    }

    private val NETWORK_ERROR_CODES = setOf(
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
    )
}
