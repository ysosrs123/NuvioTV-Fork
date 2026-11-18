package com.nuvio.tv.ui.screens.player

import androidx.media3.common.PlaybackException

/** The disk cache can hold a file length shorter than the file, which refuses every read past it. */
internal object VodCacheShortLength {

    /**
     * Only a stored length that looks like one capped piece (whole mebibytes, at most [MAX_PIECE_BYTES]) and is far
     * below the size the add-on gave counts. Add-on sizes can be wrong by gigabytes, so a stored length near it is the
     * server's real one and stays.
     */
    fun storedLengthIsShort(storedLength: Long, knownLength: Long): Boolean =
        storedLength > 0L && knownLength > 0L &&
            storedLength <= MAX_PIECE_BYTES && storedLength % MIB == 0L &&
            storedLength * PIECE_RATIO < knownLength

    private const val MIB = 1024L * 1024L
    private const val MAX_PIECE_BYTES = 256L * MIB
    private const val PIECE_RATIO = 4L

    fun retryWithoutCache(errorCode: Int, cacheActive: Boolean): Boolean =
        cacheActive && errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
}
