package com.nuvio.tv.core.iptv

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface OwnedLivePlayback {
    fun start()
    suspend fun close(): Boolean
    fun interrupt() {}
    val decoderReleased: Boolean get() = false
    fun abandon() {}
}

enum class LiveOpenResult { OPENED, CAPACITY, SHARING_UNAVAILABLE, CLOSE_UNCONFIRMED, FAILED }

object LiveSwitchOverlap {
    fun allowed(previousAccount: String, nextAccount: String, limit: Int?, decoderReleased: Boolean): Boolean =
        decoderReleased && (previousAccount != nextAccount || limit == null || limit > 1)
}

class LivePlaybackRuntime(private val admission: LiveSessionAdmission, private val closeWaitMs: Long = CLOSE_WAIT_MS) {
    private data class Active(val owner: String, val lease: LiveConsumerLease, val playback: OwnedLivePlayback)
    private val mutex = Mutex()
    @Volatile private var active: Active? = null
    @Volatile var lastDenial: AdmissionDenial? = null
        private set

    suspend fun open(key: AcquisitionKey, acquisitionBytes: Long, viewerBytes: Long, owner: String = "foreground",
        maxUpstreams: Int? = null, create: suspend (PlaybackPurpose) -> OwnedLivePlayback): LiveOpenResult = mutex.withLock {
        require(maxUpstreams == null || maxUpstreams in 1..16)
        currentCoroutineContext().ensureActive()
        if (!closeWithRetry() && !abandonFor(key, maxUpstreams)) return@withLock LiveOpenResult.CLOSE_UNCONFIRMED
        currentCoroutineContext().ensureActive()
        maxUpstreams?.let { admission.setAccountLimit(key.accountId, it) }
        val result = admission.acquire(key, acquisitionBytes, ConsumerReservation(LiveConsumerRole.VIEWER, 1, viewerBytes))
        lastDenial = (result as? LiveAdmissionResult.Denied)?.reason
        if (result !is LiveAdmissionResult.Admitted) return@withLock LiveOpenResult.CAPACITY
        if (!result.openUpstream) {
            release(result.lease)
            return@withLock LiveOpenResult.SHARING_UNAVAILABLE
        }
        val playback = try { create(PlaybackPurpose.LIVE_CHANNEL) } catch (cancel: CancellationException) {
            release(result.lease); throw cancel
        } catch (_: Exception) {
            release(result.lease)
            return@withLock LiveOpenResult.FAILED
        }
        active = Active(owner, result.lease, playback)
        admission.selectAudioOwner(result.lease)
        admission.selectDisplayOwner(result.lease)
        try { playback.start(); LiveOpenResult.OPENED } catch (cancel: CancellationException) {
            closeActive(); throw cancel
        } catch (_: Exception) {
            if (closeActive()) LiveOpenResult.FAILED else LiveOpenResult.CLOSE_UNCONFIRMED
        }
    }

    suspend fun handOver(owner: String, target: LivePlaybackRuntime, acquisitionBytes: Long, viewerBytes: Long): Boolean {
        if (target === this || active?.owner != owner || !target.stop(owner)) return false
        val moving = mutex.withLock { active?.takeIf { it.owner == owner && it.lease.let { lease -> admission.resize(lease, acquisitionBytes,
            ConsumerReservation(LiveConsumerRole.VIEWER, 1, viewerBytes)) } }?.also { active = null } } ?: return false
        val adopted = target.mutex.withLock { if (target.active == null) { target.active = moving; true } else false }
        if (!adopted) withContext(NonCancellable) {
            val closed = try { moving.playback.close() } catch (_: Exception) { false }
            if (!closed) runCatching { moving.playback.abandon() }
            release(moving.lease)
        }
        return adopted
    }

    fun interrupt(owner: String = "foreground") {
        active?.takeIf { it.owner == owner }?.playback?.let { runCatching { it.interrupt() } }
    }

    suspend fun stop(owner: String = "foreground"): Boolean = mutex.withLock {
        if (active?.owner != owner) true else closeWithRetry()
    }

    private fun abandonFor(key: AcquisitionKey, maxUpstreams: Int?): Boolean {
        val previous = active ?: return true
        val account = previous.lease.key.accountId
        val limit = if (account == key.accountId) maxUpstreams ?: admission.accountLimit(account) else admission.accountLimit(account)
        if (!LiveSwitchOverlap.allowed(account, key.accountId, limit, previous.playback.decoderReleased)) return false
        runCatching { previous.playback.abandon() }
        release(previous.lease); active = null
        return true
    }

    private suspend fun closeWithRetry(): Boolean {
        if (closeActive()) return true
        val deadline = System.nanoTime() + closeWaitMs * 1_000_000
        while (System.nanoTime() < deadline) {
            delay(CLOSE_STEP_MS)
            if (closeActive()) return true
        }
        return false
    }

    private suspend fun closeActive(): Boolean = withContext(NonCancellable) {
        val previous = active ?: return@withContext true
        val closed = try { previous.playback.close() } catch (_: Exception) { false }
        if (closed) { release(previous.lease); active = null }
        closed
    }

    private fun release(lease: LiveConsumerLease) {
        admission.release(lease)?.let(admission::completeClose)
    }

    private companion object {
        const val CLOSE_WAIT_MS = 8_000L
        const val CLOSE_STEP_MS = 100L
    }
}
