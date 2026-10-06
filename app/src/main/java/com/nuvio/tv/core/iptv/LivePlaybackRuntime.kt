package com.nuvio.tv.core.iptv

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface OwnedLivePlayback {
    fun start()
    suspend fun close(): Boolean
}

enum class LiveOpenResult { OPENED, CAPACITY, SHARING_UNAVAILABLE, CLOSE_UNCONFIRMED, FAILED }

class LivePlaybackRuntime(private val admission: LiveSessionAdmission) {
    private data class Active(val owner: String, val lease: LiveConsumerLease, val playback: OwnedLivePlayback)
    private val mutex = Mutex()
    private var active: Active? = null

    suspend fun open(key: AcquisitionKey, acquisitionBytes: Long, viewerBytes: Long, owner: String = "foreground",
        create: suspend (PlaybackPurpose) -> OwnedLivePlayback): LiveOpenResult = mutex.withLock {
        currentCoroutineContext().ensureActive()
        if (!closeActive()) return@withLock LiveOpenResult.CLOSE_UNCONFIRMED
        currentCoroutineContext().ensureActive()
        val result = admission.acquire(key, acquisitionBytes, ConsumerReservation(LiveConsumerRole.VIEWER, 1, viewerBytes))
        if (result !is LiveAdmissionResult.Admitted) return@withLock LiveOpenResult.CAPACITY
        if (!result.openUpstream) {
            admission.release(result.lease)
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

    suspend fun stop(owner: String = "foreground"): Boolean = mutex.withLock {
        if (active?.owner != owner) true else closeActive()
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
}
