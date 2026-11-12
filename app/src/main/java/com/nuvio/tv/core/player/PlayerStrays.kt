package com.nuvio.tv.core.player

import android.util.Log
import kotlinx.coroutines.delay

/**
 * A released player whose playback thread has not finished yet still holds its decoder and its whole buffer.
 * release() stops waiting after its timeout, so a box that is slow or stuck can collect several of them when
 * sources fail one after another. The next player waits a moment for them, and the crash snapshot counts them.
 */
object PlayerStrays {
    private const val TAG = "PlayerStrays"
    private const val PLAYBACK_THREAD = "ExoPlayer:Playback"
    private const val DECODER_THREAD = "ExoPlayer:MediaCodecAsyncAdapter"

    /** The trailer player and the intro sound can each be alive beside the main player. */
    const val ALLOWED_BESIDE_MAIN = 2
    const val MAX_WAIT_MS = 4_000L
    private const val POLL_MS = 100L
    private const val FRAMES_SHOWN = 4

    fun playbackThreads(): Int = count(threadNames(), PLAYBACK_THREAD)

    /**
     * Before a player is built: waits until earlier players have finished closing, at most [MAX_WAIT_MS].
     * Returns the time waited.
     */
    suspend fun awaitGone(allowed: Int = ALLOWED_BESIDE_MAIN, maxWaitMs: Long = MAX_WAIT_MS): Long {
        var waited = 0L
        while (waited < maxWaitMs && playbackThreads() > allowed) {
            delay(POLL_MS)
            waited += POLL_MS
        }
        if (waited > 0L) Log.i(TAG, "PLAYER_STRAYS waitedMs=$waited left=${playbackThreads()} allowed=$allowed")
        return waited
    }

    /** One line for the crash snapshot when more players are alive than there should be, else null. */
    fun line(): String? = runCatching {
        val threads = Thread.getAllStackTraces()
        val playback = threads.filterKeys { it.name.startsWith(PLAYBACK_THREAD) }
        if (playback.size <= ALLOWED_BESIDE_MAIN + 1) return@runCatching null
        val decoders = threads.keys.count { it.name.startsWith(DECODER_THREAD) }
        describe(playback.size, decoders, playback.values.map { stack -> stack.take(FRAMES_SHOWN).map { "${it.className}.${it.methodName}" } })
    }.getOrNull()

    internal fun describe(players: Int, decoders: Int, stacks: List<List<String>>): String {
        val where = stacks.filter { it.isNotEmpty() }.groupingBy { it.joinToString(" < ") }.eachCount()
            .entries.sortedByDescending { it.value }.take(2).joinToString("; ") { "${it.value}x ${it.key}" }
        return "Players alive: $players playback threads, $decoders video decoders" + if (where.isEmpty()) "" else "; waiting in $where"
    }

    internal fun count(names: List<String>, prefix: String): Int = names.count { it.startsWith(prefix) }

    private fun threadNames(): List<String> {
        var group = Thread.currentThread().threadGroup
        while (group?.parent != null) group = group.parent
        val root = group ?: return emptyList()
        val all = arrayOfNulls<Thread>(root.activeCount() * 2 + 16)
        val n = root.enumerate(all, true)
        return all.take(n).mapNotNull { it?.name }
    }
}
