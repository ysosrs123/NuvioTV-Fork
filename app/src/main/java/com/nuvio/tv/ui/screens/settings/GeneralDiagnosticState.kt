package com.nuvio.tv.ui.screens.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nuvio.tv.R
import com.nuvio.tv.core.network.BoundedNetworkSpeedTest
import com.nuvio.tv.core.network.GeneralNetworkSpeedTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import java.text.DateFormat
import java.util.Date

internal enum class NetworkTestState { Idle, TestingLatency, TestingDownload, Done, Error }

internal class GeneralDiagnosticState {
    val run = DiagnosticUiRun()
    var status by mutableStateOf(NetworkTestState.Idle)
    var latencyMs by mutableStateOf<Long?>(null)
    var downloadMbps by mutableStateOf<Double?>(null)
    var errorMessage by mutableStateOf<String?>(null)
    var resultScope by mutableStateOf<String?>(null)
    private var network: Any? = null
    private fun clear() {
        status = NetworkTestState.Idle; latencyMs = null; downloadMbps = null
        errorMessage = null; resultScope = null; network = null
    }
    fun cancel() { run.cancel(); clear() }
    fun invalidateNetwork(context: Context, currentNetwork: Any?) {
        if (network == null || network == currentNetwork) return
        cancel(); status = NetworkTestState.Error
        errorMessage = context.getString(R.string.network_test_network_changed)
    }
    fun start(scope: CoroutineScope, context: Context, identity: () -> Any?, label: String) {
        if (run.running) return
        clear(); network = identity()
        if (network == null) {
            status = NetworkTestState.Error; errorMessage = context.getString(R.string.network_test_route_unavailable); return
        }
        val observedNetwork = network
        status = NetworkTestState.TestingLatency
        run.start(scope, onFinished = { if (status != NetworkTestState.Done && status != NetworkTestState.Error) clear() }) execute@{ current ->
            try {
                val result = GeneralNetworkSpeedTest.run(
                    onLatency = { if (current()) latencyMs = it },
                    onDownloading = { if (current()) status = NetworkTestState.TestingDownload })
                if (!current()) return@execute
                val finishedNetwork = identity()
                if (observedNetwork != finishedNetwork) {
                    invalidateNetwork(context, finishedNetwork); return@execute
                }
                val failure = result.failure
                if (failure != null) {
                    latencyMs = null
                    errorMessage = context.getString(when (failure) {
                        BoundedNetworkSpeedTest.Failure.BUSY -> R.string.network_test_busy
                        BoundedNetworkSpeedTest.Failure.PLAYBACK_ACTIVE -> R.string.network_test_playback_active
                        BoundedNetworkSpeedTest.Failure.TIMED_OUT -> R.string.network_test_timed_out
                        else -> R.string.network_test_unavailable
                    })
                    status = NetworkTestState.Error
                } else {
                    downloadMbps = result.mbps
                    resultScope = context.getString(R.string.network_test_result_scope, label,
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date()),
                        result.servingHosts.sorted().joinToString(", ")) +
                        if (result.failedWorkers > 0) " " + context.getString(R.string.network_test_partial) else ""
                    status = NetworkTestState.Done
                }
            } catch (cancelled: CancellationException) {
                if (current()) clear()
                throw cancelled
            } finally {
                if (current() && status != NetworkTestState.Done && status != NetworkTestState.Error) clear()
            }
        }
    }
}
