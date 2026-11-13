package com.nuvio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class NetworkDropRecoveryTest {
    private val spec: DataSpec = DataSpec.Builder().setUri(mockk<Uri>(relaxed = true)).build()

    private fun decide(
        networkError: Boolean = true,
        msSinceNetworkLost: Long? = 2_000L,
        validatedNow: Boolean = true,
        alreadyRecoveredForDrop: Boolean = false,
        torrent: Boolean = false,
        live: Boolean = false,
        loopback: Boolean = false
    ) = NetworkDropRecovery.decide(
        networkError, msSinceNetworkLost, validatedNow, alreadyRecoveredForDrop, torrent, live, loopback
    )

    @Test
    fun `network back after a short drop resumes in place`() {
        assertEquals(NetworkDropAction.RECOVER, decide())
        assertEquals(NetworkDropAction.RECOVER, decide(msSinceNetworkLost = NetworkDropRecovery.WINDOW_MS))
    }

    @Test
    fun `network still down inside the window waits instead of retrying`() {
        assertEquals(NetworkDropAction.WAIT, decide(validatedNow = false))
        assertEquals(NetworkDropAction.WAIT, decide(msSinceNetworkLost = 0L, validatedNow = false))
    }

    @Test
    fun `a drop older than the window leaves the usual path and keeps watching`() {
        assertEquals(NetworkDropAction.WATCH, decide(msSinceNetworkLost = NetworkDropRecovery.WINDOW_MS + 1))
        assertEquals(NetworkDropAction.WATCH, decide(msSinceNetworkLost = 60_000L, validatedNow = false))
    }

    @Test
    fun `no drop seen yet keeps the usual path and watches for one`() {
        assertEquals(NetworkDropAction.WATCH, decide(msSinceNetworkLost = null))
        assertEquals(NetworkDropAction.WATCH, decide(msSinceNetworkLost = -5L))
    }

    @Test
    fun `only one recovery per drop`() {
        assertEquals(NetworkDropAction.WATCH, decide(alreadyRecoveredForDrop = true))
        assertEquals(NetworkDropAction.WATCH, decide(alreadyRecoveredForDrop = true, validatedNow = false))
    }

    @Test
    fun `other errors and other sources are left alone`() {
        assertEquals(NetworkDropAction.NONE, decide(networkError = false))
        assertEquals(NetworkDropAction.NONE, decide(torrent = true))
        assertEquals(NetworkDropAction.NONE, decide(live = true))
        assertEquals(NetworkDropAction.NONE, decide(loopback = true))
        assertEquals(NetworkDropAction.NONE, decide(networkError = false, alreadyRecoveredForDrop = true))
    }

    @Test
    fun `lost connections count as network drops`() {
        val read = HttpDataSource.HttpDataSourceException(
            IOException("unexpected end of stream"),
            spec,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            HttpDataSource.HttpDataSourceException.TYPE_READ
        )
        val unspecified = PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        assertTrue(NetworkDropRecovery.isNetworkDropError(unspecified, read))
        assertTrue(NetworkDropRecovery.isNetworkDropError(unspecified, IOException(SocketTimeoutException("timeout"))))
        assertTrue(NetworkDropRecovery.isNetworkDropError(unspecified, UnknownHostException("cdn.example")))
        assertTrue(NetworkDropRecovery.isNetworkDropError(unspecified, ConnectException("refused")))
        assertTrue(NetworkDropRecovery.isNetworkDropError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, null))
        assertTrue(NetworkDropRecovery.isNetworkDropError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, null))
    }

    @Test
    fun `http statuses are never treated as network drops`() {
        listOf(401, 403, 404, 429, 500, 503).forEach { code ->
            val status = HttpDataSource.InvalidResponseCodeException(code, "fixture", null, emptyMap(), spec, byteArrayOf())
            assertFalse("$code", NetworkDropRecovery.isNetworkDropError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, status))
            assertFalse("$code", NetworkDropRecovery.isNetworkDropError(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, IOException(status)))
        }
        assertFalse(NetworkDropRecovery.isNetworkDropError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, null))
    }

    @Test
    fun `broken files are not network drops`() {
        assertFalse(NetworkDropRecovery.isNetworkDropError(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, IllegalStateException("bad box")))
        assertFalse(NetworkDropRecovery.isNetworkDropError(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, IOException("Short chunk")))
        assertFalse(NetworkDropRecovery.isNetworkDropError(PlaybackException.ERROR_CODE_DECODING_FAILED, null))
    }
}
