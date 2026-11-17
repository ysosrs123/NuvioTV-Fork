package com.nuvio.tv.ui.screens.player

/**
 * Where a new player starts. Starting at 0 and seeking once the player is ready throws away what it buffered at 0,
 * so any position known before the build goes in as the start position instead.
 */
internal object PlayerStartPosition {

    /** The caller's position, else a position a rebuild carried over, else the saved resume point. */
    fun choose(explicit: Long?, carriedOver: Long?, savedResume: () -> Long): Long =
        explicit ?: carriedOver?.takeIf { it > 0L } ?: savedResume()

    /** A resume point at or within a few seconds of the file's real end belongs to another file or a bad save. */
    fun pastTheEnd(startMs: Long, durationMs: Long): Boolean =
        startMs > 0L && durationMs > 0L && startMs >= durationMs - END_MARGIN_MS

    private const val END_MARGIN_MS = 5_000L

    /**
     * A resume saved only as a share of the film (Trakt) needs the film's length, so it is applied as soon as the
     * length is known and the file can seek, before the first frame, instead of after the start buffer has filled.
     */
    fun percentResumeDue(
        savedPositionMs: Long,
        savedPercent: Float?,
        durationMs: Long,
        seekable: Boolean,
        firstFrameShown: Boolean
    ): Boolean = savedPositionMs <= 0L && savedPercent != null && savedPercent > 0f && durationMs > 0L &&
        seekable && !firstFrameShown
}
