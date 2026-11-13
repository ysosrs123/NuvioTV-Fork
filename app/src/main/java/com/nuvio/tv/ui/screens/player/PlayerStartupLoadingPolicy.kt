package com.nuvio.tv.ui.screens.player

/**
 * Loading overlay / playback-issue report decisions during startup buffering.
 */
internal object PlayerStartupLoadingPolicy {

    fun reportReason(
        hasRenderedFirstFrame: Boolean,
        error: String?,
        wasPlaying: Boolean,
    ): String = when {
        error != null -> "playback_error"
        !hasRenderedFirstFrame -> "loading_stall"
        wasPlaying -> "while_playing"
        else -> "while_paused"
    }

    fun isLoadingStallReportable(
        showLoadingOverlay: Boolean,
        hasRenderedFirstFrame: Boolean,
        error: String?,
    ): Boolean {
        return error == null && showLoadingOverlay && !hasRenderedFirstFrame
    }

    fun shouldReopenLoadingOverlayOnStartupBuffering(
        loadingOverlayEnabled: Boolean,
        showLoadingOverlay: Boolean,
        hasRenderedFirstFrame: Boolean,
        isBuffering: Boolean,
    ): Boolean {
        return isBuffering &&
            !hasRenderedFirstFrame &&
            loadingOverlayEnabled &&
            !showLoadingOverlay
    }

    fun shouldUpdateBufferingMessageBeforeFirstFrame(
        hasRenderedFirstFrame: Boolean,
        isBuffering: Boolean,
    ): Boolean {
        return isBuffering && !hasRenderedFirstFrame
    }
}
