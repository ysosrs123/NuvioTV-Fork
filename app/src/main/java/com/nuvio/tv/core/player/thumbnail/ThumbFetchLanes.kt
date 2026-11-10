package com.nuvio.tv.core.player.thumbnail

/** Where a session is when it plans its keyframe fetches. */
internal enum class FetchPhase { GENERATING, PLAYBACK, PLAYER_CLOSED }

/** How many keyframes are fetched at once. Decoding has its own lanes ([ThumbDecodeLanes]). */
internal object ThumbFetchLanes {
    const val MAX = 8
    const val PLAYBACK_MAX = 4
    /** TorBox while playing: its account total is shared with playback's downloads. */
    const val TORBOX_PLAYBACK_MAX = 3
    /** TorBox with playback held or closed: more lanes only made each read slower. */
    const val TORBOX_MAX = 4
    private const val SMALL_BOX_4K_MAX = 2

    /**
     * [ledgerCap] is the most thumbnail requests the server's [ConnectionLedger] entry allows at once; the ledger also
     * holds each request until playback leaves it room. A rate limit or dropped connection seen in the session means
     * one at a time from then on, and so does a server other than TorBox that [refused] lately while playing.
     * [surplus]: while playing, the lanes come from what the film leaves ([PlaybackHeadroom]), not a fixed number.
     */
    fun limit(
        phase: FetchPhase,
        rateLimited: Boolean,
        smallBox4K: Boolean,
        ledgerCap: Int = MAX,
        torBox: Boolean = false,
        refused: Boolean = false,
        surplus: Boolean = false,
    ): Int {
        if (rateLimited) return 1
        val lanes = when (phase) {
            FetchPhase.PLAYER_CLOSED -> if (torBox) 1 else MAX
            FetchPhase.GENERATING -> if (torBox) TORBOX_MAX else MAX
            // Small boxes pace 4K decodes while playing: a second fetch would only wait.
            FetchPhase.PLAYBACK -> when {
                smallBox4K -> 1
                refused && !torBox -> 1
                surplus -> MAX
                torBox -> TORBOX_PLAYBACK_MAX
                else -> PLAYBACK_MAX
            }
        }
        val capped = minOf(lanes, ledgerCap)
        return (if (smallBox4K) minOf(capped, SMALL_BOX_4K_MAX) else capped).coerceIn(1, MAX)
    }

    enum class State { NOT_STARTED, RUNNING, READY, FAILED }

    /**
     * The coverage slot to make next from [ahead] (planned order, head first): the head, unless its fetch is still
     * running while a later one already came in, so one slow request does not hold up the ones behind it.
     * -1: the head is still running and nothing behind it is ready, so wait for whichever fetch finishes first.
     */
    fun nextReady(ahead: List<Int>, state: (Int) -> State): Int {
        val head = ahead.firstOrNull() ?: return -1
        if (state(head) != State.RUNNING) return head
        for (i in 1 until ahead.size) if (state(ahead[i]) == State.READY) return ahead[i]
        return -1
    }

    /** The next keyframes to start, in [wanted]'s order: each once, none already under way, at most [room]. */
    fun pick(wanted: List<Int>, underWay: Set<Int>, room: Int): List<Int> {
        if (room <= 0) return emptyList()
        val out = LinkedHashSet<Int>()
        for (kf in wanted) {
            if (kf < 0 || kf in underWay) continue
            out.add(kf)
            if (out.size >= room) break
        }
        return out.toList()
    }
}
