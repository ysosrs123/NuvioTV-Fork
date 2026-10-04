package com.nuvio.tv.ui.screens.player

import androidx.media3.common.PlaybackException
import com.nuvio.tv.data.mediaserver.ServerPlaybackTarget
import com.nuvio.tv.domain.model.Stream

/** Identity a source is remembered by once it has failed in this session. */
internal fun Stream.deadSourceKey(): String? = SourceFailoverSelection.failoverKey(this)

internal fun indexOfServerTarget(streams: List<Stream>, target: ServerPlaybackTarget?): Int =
    if (target == null) -1 else streams.indexOfFirst { it.serverTarget == target }

internal fun isMidPlayCorruption(errorCode: Int, hasRenderedFirstFrame: Boolean): Boolean =
    hasRenderedFirstFrame &&
        (errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
            errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
