package com.nuvio.tv.ui.screens.player

/**
 * Whether an owned playback recovery may rebuild the player after its wait, judged against the
 * output that played before the failure. HDMI that drops for a moment while the TV changes
 * refresh rate is the same output when it comes back.
 */
internal object RecoveryRouteCheck {
    enum class Action { RETRY, RETRY_PCM, STOP }

    fun referenceRoute(stableRoute: String?, routeAtError: String?): String? = stableRoute ?: routeAtError

    fun sameOutput(reference: String?, now: String?): Boolean = when {
        reference == null || now == null -> true
        reference == now -> true
        else -> PassthroughOutputReturn.isHdmiRoute(reference) && PassthroughOutputReturn.isHdmiRoute(now)
    }

    fun decide(
        stableRoute: String?,
        routeAtError: String?,
        routeNow: String?,
        waitedForOutput: Boolean,
        pcmFallbackAvailable: Boolean
    ): Action {
        if (waitedForOutput) return Action.RETRY
        if (PassthroughOutputReturn.isHdmiRoute(routeNow)) return Action.RETRY
        if (sameOutput(referenceRoute(stableRoute, routeAtError), routeNow)) return Action.RETRY
        if (routeAtError != null && routeAtError == routeNow) return Action.RETRY
        return if (pcmFallbackAvailable) Action.RETRY_PCM else Action.STOP
    }
}
