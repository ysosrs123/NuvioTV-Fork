package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.BuildConfig
import java.io.File

/**
 * Which build ran from when. A crash or freeze report is sent by the next start, which after an update is a newer
 * build than the one that ended, so the report looks the ended build up here.
 */
object ArcBuildLog {
    private const val FILE_NAME = "arc-builds.txt"
    private const val KEPT = 6

    data class Entry(val sinceMs: Long, val versionCode: Long, val commit: String, val versionName: String) {
        val label: String get() = "$versionName ($versionCode)"
    }

    @Volatile private var file: File? = null

    fun start(context: Context) {
        if (file != null) return
        val target = File(context.noBackupFilesDir, FILE_NAME)
        file = target
        runCatching {
            note(
                target,
                Entry(System.currentTimeMillis(), BuildConfig.VERSION_CODE.toLong(), BuildConfig.GIT_COMMIT, BuildConfig.VERSION_NAME)
            )
        }
    }

    /** The build that was running at [exitMs], or null when that was before this log began. */
    fun at(exitMs: Long): Entry? = file?.let { at(it, exitMs) }

    internal fun at(target: File, exitMs: Long): Entry? = read(target).lastOrNull { it.sinceMs <= exitMs }

    internal fun note(target: File, now: Entry) {
        val entries = read(target)
        val last = entries.lastOrNull()
        if (last != null && last.versionCode == now.versionCode && last.commit == now.commit) return
        val temp = File(target.parentFile, "$FILE_NAME.tmp")
        temp.writeText((entries + now).takeLast(KEPT).joinToString("\n") {
            "${it.sinceMs}\t${it.versionCode}\t${it.commit}\t${it.versionName}"
        })
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    private fun read(target: File): List<Entry> =
        runCatching { if (target.exists()) target.readLines() else emptyList() }.getOrDefault(emptyList())
            .mapNotNull { line ->
                val parts = line.split('\t', limit = 4)
                val since = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                val code = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
                Entry(since, code, parts.getOrNull(2).orEmpty(), parts.getOrNull(3).orEmpty())
            }
}
