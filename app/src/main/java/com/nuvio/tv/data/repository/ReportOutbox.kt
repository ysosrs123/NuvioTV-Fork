package com.nuvio.tv.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import com.nuvio.tv.core.build.IssueReports
import com.nuvio.tv.data.remote.api.ReportRelayApi
import com.nuvio.tv.data.remote.dto.PlaybackIssueReportResponseDto
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response

internal enum class ReportRoute(val key: String, val path: String) {
    Playback("playback", "api/playback-reports"),
    App("app", "api/app-reports")
}

internal sealed interface ReportUpload {
    data class Accepted(val id: String, val followUrl: String?) : ReportUpload
    data class Refused(val code: Int) : ReportUpload
    data class Unreachable(val reason: String) : ReportUpload
}

internal fun reportUploadResult(code: Int, successful: Boolean, id: String?, followUrl: String?): ReportUpload = when {
    successful && !id.isNullOrBlank() -> ReportUpload.Accepted(id.trim(), followUrl)
    code in 500..599 || code == 408 || code == 425 -> ReportUpload.Unreachable("HTTP $code")
    else -> ReportUpload.Refused(code)
}

internal fun Response<PlaybackIssueReportResponseDto>.toReportUpload(): ReportUpload {
    val reply = body()
    return reportUploadResult(code(), isSuccessful, reply?.reportId?.takeIf { it.isNotBlank() } ?: reply?.id, reply?.followUrl)
}

internal data class WaitingReport(val file: File, val route: ReportRoute, val kind: String, val savedAtMs: Long)

/** Reports the viewer pressed Send on, kept cleaned and compressed while the relay cannot be reached. */
internal class ReportOutboxFiles(
    private val dir: File,
    private val freeBytes: () -> Long,
    private val now: () -> Long = System::currentTimeMillis
) {
    @Synchronized
    fun save(route: ReportRoute, kind: String, json: ByteArray): Boolean {
        prune()
        if (freeBytes() < MIN_FREE_BYTES) return false
        val packed = gzip(json)
        val waiting = pending()
        if (packed.size > MAX_REPORT_BYTES || waiting.size >= MAX_REPORTS) return false
        if (waiting.sumOf { it.file.length() } + packed.size > MAX_TOTAL_BYTES) return false
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val name = "${now()}-${UUID.randomUUID().toString().take(8)}.${route.key}.$kind.gz"
        val part = File(dir, "$name.part")
        return runCatching {
            part.writeBytes(packed)
            part.renameTo(File(dir, name))
        }.getOrDefault(false).also { saved -> if (!saved) part.delete() }
    }

    @Synchronized
    fun prune() {
        val cutoff = now() - MAX_AGE_MS
        dir.listFiles().orEmpty().forEach { file ->
            val report = parse(file)
            if (report == null || report.savedAtMs < cutoff) file.delete()
        }
    }

    @Synchronized
    fun pending(): List<WaitingReport> = dir.listFiles().orEmpty().mapNotNull(::parse).sortedBy { it.savedAtMs }

    fun read(report: WaitingReport): ByteArray? =
        runCatching { report.file.inputStream().use { GZIPInputStream(it).readBytes() } }.getOrNull()

    fun delete(report: WaitingReport) {
        report.file.delete()
    }

    private fun parse(file: File): WaitingReport? {
        val parts = NAME.matchEntire(file.name)?.groupValues ?: return null
        val route = ReportRoute.entries.firstOrNull { it.key == parts[2] } ?: return null
        return WaitingReport(file, route, parts[3], parts[1].toLongOrNull() ?: return null)
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size / 4 + 64)
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    companion object {
        const val MAX_REPORTS = 3
        const val MAX_REPORT_BYTES = 1_400_000L
        const val MAX_TOTAL_BYTES = 3_145_728L
        const val MIN_FREE_BYTES = 300L * 1024 * 1024
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        private val NAME = Regex("""(\d+)-[0-9a-f]+\.([a-z]+)\.([a-z_]+)\.gz""")
    }
}

internal class ReportRetrySchedule(
    private val delaysMs: List<Long> = listOf(60_000L, 300_000L, 900_000L, 3_600_000L),
    val networkGapMs: Long = 30_000L
) {
    private var failures = 0
    private var lastTryMs: Long? = null

    @Synchronized
    fun tried(nowMs: Long) {
        lastTryMs = nowMs
    }

    @Synchronized
    fun mayTryOnNetworkBack(nowMs: Long): Boolean = lastTryMs?.let { nowMs - it >= networkGapMs } ?: true

    @Synchronized
    fun failed(): Long = delaysMs[failures.coerceAtMost(delaysMs.lastIndex)].also { failures += 1 }

    @Synchronized
    fun succeeded() {
        failures = 0
    }
}

