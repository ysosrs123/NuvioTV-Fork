package com.nuvio.tv.core.iptv

data class LocalTimeshiftWindow(val oldest: Long, val head: Long)

object LocalTimeshiftRing {
    const val WRITE_CHUNK = LocalTimeshiftSizing.PACKET * 512
    private const val MIN_MARGIN = WRITE_CHUNK * 4L
    private const val MAX_MARGIN = 16L * 1024 * 1024

    fun margin(capacity: Long): Long = LocalTimeshiftSizing.alignUp((capacity / 50).coerceIn(MIN_MARGIN, MAX_MARGIN))

    fun oldest(head: Long, capacity: Long): Long {
        require(capacity > margin(capacity) && head >= 0)
        return LocalTimeshiftSizing.alignUp(maxOf(0, head - capacity + margin(capacity)))
    }

    fun window(head: Long, capacity: Long) = LocalTimeshiftWindow(oldest(head, capacity), head)

    fun filePosition(logical: Long, capacity: Long): Long { require(logical >= 0 && capacity > 0); return logical % capacity }

    fun contiguous(logical: Long, length: Int, capacity: Long): Int = minOf(length.toLong(), capacity - filePosition(logical, capacity)).toInt()

    fun readable(at: Long, head: Long, capacity: Long): Long = if (at < oldest(head, capacity)) -1 else head - at

    fun intact(at: Long, headAfterRead: Long, capacity: Long): Boolean = at >= oldest(headAfterRead, capacity)

    fun clamp(offset: Long, head: Long, capacity: Long): Long =
        LocalTimeshiftSizing.alignDown(offset).coerceIn(oldest(head, capacity), LocalTimeshiftSizing.alignDown(head))
}

object LocalTimeshiftPackets {
    private const val SYNC = 0x47

    fun sync(bytes: ByteArray, from: Int, length: Int): Int {
        val end = from + length
        for (index in from until end) {
            if (bytes[index].toInt() and 0xff != SYNC) continue
            val next = index + LocalTimeshiftSizing.PACKET
            if (next >= end || bytes[next].toInt() and 0xff == SYNC) return index
        }
        return -1
    }
}

class LocalTimeshiftIndex(private val maxEntries: Int = 4096, private val stepMillis: Long = 1_000) {
    init { require(maxEntries >= 2 && stepMillis > 0) }
    private val times = LongArray(maxEntries)
    private val offsets = LongArray(maxEntries)
    private var first = 0
    private var count = 0

    val size: Int @Synchronized get() = count

    private fun at(index: Int) = (first + index) % maxEntries

    @Synchronized fun add(timeMillis: Long, offset: Long): Boolean {
        if (count > 0) {
            val last = at(count - 1)
            if (offset < offsets[last] || timeMillis < times[last]) return false
            if (timeMillis - times[last] < stepMillis) return false
        }
        if (count == maxEntries) { first = (first + 1) % maxEntries; count-- }
        val slot = at(count)
        times[slot] = timeMillis; offsets[slot] = offset; count++
        return true
    }

    @Synchronized fun trim(oldest: Long) {
        while (count > 1 && offsets[at(1)] <= oldest) { first = (first + 1) % maxEntries; count-- }
    }

    @Synchronized fun oldestTime(oldest: Long): Long? {
        for (i in 0 until count) if (offsets[at(i)] >= oldest) return times[at(i)]
        return null
    }

    @Synchronized fun newestTime(): Long? = if (count == 0) null else times[at(count - 1)]

    @Synchronized fun offsetAt(timeMillis: Long, oldest: Long): Long? {
        var found: Long? = null
        for (i in 0 until count) {
            val slot = at(i)
            if (offsets[slot] < oldest) continue
            if (found == null || times[slot] <= timeMillis) found = offsets[slot]
            if (times[slot] > timeMillis) break
        }
        return found
    }

    @Synchronized fun timeAt(offset: Long): Long? {
        var found: Long? = null
        for (i in 0 until count) {
            val slot = at(i)
            if (offsets[slot] > offset) return found ?: times[slot]
            found = times[slot]
        }
        return found
    }
}
