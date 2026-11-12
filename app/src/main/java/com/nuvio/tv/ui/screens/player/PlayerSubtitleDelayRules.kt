package com.nuvio.tv.ui.screens.player

/** A subtitle delay belongs to one video: a new source keeps it, another video starts at 0. */
internal fun subtitleDelayMsAfterSwitch(
    currentDelayMs: Int,
    fromVideoId: String?,
    toVideoId: String?,
): Int = if (fromVideoId == toVideoId) currentDelayMs else 0