/** Sends what is waiting, oldest first. A refused report is dropped and never retried. True means the relay stayed out of reach. */
internal suspend fun sendWaitingReports(
    files: ReportOutboxFiles,
    upload: suspend (WaitingReport, ByteArray) -> ReportUpload,
    onAccepted: suspend (WaitingReport, ReportUpload.Accepted) -> Unit
): Boolean {
    files.prune()
    for (report in files.pending()) {
        val json = files.read(report)
        if (json == null) {
            files.delete(report)
            continue
        }
        when (val result = upload(report, json)) {
            is ReportUpload.Accepted -> {
                files.delete(report)
                onAccepted(report, result)
            }
            is ReportUpload.Refused -> if (result.code == 429) return true else files.delete(report)
            is ReportUpload.Unreachable -> return true
        }
    }
    return false
}

@Singleton
class ReportOutbox @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: Lazy<ReportRelayApi>,
    private val sentReports: SentReportsStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val files by lazy {
        ReportOutboxFiles(File(context.cacheDir, "waiting_reports"), freeBytes = { context.cacheDir.usableSpace })
    }
    private val schedule = ReportRetrySchedule()
    private val networkBack = Channel<Unit>(Channel.CONFLATED)
    private var worker: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        if (!IssueReports.available) return
        scope.launch {
            delay(START_DELAY_MS)
            files.prune()
            ensureWorker(firstWaitMs = 0L)
        }
    }

    internal suspend fun deliver(route: ReportRoute, kind: String, json: ByteArray): PlaybackIssueReceipt =
        withContext(Dispatchers.IO) {
            when (val result = upload(route, json)) {
                is ReportUpload.Accepted -> {
                    runCatching { sentReports.add(result.id, kind) }
                    PlaybackIssueReceipt(result.id, result.followUrl)
                }
                is ReportUpload.Refused -> error("Report upload failed: HTTP ${result.code}")
                is ReportUpload.Unreachable -> {
                    schedule.tried(SystemClock.elapsedRealtime())
                    if (!files.save(route, kind, json)) error("Report upload failed: ${result.reason}")
                    ensureWorker(firstWaitMs = schedule.failed())
                    PlaybackIssueReceipt("", null, saved = true)
                }
            }
        }

    private suspend fun upload(route: ReportRoute, json: ByteArray): ReportUpload = try {
        api.get().upload(route.path, json.toRequestBody(JSON)).toReportUpload()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ReportUpload.Unreachable(e.javaClass.simpleName)
    }

    @Synchronized
    private fun ensureWorker(firstWaitMs: Long) {
        if (worker?.isActive == true || files.pending().isEmpty()) return
        watchNetwork()
        worker = scope.launch { retryUntilEmpty(firstWaitMs) }
    }

    private suspend fun retryUntilEmpty(firstWaitMs: Long) {
        var waitMs = firstWaitMs
        while (true) {
            if (waitMs > 0L) {
                val woken = withTimeoutOrNull(waitMs) { networkBack.receive() } != null
                if (woken && !schedule.mayTryOnNetworkBack(SystemClock.elapsedRealtime())) {
                    waitMs = schedule.networkGapMs
                    continue
                }
            }
            schedule.tried(SystemClock.elapsedRealtime())
            val unreachable = sendWaitingReports(
                files,
                upload = { report, json -> upload(report.route, json) },
                onAccepted = { report, accepted ->
                    schedule.succeeded()
                    runCatching { sentReports.add(accepted.id, report.kind) }
                }
            )
            if (unreachable) {
                waitMs = schedule.failed()
            } else if (finishIfEmpty()) {
                return
            } else {
                waitMs = 0L
            }
        }
    }

    @Synchronized
    private fun finishIfEmpty(): Boolean {
        if (files.pending().isNotEmpty()) return false
        stopWatchingNetwork()
        worker = null
        return true
    }

    private fun watchNetwork() {
        if (networkCallback != null) return
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                networkBack.trySend(Unit)
            }
        }
        if (runCatching { connectivity.registerDefaultNetworkCallback(callback) }.isSuccess) networkCallback = callback
    }

    private fun stopWatchingNetwork() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching { context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
    }

    private companion object {
        const val START_DELAY_MS = 20_000L
        val JSON = "application/json; charset=UTF-8".toMediaType()
    }
}
