package com.nuvio.tv.core.iptv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.util.UUID

interface OwnedCaptureTransport {
    val refreshEvents: Flow<Unit> get() = emptyFlow()
    fun start()
    suspend fun close(): Boolean
}

interface OwnedCaptureConsumer {
    val minimumMemoryReservationBytes: Long get() = 0

    val minimumDecoderReservationCount: Int get() = 0
    fun start()
    suspend fun close(): Boolean
}

data class CaptureStorageReservation(val retainedBytes: Long, val segmentBytes: Long, val overheadBytes: Long) {
    init {
        require(retainedBytes > 0 && segmentBytes in 1..retainedBytes && overheadBytes > 0)
        require(retainedBytes <= Long.MAX_VALUE - segmentBytes)
        require(overheadBytes <= Long.MAX_VALUE - retainedBytes - segmentBytes)
    }
    val totalBytes: Long get() = retainedBytes + segmentBytes + overheadBytes
}

data class CapturePipeline(val store: CaptureSegmentStore, val transport: OwnedCaptureTransport)
class CaptureConsumerToken internal constructor(val id: String, val acquisitionId: String)

sealed interface CaptureJoinResult {
    data class Joined(val token: CaptureConsumerToken) : CaptureJoinResult
    data class Denied(val reason: AdmissionDenial) : CaptureJoinResult
    data object SharingUnavailable : CaptureJoinResult
    data object Closing : CaptureJoinResult

    data class Failed(val pendingConsumer: CaptureConsumerToken? = null) : CaptureJoinResult
}

class SharedCaptureRuntime(private val admission: LiveSessionAdmission) {
    private data class Consumer(val token: CaptureConsumerToken, val lease: LiveConsumerLease,
        var handle: OwnedCaptureConsumer? = null)
    private data class Session(val lease: LiveConsumerLease, val acquisitionBytes: Long,
        val captureBytes: Long, val storage: CaptureStorageReservation, val pipeline: CapturePipeline,
        val consumers: MutableMap<String, Consumer> = linkedMapOf(), var closing: Boolean = false,
        var transportStarted: Boolean = false, var transportClosed: Boolean = false)
    private val mutex = Mutex()
    private val sessions = linkedMapOf<AcquisitionKey, Session>()

