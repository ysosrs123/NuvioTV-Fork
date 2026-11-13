package com.nuvio.tv.ui.screens.stream

import android.app.ActivityManager
import android.content.Context
import com.nuvio.tv.core.debrid.StreamTextSizeParser
import com.nuvio.tv.domain.model.Stream

internal enum class LargeFileKind { Uhd, Other }

/**
 * A polite line on the source card when a box with little memory is offered a very large file. Never blocks: the
 * viewer can still pick the source.
 */
internal object LargeFileNote {
    const val SMALL_BOX_BYTES = 1536L * 1024L * 1024L
    const val BITRATE_EDGE_BPS = 40_000_000L

    fun isSmallBox(totalMemBytes: Long): Boolean = totalMemBytes in 1 until SMALL_BOX_BYTES

    fun isSmallBox(context: Context): Boolean = runCatching {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        isSmallBox(info.totalMem)
    }.getOrDefault(false)

    fun bitsPerSecond(sizeBytes: Long?, runtimeMinutes: Int?): Long? {
        if (sizeBytes == null || sizeBytes <= 0L || runtimeMinutes == null || runtimeMinutes <= 0) return null
        return sizeBytes * 8L / (runtimeMinutes * 60L)
    }

    fun kindFor(texts: List<String?>, sizeBytes: Long?, runtimeMinutes: Int?): LargeFileKind? {
        val bps = bitsPerSecond(sizeBytes, runtimeMinutes) ?: return null
        if (bps <= BITRATE_EDGE_BPS) return null
        return if (texts.any { it != null && UHD.containsMatchIn(it) }) LargeFileKind.Uhd else LargeFileKind.Other
    }

    fun kindFor(stream: Stream, runtimeMinutes: Int?): LargeFileKind? =
        kindFor(
            listOf(stream.name, stream.title, stream.description),
            StreamTextSizeParser.effectiveSizeBytes(stream),
            runtimeMinutes
        )

    private val UHD = Regex("(?i)(?<![a-z0-9])(2160p|4k|uhd)(?![a-z0-9])")
}
