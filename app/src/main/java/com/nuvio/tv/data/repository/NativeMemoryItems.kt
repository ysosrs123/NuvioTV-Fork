package com.nuvio.tv.data.repository

import android.content.Context
import coil3.imageLoader

/**
 * What Arc itself holds outside the Java heap, item by item, for the once-a-minute snapshot: the native heap line
 * alone cannot say whether pieces, the buffer, pictures or stray loaders filled it. Only figures Arc already keeps
 * or can count cheaply; "?" where one could not be read.
 */
internal object NativeMemoryItems {
    private const val LOADER = "ExoPlayer:Loader"
    private const val DISPATCHER = "DefaultDispatcher-worker"
    private const val PLAYBACK = "ExoPlayer:Playback"
    private const val RANGE_WORKER = "parallel-ds-worker"

    class Items(
        val rangeHeldBytes: Long?,
        val rangePieces: Long?,
        val rangeIdleBytes: Long?,
        val bufferBytes: Long?,
        val pictureBytes: Long?,
        val libassPlayers: Int?,
        val pluginRuntime: Boolean?,
        val loaderThreads: Int,
        val dispatcherWorkers: Int,
        val playbackThreads: Int,
        val rangeWorkers: Int,
        val openFiles: Int?
    )

    fun line(context: Context, threadNames: List<String>): String {
        val range = runCatching { com.nuvio.tv.ui.screens.player.ParallelRangeDataSource.heldForReport() }.getOrNull()
        return format(
            Items(
                rangeHeldBytes = range?.get(0),
                rangePieces = range?.get(1),
                rangeIdleBytes = range?.get(2),
                bufferBytes = runCatching { com.nuvio.tv.core.player.PlaybackMemoryFacts.bufferHeldBytes() }.getOrNull(),
                pictureBytes = runCatching { context.imageLoader.memoryCache?.size }.getOrNull(),
                libassPlayers = runCatching { com.nuvio.tv.ui.screens.player.libassPlayersAlive() }.getOrNull(),
                pluginRuntime = runCatching { com.nuvio.tv.core.runtime.PluginRuntimeHooks.started }.getOrNull(),
                loaderThreads = count(threadNames, LOADER),
                dispatcherWorkers = count(threadNames, DISPATCHER),
                playbackThreads = count(threadNames, PLAYBACK),
                rangeWorkers = count(threadNames, RANGE_WORKER),
                openFiles = runCatching { OpenFilesGuard.count() }.getOrNull()?.takeIf { it > 0 }
            )
        )
    }

    fun count(names: List<String>, prefix: String): Int = names.count { it.startsWith(prefix) }

    fun format(items: Items): String = buildList {
        add("range pieces ${mb(items.rangeHeldBytes)} MB (${items.rangePieces ?: "?"})")
        add("range pool ${mb(items.rangeIdleBytes)} MB")
        add("player buffer ${if (items.bufferBytes == null) "none" else "${mb(items.bufferBytes)} MB"}")
        add("pictures ${mb(items.pictureBytes)} MB")
        add("libass ${items.libassPlayers ?: "?"}")
        add("plugin runtime ${when (items.pluginRuntime) { true -> "on"; false -> "off"; null -> "?" }}")
        add("loader threads ${items.loaderThreads}")
        add("dispatcher workers ${items.dispatcherWorkers}")
        add("playback threads ${items.playbackThreads}")
        add("range workers ${items.rangeWorkers}")
        add("open files ${items.openFiles ?: "?"}")
    }.joinToString(", ", prefix = "Native memory items: ")

    private fun mb(bytes: Long?): String = bytes?.let { (it shr 20).toString() } ?: "?"
}
