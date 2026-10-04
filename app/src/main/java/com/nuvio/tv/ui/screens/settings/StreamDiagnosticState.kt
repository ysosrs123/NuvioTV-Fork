package com.nuvio.tv.ui.screens.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.R
import com.nuvio.tv.core.network.DiagnosticRunCoordinator
import com.nuvio.tv.core.network.displayMessage
import com.nuvio.tv.core.network.BoundedStreamSpeedTest
import com.nuvio.tv.core.network.StreamSpeedTester
import com.nuvio.tv.core.network.StreamSweepEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import java.text.DateFormat
import java.util.Date

enum class StreamDiagnosticMode { QUICK, ADVANCED }

@UnstableApi
internal class StreamDiagnosticState {
    val run = DiagnosticUiRun()
    var mode by mutableStateOf(StreamDiagnosticMode.QUICK)
    var status by mutableStateOf("Idle")
    var rows by mutableStateOf(listOf<Pair<String, Double?>>())
    var notes by mutableStateOf(mapOf<String, StreamSweepEngine.PassNote>())
    var error by mutableStateOf<String?>(null)
    var verdict by mutableStateOf<String?>(null)
    var endpoint by mutableStateOf<String?>(null)
    var resultScope by mutableStateOf<String?>(null)
    var bitrate by mutableStateOf<Long?>(null)
    var bitrateIsMux by mutableStateOf(false)

    private var networkObservation: Any? = null
    private fun clear() {
        networkObservation = null
        status = "Idle"; rows = emptyList(); notes = emptyMap()
        error = null; verdict = null; endpoint = null; resultScope = null; bitrate = null; bitrateIsMux = false
    }
    fun cancel() { run.cancel(); clear() }
    fun invalidateNetwork(context: Context, identity: Any?) {
        if (networkObservation == null || networkObservation == identity) return
        cancel(); error = context.getString(R.string.network_test_network_changed); status = "Error"
    }

    fun start(
        scope: CoroutineScope, context: Context, selectedMode: StreamDiagnosticMode,
        url: String, headers: Map<String, String>, estimatedBitrate: Long?,
        networkIdentity: () -> Any?, networkLabel: String,
        now: () -> Long = System::currentTimeMillis, durationMs: Long = 0
    ) {
        if (run.running || url.isBlank()) return
        // Snapshot caller-owned inputs; editing diagnostics during a run cannot change its source.
        val requestHeaders = headers.toMap()
        val startNetwork = networkIdentity()
        clear(); mode = selectedMode; status = "Preparing"; bitrate = estimatedBitrate
        networkObservation = startNetwork
        if (startNetwork == null) {
            error = context.getString(R.string.network_test_route_unavailable); status = "Error"; return
        }
        run.start(scope, onFinished = { if (status != "Done" && status != "Error") clear() }) execute@{ current ->
            try {
                if (selectedMode == StreamDiagnosticMode.QUICK) {
                    val label = context.getString(R.string.stream_test_label_baseline)
                    status = label; rows = listOf(label to null)
                    val result = StreamSpeedTester.runBaselineTest(url, requestHeaders)
                    if (!current()) return@execute
                    val failure = result.failure
                    error = if (failure == null && result.mbps?.let { it.isFinite() && it > 0 } == true) null
                        else context.getString(when (failure) {
                            BoundedStreamSpeedTest.Failure.BUSY -> R.string.network_test_busy
                            BoundedStreamSpeedTest.Failure.PLAYBACK_ACTIVE -> R.string.network_test_playback_active
                            BoundedStreamSpeedTest.Failure.TIMED_OUT -> R.string.network_test_timed_out
                            else -> R.string.stream_test_baseline_unavailable
                        })
                    if (error == null) {
                        endpoint = result.servingEndpoint
                        rows = listOf(label to result.mbps)
                        notes = mapOf(label to StreamSweepEngine.PassNote("", context.getString(
                            R.string.stream_test_baseline_scope, result.servingEndpoint.orEmpty(),
                            "%.1f".format(result.measuredBytes / (1024.0 * 1024)),
                            "%.2f".format(result.measuredNanos / 1_000_000_000.0),
                            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now())))))
                    }
                    // A quick sample never selects or recommends a playback configuration.
                } else {
                    val outcome = DiagnosticRunCoordinator.shared.run(DiagnosticRunCoordinator.COMPARISON) {
                        if (bitrate == null && durationMs > 0) {
                            val size = StreamSpeedTester.getStreamContentLength(url, requestHeaders)
                            if (current() && size > 0) {
                                bitrate = (size * 8_000.0 / durationMs).toLong()
                                bitrateIsMux = true
                            }
                        }
                        StreamSweepEngine.run(context, url, requestHeaders, bitrate,
                        onState = { if (current()) status = it },
                        onPassAdded = { if (current()) rows = rows + (it to null) },
                        onPassResult = { label, mbps, note -> if (current()) {
                            rows = rows.map { if (it.first == label) label to mbps else it }
                            if (note != null) notes = notes + (label to note)
                        } },
                        onBaselineEndpoint = { if (current()) endpoint = it })
                    }
                    if (!current()) return@execute
                    error = outcome.errorText; verdict = outcome.verdictText
                }
                if (startNetwork == null || startNetwork != networkIdentity()) {
                    rows = emptyList(); notes = emptyMap(); endpoint = null; verdict = null
                    error = context.getString(R.string.network_test_network_changed)
                } else {
                    resultScope = context.getString(R.string.stream_diagnostic_observation, networkLabel,
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(now())))
                }
                status = if (error == null) "Done" else "Error"
            } catch (unavailable: DiagnosticRunCoordinator.Unavailable) {
                if (current()) { error = unavailable.displayMessage(context); verdict = null; status = "Error" }
            } catch (cancelled: CancellationException) {
                // Scope disposal/playback cancellation can happen without the explicit Cancel action.
                if (current()) clear()
                throw cancelled
            } finally {
                if (current() && status != "Done" && status != "Error") clear()
            }
        }
    }
}
