package com.nuvio.tv.data.trailer

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Trailer links that failed in the player. Caches skip them and the screen that owns one looks it up again. */
object TrailerPlaybackFailures {
    private const val MAX_REMEMBERED = 200

    private val failed: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    private val _events = MutableSharedFlow<String>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<String> = _events.asSharedFlow()

    fun report(videoUrl: String) {
        if (videoUrl.isBlank()) return
        if (failed.size >= MAX_REMEMBERED) failed.clear()
        failed.add(videoUrl)
        _events.tryEmit(videoUrl)
    }

    fun hasFailed(videoUrl: String): Boolean = videoUrl in failed
}
