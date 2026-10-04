package com.nuvio.tv.ui.screens.detail

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Main-thread handoff from metadata publication to a rendered hero. Never waits indefinitely. */
internal class DetailPresentationGate {
    private var generation = 0
    private var presented = CompletableDeferred<Unit>()
    private var secondaryRequested = false
    private val secondaryDemand = CompletableDeferred<Unit>()

    fun begin(): Int {
        presented.complete(Unit)
        presented = CompletableDeferred()
        if (secondaryRequested) presented.complete(Unit)
        return ++generation
    }

    fun acknowledge(token: Int) {
        if (token == generation) presented.complete(Unit)
    }

    fun requestSecondary() {
        secondaryRequested = true
        secondaryDemand.complete(Unit)
        presented.complete(Unit)
    }

    suspend fun awaitQuietPeriod(milliseconds: Long) {
        if (!secondaryRequested) withTimeoutOrNull(milliseconds) { secondaryDemand.await() }
    }

    suspend fun awaitPresentation() {
        // A stopped/suppressed screen must not deadlock metadata or a play-on-load handoff.
        withTimeoutOrNull(750L) { presented.await() }
    }
}
