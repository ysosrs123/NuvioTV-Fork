package com.nuvio.tv.ui.screens.stream

import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource

/**
 * A Mix episode with Pick sources for me on picks its own source even when auto-play is Manual, as the player does
 * for a next episode: the first suitable stream, in the viewer's own order and quality rules. Other settings stay.
 */
internal fun PlayerSettings.forMixPick(pickForMe: Boolean): PlayerSettings =
    if (!pickForMe || streamAutoPlayMode != StreamAutoPlayMode.MANUAL) this
    else copy(
        streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM,
        streamAutoPlaySource = StreamAutoPlaySource.ALL_SOURCES,
        streamAutoPlaySelectedAddons = emptySet(),
        streamAutoPlaySelectedPlugins = emptySet(),
        streamAutoPlayRegex = ""
    )
