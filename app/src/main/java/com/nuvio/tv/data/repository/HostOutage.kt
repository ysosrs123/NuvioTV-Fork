package com.nuvio.tv.data.repository

import android.util.Log
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException

/**
 * Remembers that a host could not be reached, so it is not asked again on every page. When another host answered
 * at the same time the network is fine and the host is taken as gone for [goneMs]; otherwise the box may just be
 * offline for a moment, and it is tried again after [offlineMs].
 */
internal class HostOutage(
    private val now: () -> Long = System::currentTimeMillis,
    private val goneMs: Long = GONE_MS,
    private val offlineMs: Long = OFFLINE_MS
) {
    @Volatile private var downUntilMs = 0L

    fun isDown(): Boolean = now() < downUntilMs

    fun markDown(networkUp: Boolean, tag: String? = null) {
        val holdMs = if (networkUp) goneMs else offlineMs
        downUntilMs = now() + holdMs
        if (tag != null) Log.w(tag, "Primary episode ratings host unreachable, using the backup for ${holdMs / 60_000} min")
    }

    companion object {
        const val GONE_MS = 24L * 60L * 60L * 1000L
        const val OFFLINE_MS = 10L * 60L * 1000L

        fun isUnreachable(error: Throwable): Boolean = generateSequence(error) { it.cause }.take(5).any {
            it is UnknownHostException || it is ConnectException || it is NoRouteToHostException
        }
    }
}
