package com.nuvio.tv.ui.screens.player

import androidx.media3.common.PlaybackException

/** The disk cache can hold a file length shorter than the file, which refuses every read past it. */
internal object VodCacheShortLength {

    fun storedLengthIsShort(storedLength: Long, knownLength: Long): Boolean =
        storedLength > 0L && knownLength > 0L && storedLength < knownLength

    fun retryWithoutCache(errorCode: Int, cacheActive: Boolean): Boolean =
        cacheActive && errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
}
