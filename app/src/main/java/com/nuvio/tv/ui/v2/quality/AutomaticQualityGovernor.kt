package com.nuvio.tv.ui.v2.quality

/** Conservative, allocation-free frame observer. Thresholds are a safety gate,
 * not a device score: four busy windows must each contain >=20% JankStats misses.
 * A pending reduction is applied only after entering an idle utility destination.
 * No automatic upgrades occur within a session. */
class AutomaticQualityGovernor {
    private var resumed = false
    private var eligible = false
    private var route: String? = null
    private var utility = false
    private var tier = VisualQualityTier.PERFORMANCE
    private var warmUntil = Long.MAX_VALUE
    private var lastFrameAt = 0L
    private var windowStart = 0L
    private var frames = 0
    private var misses = 0
    private var badWindows = 0
    private var pendingFrom: String? = null
    private var routeChangedAt = 0L
    private var lastInputAt = 0L
    private val heldKeys = mutableSetOf<Int>() // Input events only, never frame callbacks.

    @Synchronized fun resume(now: Long) {
        resumed = true
        warmUntil = now + WarmupNanos
        heldKeys.clear()
        resetWindow(now)
    }

    @Synchronized fun pause(now: Long) {
        resumed = false
        pendingFrom = null
        heldKeys.clear()
        resetWindow(now)
    }

    @Synchronized fun input(now: Long, key: Int, down: Boolean) {
        lastInputAt = now
        if (down) heldKeys.add(key) else heldKeys.remove(key)
    }

    @Synchronized fun context(now: Long, allowed: Boolean, destination: String?, stableUtility: Boolean, currentTier: VisualQualityTier) {
        if (route != destination) {
            routeChangedAt = now
            resetWindow(now)
        }
        if (eligible != allowed || tier != currentTier) {
            resetWindow(now)
            if (!allowed || currentTier == VisualQualityTier.PERFORMANCE) pendingFrom = null
        }
        eligible = allowed
        route = destination
        utility = stableUtility
        tier = currentTier
    }

    @Synchronized fun frame(now: Long, isJank: Boolean) {
        if (!resumed || !eligible || route == null || tier == VisualQualityTier.PERFORMANCE || now < warmUntil) return
        if (pendingFrom != null) return
        if (lastFrameAt != 0L && now - lastFrameAt > GapToleranceNanos) resetWindow(now)
        lastFrameAt = now
        if (windowStart < warmUntil) windowStart = now
        if (now - windowStart >= WindowNanos) {
            // Idle or suspended gaps cannot count as continuous active work.
            val busy = now - windowStart <= WindowNanos + GapToleranceNanos && frames >= MinimumFrames
            badWindows = if (busy && misses * 100 >= frames * MissPercent) badWindows + 1 else 0
            windowStart = now
            frames = 0
            misses = 0
            if (badWindows >= ConsecutiveWindows) pendingFrom = route
        }
        frames++
        if (isJank) misses++
    }

    @Synchronized fun takeReduction(now: Long): VisualQualityTier? {
        val origin = pendingFrom ?: return null
        if (!resumed || !eligible || !utility || route == origin || heldKeys.isNotEmpty() ||
            now - routeChangedAt < StableNanos || now - lastInputAt < StableNanos) return null
        pendingFrom = null
        resetWindow(now)
        return when (tier) {
            VisualQualityTier.MAXIMUM -> VisualQualityTier.ENHANCED
            VisualQualityTier.ENHANCED -> VisualQualityTier.PERFORMANCE
            VisualQualityTier.PERFORMANCE -> null
        }
    }

    private fun resetWindow(now: Long) {
        lastFrameAt = 0L
        windowStart = now
        frames = 0
        misses = 0
        badWindows = 0
    }

    companion object {
        const val WarmupNanos = 30_000_000_000L
        const val WindowNanos = 5_000_000_000L
        const val GapToleranceNanos = 500_000_000L
        const val StableNanos = 1_500_000_000L
        const val MinimumFrames = 150
        const val MissPercent = 20
        const val ConsecutiveWindows = 4
    }
}
