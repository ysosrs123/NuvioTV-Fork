package com.nuvio.tv.core.util

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide signal asking the Home screen to reload its catalogues with
 * forceReload semantics. Emitted by Settings after destructive cache
 * operations (clear image/catalogue caches) so the cleared layers are
 * repopulated with fresh network data without an app restart.
 *
 * tryEmit + extraBufferCapacity(1) keeps emission non-suspending from UI
 * callers; a second request while one is pending coalesces, which is the
 * desired behaviour for a refresh signal.
 */
@Singleton
class HomeRefreshSignal @Inject constructor() {
    private val _events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val events: SharedFlow<Unit> = _events.asSharedFlow()

    fun requestRefresh() {
        _events.tryEmit(Unit)
    }
}
