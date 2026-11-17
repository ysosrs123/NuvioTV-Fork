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
