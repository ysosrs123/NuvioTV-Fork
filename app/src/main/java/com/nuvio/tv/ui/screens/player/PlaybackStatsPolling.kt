package com.nuvio.tv.ui.screens.player

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/** Probe work must not delay the main-thread HUD sample cadence; both children share its lifetime. */
internal suspend fun pollPlaybackStats(
    probe: suspend () -> PlaybackConnectSample?,
    sample: (PlaybackConnectSample?) -> Unit
): Unit = coroutineScope {
    val latest = AtomicReference<PlaybackConnectSample?>(null)
    launch {
        while (true) {
            latest.set(probe())
            delay(5_000L)
        }
    }
    while (true) {
        sample(latest.get())
        delay(1_000L)
    }
}