    suspend fun join(key: AcquisitionKey, acquisitionBytes: Long, captureBytes: Long,
        storage: CaptureStorageReservation, reservation: ConsumerReservation,
        createPipeline: () -> CapturePipeline,
        createConsumer: (CaptureSegmentStore) -> OwnedCaptureConsumer): CaptureJoinResult = mutex.withLock {
        currentCoroutineContext().ensureActive()
        require(acquisitionBytes >= 0 && captureBytes >= 0)
        var session = sessions[key]
        if (session?.closing == true) return@withLock CaptureJoinResult.Closing
        if (session != null && (session.acquisitionBytes != acquisitionBytes ||
                session.captureBytes != captureBytes || session.storage != storage)) {
            return@withLock CaptureJoinResult.Denied(AdmissionDenial.INCOMPATIBLE_RESERVATION)
        }
        var infrastructure: LiveConsumerLease? = null
        if (session == null) {
            val acquired = admission.acquire(key, acquisitionBytes,
                ConsumerReservation(LiveConsumerRole.TIMESHIFT, 0, captureBytes, storage.totalBytes))
            if (acquired is LiveAdmissionResult.Denied) return@withLock CaptureJoinResult.Denied(acquired.reason)
            acquired as LiveAdmissionResult.Admitted
            if (!acquired.openUpstream) {
                releaseClosed(acquired.lease)
                return@withLock CaptureJoinResult.SharingUnavailable
            }
            infrastructure = acquired.lease
        }
        val acquired = admission.acquire(key, acquisitionBytes, reservation)
        if (acquired is LiveAdmissionResult.Denied) {
            infrastructure?.let(::releaseClosed)
            return@withLock CaptureJoinResult.Denied(acquired.reason)
        }
        acquired as LiveAdmissionResult.Admitted
        if (session == null) {
            val pipeline = try { createPipeline() } catch (failure: Exception) {
                releaseClosed(acquired.lease); releaseClosed(requireNotNull(infrastructure))
                if (failure is CancellationException) throw failure
                return@withLock CaptureJoinResult.Failed()
            }
            session = Session(requireNotNull(infrastructure), acquisitionBytes, captureBytes, storage, pipeline)
            sessions[key] = session
        }
        val token = CaptureConsumerToken(UUID.randomUUID().toString(), session.lease.acquisitionId)
        val consumer = Consumer(token, acquired.lease)
        session.consumers[token.id] = consumer
        try {
            check(session.pipeline.store.maxRetainedBytes == storage.retainedBytes &&
                session.pipeline.store.maxSegmentBytes == storage.segmentBytes)
            session.pipeline.store.checkStorageReservation(storage)
            currentCoroutineContext().ensureActive()
            consumer.handle = createConsumer(session.pipeline.store)
            val minimum = consumer.handle!!.minimumMemoryReservationBytes
            check(minimum >= 0 && reservation.memoryBytes >= minimum) { "Capture consumer memory is under-reserved" }
            val decoders = consumer.handle!!.minimumDecoderReservationCount
            check(decoders >= 0 && reservation.decoders >= decoders) { "Capture consumer decoder is under-reserved" }
            if (!session.transportStarted) { session.pipeline.transport.start(); session.transportStarted = true }
            currentCoroutineContext().ensureActive()
            consumer.handle!!.start()
            currentCoroutineContext().ensureActive()
            if (reservation.role == LiveConsumerRole.VIEWER) {
                admission.selectAudioOwner(consumer.lease)
                admission.selectDisplayOwner(consumer.lease)
            }
            CaptureJoinResult.Joined(token)
        } catch (failure: Exception) {
            closeConsumer(session, consumer)
            if (failure is CancellationException) throw failure
            CaptureJoinResult.Failed(token.takeIf { session.consumers.containsKey(token.id) })
        }
    }

    suspend fun close(token: CaptureConsumerToken): Boolean = mutex.withLock {
        val session = sessions.values.singleOrNull { it.lease.acquisitionId == token.acquisitionId }
            ?: return@withLock true
        val consumer = session.consumers[token.id]?.takeIf { it.token == token }
        if (consumer == null) {
            if (session.closing) closeSession(session) else true
        } else closeConsumer(session, consumer)
    }

    suspend fun closeAll(): Boolean = mutex.withLock {
        var closed = true
        for (session in sessions.values.toList()) {
            session.closing = true
            if (session.consumers.isEmpty()) {
                if (!closeSession(session)) closed = false
                continue
            }
            for (consumer in session.consumers.values.toList()) {
                if (!closeConsumer(session, consumer)) closed = false
            }
        }
        closed
    }

    suspend fun retryClosing(): Boolean = mutex.withLock {
        var closed = true
        for (session in sessions.values.filter { it.closing && it.consumers.isEmpty() }.toList()) {
            if (!closeSession(session)) closed = false
        }
        closed
    }

    private suspend fun closeConsumer(session: Session, consumer: Consumer): Boolean = withContext(NonCancellable) {
        val closed = try { consumer.handle?.close() ?: true } catch (_: Exception) { false }
        if (!closed) return@withContext false
        releaseClosed(consumer.lease)
        session.consumers.remove(consumer.token.id)
        if (session.consumers.isEmpty()) {
            session.closing = true
            closeSession(session)
        } else true
    }

    private suspend fun closeSession(session: Session): Boolean = withContext(NonCancellable) {
        if (sessions[session.lease.key] !== session) return@withContext true
        check(session.consumers.isEmpty())
        if (!session.transportClosed) {
            session.transportClosed = try { session.pipeline.transport.close() } catch (_: Exception) { false }
        }
        if (!session.transportClosed) return@withContext false

        try { session.pipeline.store.close() } catch (_: Exception) { return@withContext false }
        releaseClosed(session.lease)
        sessions.remove(session.lease.key)
        true
    }

    private fun releaseClosed(lease: LiveConsumerLease) {
        admission.release(lease)?.let(admission::completeClose)
    }
}
