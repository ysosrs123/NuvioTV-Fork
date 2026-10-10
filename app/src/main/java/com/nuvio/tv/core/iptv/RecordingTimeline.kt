package com.nuvio.tv.core.iptv

data class RecordingMark(val offset: Long, val millis: Long, val cut: Boolean = false) {
    init { require(offset >= 0 && millis >= 0) }
}

data class RecordingTimeline(val marks: List<RecordingMark>, val endOffset: Long, val endMillis: Long, val probed: Boolean = false) {
    init {
        require(marks.isNotEmpty() && endOffset >= 0 && endMillis >= 0)
        require(marks.zipWithNext().all { (a, b) -> b.offset > a.offset && b.millis >= a.millis })
    }

    fun encode(): String = buildString {
        append(VERSION).append(' ').append(endOffset).append(' ').append(endMillis)
        if (probed) append(" p")
        append('\n')
        var offset = 0L
        var millis = 0L
        marks.forEach { mark ->
            append(mark.offset - offset).append(' ').append(mark.millis - millis)
            if (mark.cut) append(" c")
            append('\n')
            offset = mark.offset
            millis = mark.millis
        }
    }

    companion object {
        const val MAX_MARKS = 20_000
        private const val VERSION = "v1"

        fun decode(text: String): RecordingTimeline? = try {
            val lines = text.lineSequence().filter { it.isNotBlank() }.iterator()
            val head = lines.next().trim().split(' ')
            if (head[0] != VERSION) null else {
                val marks = ArrayList<RecordingMark>()
                var offset = 0L
                var millis = 0L
                while (lines.hasNext() && marks.size < MAX_MARKS) {
                    val parts = lines.next().trim().split(' ')
                    offset += parts[0].toLong()
                    millis += parts[1].toLong()
                    marks += RecordingMark(offset, millis, parts.getOrNull(2) == "c")
                }
                RecordingTimeline(marks, head[1].toLong(), head[2].toLong(), head.getOrNull(3) == "p")
            }
        } catch (_: Exception) { null }
    }
}

object TsClock {
    const val PACKET = 188
    const val TICKS_PER_MILLI = 90L
    const val MASK = (1L shl 33) - 1
    const val HALF = 1L shl 32
    const val JUMP_TICKS = 5_000L * TICKS_PER_MILLI
    private const val SYNC = 0x47

    class Pcr(val offset: Long, val pid: Int, val value: Long, val reset: Boolean)

    fun delta(from: Long, to: Long): Long = (to - from) and MASK

    fun continuous(ticks: Long): Boolean = ticks < JUMP_TICKS

    fun pcr(data: ByteArray, at: Int, offset: Long): Pcr? {
        if (at + 11 > data.size || (data[at].toInt() and 255) != SYNC) return null
        if ((data[at + 3].toInt() shr 4) and 2 == 0) return null
        if ((data[at + 4].toInt() and 255) < 7) return null
        val flags = data[at + 5].toInt() and 255
        if (flags and 0x10 == 0) return null
        val value = ((data[at + 6].toLong() and 255) shl 25) or ((data[at + 7].toLong() and 255) shl 17) or
            ((data[at + 8].toLong() and 255) shl 9) or ((data[at + 9].toLong() and 255) shl 1) or ((data[at + 10].toLong() and 255) shr 7)
        return Pcr(offset, ((data[at + 1].toInt() and 0x1F) shl 8) or (data[at + 2].toInt() and 255), value, flags and 0x80 != 0)
    }

    fun scan(data: ByteArray, length: Int, base: Long): List<Pcr> {
        val result = ArrayList<Pcr>()
        var at = sync(data, 0, length)
        while (at >= 0 && at + PACKET <= length) {
            if ((data[at].toInt() and 255) != SYNC) { at = sync(data, at + 1, length); continue }
            pcr(data, at, base + at)?.let(result::add)
            at += PACKET
        }
        return result
    }

    fun sync(data: ByteArray, from: Int, length: Int): Int {
        for (at in from until length) {
            if ((data[at].toInt() and 255) != SYNC) continue
            if (at + PACKET < length && (data[at + PACKET].toInt() and 255) != SYNC) continue
            if (at + 2 * PACKET < length && (data[at + 2 * PACKET].toInt() and 255) != SYNC) continue
            return at
        }
        return -1
    }
}

