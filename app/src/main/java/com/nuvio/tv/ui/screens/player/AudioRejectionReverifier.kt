package com.nuvio.tv.ui.screens.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.nuvio.tv.core.player.AudioPassthroughPolicy

internal object DirectOpenProbeLock

/** What to do with a stashed refusal once a fallback opened: learn it, or not, and earlier ones to forget. */
internal class RejectionCommit(
    val learn: Boolean,
    val forget: List<String>,
    val refusedOnRoute: Set<AudioPassthroughPolicy.Group>
)

internal class AudioRejectionLedger {

    private val verified = HashSet<String>()
    private val probedRoutes = HashSet<String>()
    private var pending: Pair<String, String>? = null

    private class RouteEvidence(var updatedAtMs: Long) {
        val refused = LinkedHashSet<AudioPassthroughPolicy.Group>()
        val learned = LinkedHashSet<String>()
    }

    /** Per route: what was refused and learned there since a bitstream last opened on it. */
    private val sinceBitstreamOpen = HashMap<String, RouteEvidence>()
    private var evidenceRestored = false

    @Synchronized
    fun learnedFor(routeKey: String?, persisted: Set<String>): Set<AudioPassthroughPolicy.Group> {
        if (routeKey == null) return emptySet()
        return persisted.mapNotNull { entry ->
            if (entry in verified) return@mapNotNull null
            val separator = entry.lastIndexOf("::")
            if (separator <= 0) return@mapNotNull null
            if (entry.substring(0, separator) != routeKey) return@mapNotNull null
            AudioPassthroughPolicy.Group.entries.firstOrNull { it.name == entry.substring(separator + 2) }
        }.toSet()
    }

    @Synchronized
    fun entriesToProbe(routeKey: String, persisted: Set<String>): List<String> {
        if (!probedRoutes.add(routeKey)) return emptyList()
        return persisted.filter { it.startsWith("$routeKey::") && it !in verified }
    }

    @Synchronized
    fun markVerified(entry: String) {
        verified.add(entry)
    }

    @Synchronized
    fun invalidate() {
        verified.clear()
        probedRoutes.clear()
        sinceBitstreamOpen.clear()
    }

    @Synchronized
    fun noteOpenRefused(routeKey: String, group: AudioPassthroughPolicy.Group, nowMs: Long = System.currentTimeMillis()) {
        val route = sinceBitstreamOpen.getOrPut(routeKey) { RouteEvidence(nowMs) }
        route.refused.add(group)
        route.updatedAtMs = nowMs
    }

    /** Returns true when evidence for the route was dropped, so the saved copy needs writing. */
    @Synchronized
    fun noteBitstreamOpened(routeKey: String): Boolean = sinceBitstreamOpen.remove(routeKey) != null

    /**
     * A receiver refuses a format, not a route. Real receivers often refuse two formats (TrueHD and DTS-HD on a TV
     * ARC link, DTS and DTS-HD on a soundbar without DTS), but any HDMI sink that takes bitstream at all takes AC-3
     * and nearly always E-AC-3. So when AC-3 or E-AC-3 was refused along with another format on the route with no
     * bitstream opening there in between, the output itself is down: nothing is learned, and what was learned on that
     * route since its last bitstream open is handed back for forgetting.
     */
    @Synchronized
    fun commit(entry: String, nowMs: Long = System.currentTimeMillis()): RejectionCommit {
        val routeKey = routeOf(entry) ?: return RejectionCommit(false, emptyList(), emptySet())
        val route = sinceBitstreamOpen.getOrPut(routeKey) { RouteEvidence(nowMs) }
        route.updatedAtMs = nowMs
        val refused = route.refused.toSet()
        if (refused.size > 1 && refused.any { it in DOLBY_CORE }) {
            val forget = route.learned.filter { it != entry }
            route.learned.clear()
            return RejectionCommit(false, forget, refused)
        }
        route.learned.add(entry)
        return RejectionCommit(true, emptyList(), refused)
    }

    @Synchronized
    fun evidenceSnapshot(): Set<String> = sinceBitstreamOpen.mapTo(LinkedHashSet()) { (routeKey, route) ->
        val learned = route.learned.mapNotNull { groupOf(it)?.name }
        "${route.updatedAtMs};${route.refused.joinToString(",") { it.name }};${learned.joinToString(",")};$routeKey"
    }

