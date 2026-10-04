package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Owns UI callbacks separately from the transport's retained cleanup admission. */
internal class DiagnosticUiRun {
    var running by mutableStateOf(false)
        private set
    private var generation = 0L
    private var job: Job? = null

    fun start(scope: CoroutineScope, onFinished: () -> Unit = {}, block: suspend (() -> Boolean) -> Unit): Boolean {
        if (running) return false
        val token = ++generation
        running = true // Claim before dispatch, including rapid repeated remote clicks.
        val next = scope.launch(start = CoroutineStart.LAZY) {
            block { generation == token && job?.isActive == true }
        }
        job = next
        next.invokeOnCompletion {
            if (generation == token) { job = null; running = false; onFinished() }
        }
        next.start()
        return true
    }

    fun cancel() {
        ++generation // Late callbacks/finally blocks cannot mutate a replacement run.
        val old = job
        job = null
        running = false
        old?.cancel()
    }
}