class RecordingClock(private var markMillis: Long = 4_000, private val maxMarks: Int = 12_000) {
    init { require(markMillis > 0 && maxMarks in 16..RecordingTimeline.MAX_MARKS) }
    private val marks = ArrayList<RecordingMark>()
    private val carry = ByteArray(TsClock.PACKET)
    private var carried = 0
    private var carryOffset = 0L
    private var synced = true
    private var pid = -1
    private var stale = 0
    private var lastPcr = -1L
    private var lastOffset = -1L
    private var ticks = 0L
    private var pendingCut: Long? = null
    private val ringOffsets = LongArray(RING)
    private val ringTicks = LongArray(RING)
    private var ringSize = 0
    private var ringNext = 0

    @Synchronized fun cut(offset: Long) {
        require(offset >= 0)
        pendingCut = offset
        lastPcr = -1
        carried = 0
        synced = true
    }

    @Synchronized fun feed(offset: Long, data: ByteArray, from: Int, length: Int) {
        require(offset >= 0 && from >= 0 && length >= 0 && from + length <= data.size)
        var position = from
        val end = from + length
        if (carried > 0) {
            val take = minOf(TsClock.PACKET - carried, length)
            System.arraycopy(data, position, carry, carried, take)
            carried += take
            position += take
            if (carried < TsClock.PACKET) return
            carried = 0
            if ((carry[0].toInt() and 255) == SYNC) packet(carry, 0, carryOffset) else synced = false
        }
        while (position < end) {
            val absolute = offset + (position - from)
            if (!synced) {
                val found = TsClock.sync(data, position, end)
                if (found < 0) return
                position = found
                synced = true
                continue
            }
            if (end - position < TsClock.PACKET) {
                System.arraycopy(data, position, carry, 0, end - position)
                carried = end - position
                carryOffset = absolute
                return
            }
            if ((data[position].toInt() and 255) != SYNC) { synced = false; continue }
            packet(data, position, absolute)
            position += TsClock.PACKET
        }
    }

    @Synchronized fun rollback(offset: Long) {
        marks.removeAll { it.offset >= offset }
        ticks = recent(offset) ?: marks.lastOrNull()?.millis?.times(TsClock.TICKS_PER_MILLI) ?: 0L
        ringSize = 0
        lastOffset = -1
        cut(offset)
    }

    @Synchronized fun timeline(end: Long): RecordingTimeline? {
        val kept = marks.filter { it.offset < end }
        if (kept.isEmpty()) return null
        val endTicks = if (lastOffset in 0 until end) ticks else recent(end) ?: 0L
        return RecordingTimeline(kept, end, maxOf(endTicks / TsClock.TICKS_PER_MILLI, kept.last().millis))
    }

    private fun packet(data: ByteArray, at: Int, offset: Long) {
        val pcr = TsClock.pcr(data, at, offset) ?: return
        if (pid < 0) pid = pcr.pid
        if (pcr.pid != pid) {
            if (++stale < RELOCK_AFTER) return
            pid = pcr.pid
            stale = 0
            if (lastPcr >= 0) { mark(offset, true); remember(pcr); return }
        }
        stale = 0
        if (lastPcr < 0) {
            mark(pendingCut?.takeIf { it <= offset } ?: offset, marks.isNotEmpty())
            pendingCut = null
        } else {
            val step = TsClock.delta(lastPcr, pcr.value)
            if (pcr.reset || !TsClock.continuous(step)) mark(offset, true)
            else {
                ticks += step
                if (ticks / TsClock.TICKS_PER_MILLI - marks.last().millis >= markMillis) mark(offset, false)
            }
        }
        remember(pcr)
    }

    private fun remember(pcr: TsClock.Pcr) {
        lastPcr = pcr.value
        lastOffset = pcr.offset
        ringOffsets[ringNext] = pcr.offset
        ringTicks[ringNext] = ticks
        ringNext = (ringNext + 1) % RING
        if (ringSize < RING) ringSize += 1
    }

    private fun recent(before: Long): Long? {
        for (step in 1..ringSize) {
            val index = (ringNext - step + RING) % RING
            if (ringOffsets[index] < before) return ringTicks[index]
        }
        return null
    }

    private fun mark(offset: Long, cut: Boolean) {
        val millis = ticks / TsClock.TICKS_PER_MILLI
        val last = marks.lastOrNull()
        if (last != null && offset <= last.offset) {
            if (cut && !last.cut && marks.size > 1) marks[marks.lastIndex] = last.copy(cut = true)
            return
        }
        marks += RecordingMark(offset, maxOf(millis, last?.millis ?: 0), cut)
        if (marks.size >= maxMarks) {
            val thinned = marks.filterIndexed { index, mark -> index == 0 || mark.cut || index % 2 == 0 }
            marks.clear()
            marks.addAll(thinned)
            markMillis *= 2
        }
    }

    private companion object {
        const val SYNC = 0x47
        const val RING = 256
        const val RELOCK_AFTER = 200
    }
}
