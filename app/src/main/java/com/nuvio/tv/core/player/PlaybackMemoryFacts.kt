@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.core.player

import android.os.SystemClock
import androidx.media3.exoplayer.upstream.DefaultAllocator
import java.util.concurrent.atomic.AtomicReference

/**
 * What the player holds, for the once-a-minute snapshot a low-memory exit report reads. The player writes it when it is
 * built and when a format, decoder or audio output changes; the snapshot thread reads it. Formats only, never a title,
 * link or file name.
 */
object PlaybackMemoryFacts {
    data class Player(
        val engine: String,
        val nativeMemory: Boolean = false,
        /** automatic, custom or stock. */
        val buffer: String? = null,
        val video: String? = null,
        val videoDecoder: String? = null,
        val audio: String? = null,
        val audioOut: String? = null,
        val mpvDecode: String? = null,
        val keeper: BufferMemoryKeeper? = null,
        val allocator: DefaultAllocator? = null,
    )

    data class Buffer(
        val budgetMb: Long,
        val targetMb: Long,
        val heldMb: Long,
        val footprintMb: Long,
        val pressure: Int,
        val pressureForS: Long,
        val grownMb: Long,
        val lentMb: Long,
    )

    private val state = AtomicReference<Player?>(null)

    fun onExoBuilt(nativeMemory: Boolean, buffer: String, allocator: DefaultAllocator?) {
        val keeper = state.get()?.keeper?.takeIf { it.control.bufferAllocator === allocator }
        state.set(Player("ExoPlayer", nativeMemory, buffer, keeper = keeper, allocator = allocator))
    }

    fun onMpvStarted(decodeMode: String?) {
        state.set(Player("MPV", mpvDecode = decodeMode))
    }

    fun onKeeper(keeper: BufferMemoryKeeper?) = update { it.copy(keeper = keeper) }

    fun onVideoFormat(mime: String?, width: Int, height: Int) = update {
        it.copy(video = codec(mime) + if (width > 0 && height > 0) " ${width}x$height" else "")
    }

    fun onVideoDecoder(name: String, software: Boolean) = update { it.copy(videoDecoder = videoDecoderLabel(name, software)) }

    fun onAudioFormat(mime: String?) = update { it.copy(audio = codec(mime)) }

    /** A decoder only starts when the audio is decoded here; passthrough never starts one. */
    fun onAudioDecoder(name: String) = update {
        it.copy(audioOut = if (name.lowercase().contains("ffmpeg")) "ffmpeg" else "decoded")
    }

    /** [bitstream] names what reaches the receiver undecoded (passthrough, ac3 transcode); null is PCM. */
    fun onAudioOutput(bitstream: String?) = update {
        it.copy(audioOut = bitstream ?: it.audioOut?.takeIf { out -> out == "ffmpeg" || out == "decoded" } ?: "decoded")
    }

    fun clear() = state.set(null)

    /** Bytes the ExoPlayer buffer holds now, or null without one. */
    fun bufferHeldBytes(): Long? {
        val player = state.get() ?: return null
        val allocator = player.allocator ?: player.keeper?.control?.bufferAllocator ?: return null
        return runCatching { allocator.totalBytesAllocated.toLong() }.getOrNull()
    }

    /** The snapshot lines while a film plays; a few volatile reads and two allocator getters, nothing on the player. */
    fun lines(): List<String> {
        val player = state.get() ?: return emptyList()
        val keeper = player.keeper
        val allocator = player.allocator ?: keeper?.control?.bufferAllocator
        val buffer = if (player.engine != "ExoPlayer" || allocator == null) null else runCatching {
            val control = keeper?.control
            val changedAt = keeper?.levelChangedAtMs ?: 0L
            val (held, footprint) = synchronized(allocator) {
                allocator.totalBytesAllocated.toLong() to allocator.memoryFootprint.toLong()
            }
            Buffer(
                budgetMb = control?.uncappedBudgetBytes?.let { it / MB } ?: -1L,
                targetMb = control?.allocatorTargetBytes()?.let { it / MB } ?: -1L,
                heldMb = held / MB,
                footprintMb = footprint / MB,
                pressure = keeper?.pressureLevel ?: -1,
                pressureForS = if (changedAt > 0L) (SystemClock.elapsedRealtime() - changedAt) / 1000L else -1L,
                grownMb = (keeper?.grownBytes ?: 0L) / MB,
                lentMb = (keeper?.lentBytes ?: 0L) / MB,
            )
        }.getOrNull()
        return format(player, buffer)
    }

    internal fun format(player: Player, buffer: Buffer?): List<String> {
        val first = buildList {
            if (player.engine == "MPV") add("MPV" + (player.mpvDecode?.let { " (hwdec $it)" } ?: ""))
            else add("ExoPlayer, native memory ${if (player.nativeMemory) "on" else "off"}")
            player.video?.let { add("video $it" + (player.videoDecoder?.let { d -> " $d" } ?: "")) }
            player.audio?.let { add("audio $it" + (player.audioOut?.let { o -> " $o" } ?: "")) }
        }.joinToString(", ")
        val second = buffer?.let { b ->
            buildList {
                add(player.buffer ?: "unknown")
                if (b.budgetMb >= 0L) add("budget ${b.budgetMb} MB" + if (b.grownMb > 0L) " (grown ${b.grownMb})" else "")
                if (b.targetMb >= 0L && b.targetMb != b.budgetMb) add("target ${b.targetMb} MB")
                add("held ${b.heldMb} of ${b.footprintMb} MB")
                if (b.lentMb > 0L) add("lent ${b.lentMb} MB")
                if (b.pressure >= 0) add("pressure ${pressureName(b.pressure)}" +
                    if (b.pressureForS >= 0L) " for ${b.pressureForS} s" else "")
            }.joinToString(", ")
        }
        return listOfNotNull("Player: $first", second?.let { "Buffer: $it" })
    }

    internal fun videoDecoderLabel(name: String, software: Boolean): String {
        val lower = name.lowercase()
        return when {
            lower.contains("dav1d") -> "sw dav1d"
            lower.contains("ffmpeg") -> "sw ffmpeg"
            software -> "sw"
            else -> "hw"
        }
    }

    internal fun codec(mime: String?): String =
        mime?.substringAfter('/')?.removePrefix("x-")?.takeIf { it.isNotBlank() } ?: "unknown"

    internal fun pressureName(level: Int): String = when (level) {
        BufferMemoryKeeper.LEVEL_LOW -> "LOW"
        BufferMemoryKeeper.LEVEL_CRITICAL -> "CRITICAL"
        else -> "NONE"
    }

    private inline fun update(crossinline change: (Player) -> Player) {
        state.updateAndGet { current -> current?.let(change) }
    }

    private const val MB = 1024L * 1024L
}
