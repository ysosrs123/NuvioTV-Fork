package com.nuvio.tv.core.player.thumbnail

/**
 * The generating screen never stays up on its own: with no thumbnails session behind it, or with no progress for
 * [NO_PROGRESS_MS], it ends and the film shows. Main thread only.
 */
internal class PrepareScreenGuard {
    enum class End { NO_SESSION, NO_PROGRESS }

    companion object {
        /** A screen no session owns any more (the session ended before its screen came up). */
        const val NO_SESSION_MS = 3_000L
        /** Longer than the 30 s wait for the player's format and the screen's own 20 s stall rule. */
        const val NO_PROGRESS_MS = 45_000L
        const val POLL_MS = 1_000L
    }

    private var shownKey: Any? = null
    private var progressAt = -1L
    private var unownedSince = -1L

    /** Seconds the screen has shown no progress, for the log line. */
    fun stillForMs(nowMs: Long): Long = if (progressAt < 0) 0L else nowMs - progressAt

    /**
     * [key]: what the screen shows that counts as progress (null = no screen). [owned]: a session runs or is starting.
     * In the background nothing is judged. Returns why the screen should end now, else null.
     */
    fun note(nowMs: Long, key: Any?, owned: Boolean, foreground: Boolean): End? {
        if (key == null) {
            reset()
            return null
        }
        if (!foreground || key != shownKey || progressAt < 0) {
            shownKey = key
            progressAt = nowMs
        }
        if (!foreground || owned) unownedSince = -1L else if (unownedSince < 0) unownedSince = nowMs
        return when {
            unownedSince >= 0 && nowMs - unownedSince >= NO_SESSION_MS -> End.NO_SESSION
            nowMs - progressAt >= NO_PROGRESS_MS -> End.NO_PROGRESS
            else -> null
        }
    }

    fun reset() {
        shownKey = null
        progressAt = -1L
        unownedSince = -1L
    }
}
