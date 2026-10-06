package com.nuvio.tv.core.iptv

import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

data class CaptureSpaceReading(val usableBytes: Long, val allocationUnitBytes: Long, val volumeId: String)
fun interface CaptureSpaceProbe { fun read(directory: File): CaptureSpaceReading }
enum class CaptureStorageFailure { PROBE_FAILED, INVALID_READING, VOLUME_CHANGED, INSUFFICIENT_SPACE, RESERVATION_TOO_SMALL, UNGUARDED }
class CaptureStorageUnavailable(val reason: CaptureStorageFailure) : IOException("Capture storage unavailable: $reason")

class CaptureStoragePolicy(val minimumFreeBytes: Long, private val probe: CaptureSpaceProbe) {
    init { require(minimumFreeBytes > 0) }
    internal fun bind(directory: File): CaptureStorageFence = CaptureStorageFence(directory, minimumFreeBytes, probe)
}

internal class CaptureStorageFence(private val directory: File, private val minimumFreeBytes: Long,
    private val probe: CaptureSpaceProbe) {
    private val initial = observation()
    private fun observation(): CaptureSpaceReading {
        val reading = try { probe.read(directory) }
        catch (cancel: CancellationException) { throw cancel }
        catch (interrupted: InterruptedException) { throw interrupted }
        catch (_: Exception) { throw CaptureStorageUnavailable(CaptureStorageFailure.PROBE_FAILED) }
        if (reading.usableBytes < 0 || reading.allocationUnitBytes !in 1..(16L * 1024 * 1024) ||
            reading.volumeId.isBlank() || reading.volumeId.length > 128) throw CaptureStorageUnavailable(CaptureStorageFailure.INVALID_READING)
        return reading
    }
    private fun current(): CaptureSpaceReading = observation().also {
        if (it.volumeId != initial.volumeId || it.allocationUnitBytes != initial.allocationUnitBytes)
            throw CaptureStorageUnavailable(CaptureStorageFailure.VOLUME_CHANGED)
    }
    private fun rounded(bytes: Long): Long {
        require(bytes >= 0)
        val unit = initial.allocationUnitBytes
        return exact { Math.multiplyExact(bytes / unit + if (bytes % unit == 0L) 0 else 1, unit) }
    }
    fun overhead(indexBytes: Long, maxSegments: Int): Long = exact {
        Math.addExact(minimumFreeBytes, Math.addExact(Math.multiplyExact(rounded(indexBytes), 2),
            Math.multiplyExact(maxSegments.toLong() + 2, initial.allocationUnitBytes)))
    }
    fun admission(additionalPayloadBytes: Long, overheadBytes: Long) {
        require(additionalPayloadBytes >= 0 && overheadBytes > 0)
        demand(exact { Math.addExact(additionalPayloadBytes, overheadBytes) })
    }

    fun growth(oldBytes: Long, newBytes: Long, metadataBytes: Long = 0) {
        require(oldBytes >= 0 && newBytes >= oldBytes && metadataBytes >= 0)
        demand(exact { Math.addExact(minimumFreeBytes, Math.addExact(rounded(newBytes) - rounded(oldBytes), rounded(metadataBytes))) })
    }
    private fun demand(bytes: Long) {
        if (current().usableBytes < bytes) throw CaptureStorageUnavailable(CaptureStorageFailure.INSUFFICIENT_SPACE)
    }
    private inline fun exact(block: () -> Long): Long = try { block() }
        catch (_: ArithmeticException) { throw CaptureStorageUnavailable(CaptureStorageFailure.INVALID_READING) }
}
