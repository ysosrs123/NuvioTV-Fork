package com.nuvio.tv.core.iptv

import java.util.UUID

/** Shared aliases must be assigned the same accountId by source setup. No URLs or secrets here. */
data class AcquisitionKey(val accountId: String, val channelId: String, val variantId: String, val contextVersion: Long) {
    init { require(accountId.isNotBlank() && channelId.isNotBlank() && variantId.isNotBlank() && contextVersion >= 0) }
}
enum class LiveConsumerRole { VIEWER, RECORDING, TIMESHIFT }
data class ConsumerReservation(val role: LiveConsumerRole, val decoders: Int, val memoryBytes: Long, val storageBytes: Long = 0) {
    init {
        require(decoders >= 0 && memoryBytes >= 0 && storageBytes >= 0)
        require(role == LiveConsumerRole.VIEWER || decoders == 0) { "Capture-only consumers must not reserve decoders" }
        require(role != LiveConsumerRole.VIEWER || decoders > 0)
    }
}
data class DeviceAdmissionLimits(val decoders: Int, val memoryBytes: Long, val storageBytes: Long) {
    init { require(decoders >= 0 && memoryBytes >= 0 && storageBytes >= 0) }
}
data class LiveConsumerLease(val id: String, val acquisitionId: String, val key: AcquisitionKey)
data class AcquisitionCloseTicket(val acquisitionId: String)
enum class AdmissionDenial { ACCOUNT_LIMIT, DEVICE_DECODERS, DEVICE_MEMORY, DEVICE_STORAGE, ACQUISITION_CLOSING, INCOMPATIBLE_RESERVATION }
sealed interface LiveAdmissionResult {
    data class Admitted(val lease: LiveConsumerLease, val openUpstream: Boolean) : LiveAdmissionResult
    data class Denied(val reason: AdmissionDenial) : LiveAdmissionResult
}
data class LiveAdmissionSnapshot(
    val upstreamsByAccount: Map<String, Int>, val decoders: Int, val memoryBytes: Long,
    val storageBytes: Long, val consumers: Int, val closingAcquisitions: Int,
    val audioOwner: String?, val displayOwner: String?,
)

/**
 * Atomic reservations, not network or Android lifecycle ownership. Callers reserve before opening
 * media and confirm closure only after their transport has actually stopped. Closing counts against
 * account/memory capacity, so rapid channel changes cannot create fictitious spare provider slots.
 * Estimates include native/graphics resources; this is not a measurement or process memory cap.
 */
class LiveSessionAdmission(private val limits: DeviceAdmissionLimits) {
    private data class Acquisition(val id: String, val key: AcquisitionKey, val memoryBytes: Long, var closing: Boolean = false)
    private data class Consumer(val lease: LiveConsumerLease, val reservation: ConsumerReservation)
    private val acquisitions = linkedMapOf<AcquisitionKey, Acquisition>()
    private val consumers = linkedMapOf<String, Consumer>()
    private val accountLimits = mutableMapOf<String, Int>()
    private var audioOwner: String? = null
    private var displayOwner: String? = null

    @Synchronized fun setAccountLimit(accountId: String, maxUpstreams: Int) {
        require(accountId.isNotBlank() && maxUpstreams > 0)
        accountLimits[accountId] = maxUpstreams
        // Lowering a limit does not terminate existing consumers; it blocks further acquisitions.
    }

    @Synchronized fun acquire(key: AcquisitionKey, acquisitionMemoryBytes: Long, reservation: ConsumerReservation): LiveAdmissionResult {
        require(acquisitionMemoryBytes >= 0)
        val existing = acquisitions[key]
        if (existing?.closing == true) return LiveAdmissionResult.Denied(AdmissionDenial.ACQUISITION_CLOSING)
        if (existing != null && existing.memoryBytes != acquisitionMemoryBytes) return LiveAdmissionResult.Denied(AdmissionDenial.INCOMPATIBLE_RESERVATION)
        if (existing == null && acquisitions.values.count { it.key.accountId == key.accountId } >= (accountLimits[key.accountId] ?: 1)) {
            return LiveAdmissionResult.Denied(AdmissionDenial.ACCOUNT_LIMIT)
        }
        val current = snapshot()
        if (reservation.decoders > limits.decoders - current.decoders) return LiveAdmissionResult.Denied(AdmissionDenial.DEVICE_DECODERS)
        val newAcquisitionBytes = if (existing == null) acquisitionMemoryBytes else 0L
        val availableMemory = limits.memoryBytes - current.memoryBytes
        if (newAcquisitionBytes > availableMemory || reservation.memoryBytes > availableMemory - newAcquisitionBytes) {
            return LiveAdmissionResult.Denied(AdmissionDenial.DEVICE_MEMORY)
        }
        if (reservation.storageBytes > limits.storageBytes - current.storageBytes) return LiveAdmissionResult.Denied(AdmissionDenial.DEVICE_STORAGE)
        val acquisition = existing ?: Acquisition(UUID.randomUUID().toString(), key, acquisitionMemoryBytes).also { acquisitions[key] = it }
        val lease = LiveConsumerLease(UUID.randomUUID().toString(), acquisition.id, key)
        consumers[lease.id] = Consumer(lease, reservation)
        return LiveAdmissionResult.Admitted(lease, existing == null)
    }

    /** Idempotent. A final consumer returns the close fence; the upstream reservation remains held. */
    @Synchronized fun release(lease: LiveConsumerLease): AcquisitionCloseTicket? {
        val consumer = consumers[lease.id]?.takeIf { it.lease == lease } ?: return null
        consumers.remove(consumer.lease.id)
        if (audioOwner == lease.id) audioOwner = null
        if (displayOwner == lease.id) displayOwner = null
        if (consumers.values.any { it.lease.acquisitionId == lease.acquisitionId }) return null
        val acquisition = acquisitions[lease.key]?.takeIf { it.id == lease.acquisitionId } ?: return null
        acquisition.closing = true
        return AcquisitionCloseTicket(acquisition.id)
    }

    @Synchronized fun completeClose(ticket: AcquisitionCloseTicket): Boolean {
        val acquisition = acquisitions.values.singleOrNull { it.id == ticket.acquisitionId && it.closing } ?: return false
        acquisitions.remove(acquisition.key)
        return true
    }

    /** Playback layer performs actual audio/display handover; stale or capture-only leases cannot own either. */
    @Synchronized fun selectAudioOwner(lease: LiveConsumerLease): Boolean {
        if (!isViewer(lease)) return false
        audioOwner = lease.id
        return true
    }
    @Synchronized fun selectDisplayOwner(lease: LiveConsumerLease): Boolean {
        if (!isViewer(lease)) return false
        displayOwner = lease.id
        return true
    }
    private fun isViewer(lease: LiveConsumerLease): Boolean = consumers[lease.id]?.let { it.lease == lease && it.reservation.role == LiveConsumerRole.VIEWER } == true

    @Synchronized fun snapshot(): LiveAdmissionSnapshot = LiveAdmissionSnapshot(
        upstreamsByAccount = acquisitions.values.groupingBy { it.key.accountId }.eachCount(),
        decoders = consumers.values.sumOf { it.reservation.decoders },
        memoryBytes = acquisitions.values.sumOf { it.memoryBytes } + consumers.values.sumOf { it.reservation.memoryBytes },
        storageBytes = consumers.values.sumOf { it.reservation.storageBytes },
        consumers = consumers.size,
        closingAcquisitions = acquisitions.values.count { it.closing },
        audioOwner = audioOwner,
        displayOwner = displayOwner,
    )
}