    /** Brings back saved evidence once per process, so an outage still counts after the app restarts. */
    @Synchronized
    fun restoreEvidence(saved: Set<String>, nowMs: Long = System.currentTimeMillis()) {
        if (evidenceRestored) return
        evidenceRestored = true
        for (record in saved) {
            val parts = record.split(';', limit = 4)
            if (parts.size != 4) continue
            val at = parts[0].toLongOrNull() ?: continue
            if (nowMs - at !in 0..EVIDENCE_TTL_MS) continue
            val routeKey = parts[3]
            if (routeKey.isEmpty() || routeKey in sinceBitstreamOpen) continue
            val route = RouteEvidence(at)
            parts[1].split(',').mapNotNullTo(route.refused) { groupNamed(it) }
            parts[2].split(',').mapNotNull { groupNamed(it) }.mapTo(route.learned) { entry(routeKey, it) }
            if (route.refused.isNotEmpty()) sinceBitstreamOpen[routeKey] = route
        }
    }

    @Synchronized
    fun stashPending(streamUrl: String, entry: String) {
        pending = streamUrl to entry
    }

    @Synchronized
    fun takePendingFor(streamUrl: String): String? {
        val current = pending ?: return null
        pending = null
        return if (current.first == streamUrl) current.second else null
    }

    @Synchronized
    fun dropPending() {
        pending = null
    }

    companion object {
        const val EVIDENCE_TTL_MS = 10 * 60_000L

        private val DOLBY_CORE = setOf(AudioPassthroughPolicy.Group.AC3, AudioPassthroughPolicy.Group.EAC3)

        fun entry(routeKey: String, group: AudioPassthroughPolicy.Group): String = "$routeKey::${group.name}"

        fun groupOf(entry: String): AudioPassthroughPolicy.Group? {
            val separator = entry.lastIndexOf("::")
            if (separator <= 0) return null
            return groupNamed(entry.substring(separator + 2))
        }

        private fun groupNamed(name: String): AudioPassthroughPolicy.Group? =
            AudioPassthroughPolicy.Group.entries.firstOrNull { it.name == name }

        fun routeOf(entry: String): String? {
            val separator = entry.lastIndexOf("::")
            return if (separator <= 0) null else entry.substring(0, separator)
        }
    }
}

internal object AudioRejectionReverifier {

    private const val TAG = "AudioRejection"

    val ledger = AudioRejectionLedger()

    fun start(routeKey: String, persisted: Set<String>, onVerified: (String) -> Unit) {
        val entries = ledger.entriesToProbe(routeKey, persisted)
        if (entries.isEmpty()) return
        Log.i(TAG, "re-verifying learned denials on $routeKey: $entries")
        Thread({
            for (entry in entries) {
                val group = AudioRejectionLedger.groupOf(entry) ?: continue
                val opened = synchronized(DirectOpenProbeLock) { openAndRelease(group) }
                if (opened) {
                    ledger.markVerified(entry)
                    Log.i(TAG, "re-verify $entry: opened, denial cleared")
                    onVerified(entry)
                } else {
                    Log.i(TAG, "re-verify $entry: still refused")
                }
            }
        }, "audio-rejection-reverify").apply { isDaemon = true }.start()
    }

    private fun openAndRelease(group: AudioPassthroughPolicy.Group): Boolean {
        val encoding = when (group) {
            AudioPassthroughPolicy.Group.AC3 -> AudioFormat.ENCODING_AC3
            AudioPassthroughPolicy.Group.EAC3 -> AudioFormat.ENCODING_E_AC3
            AudioPassthroughPolicy.Group.TRUEHD -> AudioFormat.ENCODING_DOLBY_TRUEHD
            AudioPassthroughPolicy.Group.DTS -> AudioFormat.ENCODING_DTS
            AudioPassthroughPolicy.Group.DTS_HD -> AudioFormat.ENCODING_DTS_HD
        }
        val channelMask = AudioFormat.CHANNEL_OUT_5POINT1
        val minBuffer = AudioTrack.getMinBufferSize(PROBE_SAMPLE_RATE, channelMask, encoding)
        if (minBuffer <= 0) return false
        return try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(PROBE_SAMPLE_RATE)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(minBuffer)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            val initialized = track.state == AudioTrack.STATE_INITIALIZED
            track.release()
            initialized
        } catch (_: Exception) {
            false
        }
    }

    private const val PROBE_SAMPLE_RATE = 48_000
}
