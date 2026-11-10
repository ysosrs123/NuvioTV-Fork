package com.nuvio.tv.data.repository

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.build.IssueReports
import com.nuvio.tv.data.remote.api.AuthDiagnosticReportApi
import com.nuvio.tv.data.remote.dto.AuthDiagnosticReportRequestDto
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthDiagnosticReportRepository internal constructor(
    private val authDiagnosticReportApi: AuthDiagnosticReportApi,
    private val queue: AuthDiagnosticReportQueue,
    private val endpointConfigured: Boolean
) {
    @Inject
    constructor(
        authDiagnosticReportApi: AuthDiagnosticReportApi,
        moshi: Moshi,
        @ApplicationContext context: Context
    ) : this(
        authDiagnosticReportApi = authDiagnosticReportApi,
        queue = AuthDiagnosticReportQueue(
            queueFile = File(context.filesDir, "auth_diagnostics_queue.jsonl"),
            moshi = moshi
        ),
        endpointConfigured = IssueReports.available
    )

    private val mutex = Mutex()

    suspend fun submit(report: AuthDiagnosticReportRequestDto): Result<String> = mutex.withLock {
        if (!endpointConfigured) {
            queue.write(emptyList())
            return@withLock Result.failure(IllegalStateException("Auth diagnostics endpoint is not configured"))
        }
        drainQueuedLocked()
        return@withLock try {
            Result.success(upload(report))
        } catch (e: Exception) {
            enqueueLocked(report)
            Result.failure(e)
        }
    }

    private suspend fun drainQueuedLocked() {
        val queued = queue.read()
        if (queued.isEmpty()) return
        val remaining = mutableListOf<AuthDiagnosticReportRequestDto>()
        var blocked = false
        queued.forEach { report ->
            if (blocked) {
                remaining += report
            } else {
                try {
                    upload(report)
                } catch (_: Exception) {
                    remaining += report
                    blocked = true
                }
            }
        }
        queue.write(remaining)
    }

    private suspend fun upload(report: AuthDiagnosticReportRequestDto): String {
        val response = authDiagnosticReportApi.createAuthDiagnosticReport(report.forUpload())
        if (!response.isSuccessful) {
            error("Auth diagnostics upload failed: HTTP ${response.code()}")
        }
        val body = response.body()
        return body?.reportId?.trim()?.takeIf { it.isNotBlank() }
            ?: body?.id?.trim()?.takeIf { it.isNotBlank() }
            ?: error("Auth diagnostics upload failed: missing report id")
    }

    private suspend fun enqueueLocked(report: AuthDiagnosticReportRequestDto) {
        queue.enqueue(report)
    }
}

class AuthDiagnosticReportQueue(
    private val queueFile: File,
    moshi: Moshi,
    private val maxReports: Int = 50
) {
    private val adapter = moshi.adapter(AuthDiagnosticReportRequestDto::class.java)

    suspend fun enqueue(report: AuthDiagnosticReportRequestDto) {
        write(read() + report)
    }

    suspend fun read(): List<AuthDiagnosticReportRequestDto> = withContext(Dispatchers.IO) {
        val lines = try {
            if (!queueFile.exists()) return@withContext emptyList()
            queueFile.readLines()
        } catch (e: IOException) {
            Log.w(TAG, "Auth diagnostics queue unreadable: ${e.message}")
            return@withContext emptyList()
        } catch (e: SecurityException) {
            Log.w(TAG, "Auth diagnostics queue unreadable: ${e.message}")
            return@withContext emptyList()
        }
        lines.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line -> runCatching { adapter.fromJson(line) }.getOrNull() }
            .toList()
    }

    suspend fun write(reports: List<AuthDiagnosticReportRequestDto>) {
        withContext(Dispatchers.IO) {
            try {
                val bounded = reports.takeLast(maxReports)
                if (bounded.isEmpty()) {
                    if (queueFile.exists()) queueFile.delete()
                    return@withContext
                }
                queueFile.parentFile?.mkdirs()
                queueFile.writeText(bounded.joinToString(separator = "\n") { adapter.toJson(it) } + "\n")
            } catch (e: IOException) {
                Log.w(TAG, "Auth diagnostics queue not saved: ${e.message}")
            } catch (e: SecurityException) {
                Log.w(TAG, "Auth diagnostics queue not saved: ${e.message}")
            }
        }
    }

    private companion object {
        const val TAG = "AuthDiagnosticsQueue"
    }
}
