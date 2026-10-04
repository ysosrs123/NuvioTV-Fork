package com.nuvio.tv.core.party

/** Estimates how far the host's clock is from ours. The sample with the shortest round trip wins. */
class PartyClock {
    private class Sample(val offsetMs: Long, val roundTripMs: Long)

    private val samples = ArrayDeque<Sample>()
    private var roughOffsetMs: Long? = null

    val hasMeasurement: Boolean get() = samples.isNotEmpty()

    val offsetMs: Long?
        get() = samples.minByOrNull { it.roundTripMs }?.offsetMs ?: roughOffsetMs

    val roundTripMs: Long?
        get() = samples.minByOrNull { it.roundTripMs }?.roundTripMs

    fun addSample(sentAtLocal: Long, hostClock: Long, receivedAtLocal: Long) {
        val roundTrip = receivedAtLocal - sentAtLocal
        if (roundTrip < 0 || roundTrip > MAX_ROUND_TRIP_MS) return
        samples.addLast(Sample(hostClock - (sentAtLocal + roundTrip / 2), roundTrip))
        while (samples.size > TRACKED) samples.removeFirst()
    }

    /** A host message that arrived before any ping came back: assumes no transit time, replaced by the first sample. */
    fun addRoughHint(hostClock: Long, receivedAtLocal: Long) {
        val candidate = hostClock - receivedAtLocal
        val current = roughOffsetMs
        if (current == null || candidate > current) roughOffsetMs = candidate
    }

    fun hostNow(localNow: Long): Long? = offsetMs?.let { localNow + it }

    fun reset() {
        samples.clear()
        roughOffsetMs = null
    }

    private companion object {
        const val TRACKED = 8
        const val MAX_ROUND_TRIP_MS = 5_000L
    }
}
