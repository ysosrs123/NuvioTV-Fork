package com.nuvio.tv.ui.screens.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VodCacheShortLengthTest {

    @Test
    fun `a stored length below the known file size is short`() {
        assertTrue(VodCacheShortLength.storedLengthIsShort(67_108_864L, 11_658_940_157L))
    }

    @Test
    fun `a matching, missing or unknown length is left alone`() {
        assertFalse(VodCacheShortLength.storedLengthIsShort(8_005_045_536L, 8_005_045_536L))
        // The server's real length below a wrong add-on size stays.
        assertFalse(VodCacheShortLength.storedLengthIsShort(42_154_263_936L, 50_123_388_878L))
        assertFalse(VodCacheShortLength.storedLengthIsShort(150_000_000L, 1_000_000_000L))
        assertFalse(VodCacheShortLength.storedLengthIsShort(67_108_864L, 200_000_000L))
        assertTrue(VodCacheShortLength.storedLengthIsShort(16_777_216L, 5_000_000_000L))
        assertFalse(VodCacheShortLength.storedLengthIsShort(-1L, 8_005_045_536L))
        assertFalse(VodCacheShortLength.storedLengthIsShort(67_108_864L, 0L))
        assertFalse(VodCacheShortLength.storedLengthIsShort(67_108_864L, -1L))
    }

    @Test
    fun `read position out of range with the disk cache on retries without it`() {
        assertTrue(
            VodCacheShortLength.retryWithoutCache(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, cacheActive = true)
        )
    }

    @Test
    fun `no cache retry when the cache is off or the error is something else`() {
        assertFalse(
            VodCacheShortLength.retryWithoutCache(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, cacheActive = false)
        )
        assertFalse(
            VodCacheShortLength.retryWithoutCache(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, cacheActive = true)
        )
        assertFalse(
            VodCacheShortLength.retryWithoutCache(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, cacheActive = true)
        )
    }
}
