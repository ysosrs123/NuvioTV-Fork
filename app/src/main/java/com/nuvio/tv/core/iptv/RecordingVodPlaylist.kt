package com.nuvio.tv.core.iptv

import kotlin.math.ceil

object RecordingProbe {
    const val WINDOW = 128 * 1024
    private const val MAX_READS = 400
    private const val SPACING_MILLIS = 120_000L
    private const val SLACK_TICKS = 30_000L * TsClock.TICKS_PER_MILLI

    private class Step(val pcr: TsClock.Pcr, val ticks: Long, val cut: Boolean)

    fun timeline(total: Long, wallMillis: Long, read: (offset: Long, size: Int) -> ByteArray): RecordingTimeline {
        val fallback = RecordingTimeline(listOf(RecordingMark(0, 0)), total.coerceAtLeast(0), wallMillis.coerceAtLeast(0), probed = true)
        if (total < 4L * TsClock.PACKET) return fallback
        var reads = 0
        var pid = -1
        fun sample(offset: Long, size: Int): List<TsClock.Pcr> {
            reads += 1
            val at = offset.coerceIn(0, total - 1)
            val data = read(at, minOf(size.toLong(), total - at).toInt())
            return TsClock.scan(data, data.size, at)
        }
        fun first(offset: Long): TsClock.Pcr? {
            val found = sample(offset, WINDOW)
            if (pid < 0) found.firstOrNull()?.let { pid = it.pid }
            return found.firstOrNull { it.pid == pid }
        }
        val count = ((if (wallMillis > 0) wallMillis / SPACING_MILLIS else total / (128L shl 20)) + 1).coerceIn(8, 160).toInt()
        val points = ((0 until count).map { total * it / count } + (total - WINDOW).coerceAtLeast(0)).distinct().sorted()
        val coarse = points.mapNotNull { first(it) }.distinctBy { it.offset }.sortedBy { it.offset }
        if (coarse.size < 2) return fallback
        val rates = coarse.zipWithNext().mapNotNull { (a, b) ->
            TsClock.delta(a.value, b.value).takeIf { it in 1 until TsClock.HALF }?.let { it.toDouble() / (b.offset - a.offset) }
        }.sorted()
        if (rates.isEmpty()) return fallback
        val rate = rates[rates.size / 2]
        fun estimate(bytes: Long): Long = (bytes * rate).toLong().coerceAtLeast(0)
        fun plausible(ticks: Long, bytes: Long): Boolean {
            if (ticks >= TsClock.HALF) return false
            val expected = bytes * rate
            return ticks >= expected * 0.4 - SLACK_TICKS && ticks <= expected * 2.5 + SLACK_TICKS
        }
        val steps = ArrayList<Step>()
        fun locate(a: TsClock.Pcr, b: TsClock.Pcr) {
            val bytes = b.offset - a.offset
            val inside = if (bytes <= 4L * WINDOW && reads < MAX_READS) sample(a.offset, (bytes + TsClock.PACKET).toInt())
                .filter { it.pid == pid && it.offset > a.offset && it.offset < b.offset } else emptyList()
            var previous = a
            var ticks = 0L
            for (pcr in inside + b) {
                val step = TsClock.delta(previous.value, pcr.value)
                if (TsClock.continuous(step) && !pcr.reset) ticks += step
                else {
                    if (previous !== a) steps += Step(previous, ticks, false)
                    steps += Step(pcr, estimate(pcr.offset - previous.offset), true)
                    ticks = 0
                }
                previous = pcr
            }
            if (steps.lastOrNull()?.pcr !== b) steps += Step(b, ticks, false)
        }
        fun refine(a: TsClock.Pcr, b: TsClock.Pcr) {
            val bytes = b.offset - a.offset
            val ticks = TsClock.delta(a.value, b.value)
            if (plausible(ticks, bytes)) { steps += Step(b, ticks, false); return }
            if (bytes <= 2L * WINDOW || reads >= MAX_READS) { locate(a, b); return }
            val middle = first(a.offset + bytes / 2)
            if (middle == null || middle.offset <= a.offset || middle.offset >= b.offset) { locate(a, b); return }
            refine(a, middle)
            refine(middle, b)
        }
        coarse.zipWithNext().forEach { (a, b) -> refine(a, b) }
        val marks = arrayListOf(RecordingMark(0, 0))
        var ticks = if (coarse.first().offset > 2L * WINDOW) estimate(coarse.first().offset) else 0L
        if (ticks > 0) marks += RecordingMark(coarse.first().offset, ticks / TsClock.TICKS_PER_MILLI)
        steps.forEach { step ->
            ticks += step.ticks
            val last = marks.last()
            if (step.pcr.offset > last.offset) marks += RecordingMark(step.pcr.offset, maxOf(ticks / TsClock.TICKS_PER_MILLI, last.millis), step.cut)
            else if (step.cut && marks.size > 1) marks[marks.lastIndex] = last.copy(cut = true)
        }
        val tail = total - steps.last().pcr.offset
        return RecordingTimeline(marks, total, maxOf((ticks + estimate(tail)) / TsClock.TICKS_PER_MILLI, marks.last().millis), probed = true)
    }
}

object RecordingVodPlaylist {
    data class Segment(val offset: Long, val length: Long, val millis: Long, val cut: Boolean)

    const val TARGET_MILLIS = 6_000L
    private const val DEFAULT_BYTES_PER_MILLI = 1_000.0

    fun segments(timeline: RecordingTimeline, total: Long, targetMillis: Long = TARGET_MILLIS): List<Segment> {
        require(targetMillis > 0)
        if (total <= 0) return emptyList()
        val marks = timeline.marks.filter { it.offset < total }.toMutableList()
        if (marks.isEmpty() || marks.first().offset > 0) marks.add(0, RecordingMark(0, 0))
        val result = ArrayList<Segment>()
        marks.forEachIndexed { index, mark ->
            val next = marks.getOrNull(index + 1)
            val bytes = (next?.offset ?: total) - mark.offset
            if (bytes <= 0) return@forEachIndexed
            val millis = (if (next != null) next.millis - mark.millis else tail(timeline, marks, total)).takeIf { it > 0 }
                ?: (bytes / rate(marks, index, timeline)).toLong().coerceAtLeast(1)
            split(mark.offset, bytes, millis, mark.cut && result.isNotEmpty(), targetMillis, result)
        }
        return result
    }

    fun text(segments: List<Segment>, uri: String): String = buildString {
        val target = ceil((segments.maxOfOrNull { it.millis } ?: TARGET_MILLIS) / 1000.0).toLong().coerceAtLeast(1)
        append("#EXTM3U\n#EXT-X-VERSION:4\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-TARGETDURATION:").append(target).append("\n#EXT-X-MEDIA-SEQUENCE:0\n")
        segments.forEach { segment ->
            if (segment.cut) append("#EXT-X-DISCONTINUITY\n")
            append("#EXTINF:").append(segment.millis / 1000).append('.').append((segment.millis % 1000).toString().padStart(3, '0')).append(",\n")
            append("#EXT-X-BYTERANGE:").append(segment.length).append('@').append(segment.offset).append('\n')
            append(uri).append('\n')
        }
        append("#EXT-X-ENDLIST\n")
    }

    fun durationMillis(segments: List<Segment>): Long = segments.sumOf { it.millis }

    private fun tail(timeline: RecordingTimeline, marks: List<RecordingMark>, total: Long): Long {
        val last = marks.last()
        val known = timeline.endMillis - last.millis
        val span = timeline.endOffset - last.offset
        return when {
            span <= 0 -> 0
            total <= timeline.endOffset -> (known.toDouble() * (total - last.offset) / span).toLong()
            else -> known + ((total - timeline.endOffset) / rate(marks, marks.lastIndex, timeline)).toLong()
        }
    }

    private fun rate(marks: List<RecordingMark>, index: Int, timeline: RecordingTimeline): Double {
        val start = (index downTo 0).firstOrNull { marks[it].cut } ?: 0
        val stop = ((index + 1) until marks.size).firstOrNull { marks[it].cut }
        val from = marks[start]
        val last = marks[(stop ?: marks.size) - 1]
        val (offset, millis) = if (stop == null && timeline.endOffset > last.offset) timeline.endOffset to timeline.endMillis
            else last.offset to last.millis
        val bytes = offset - from.offset
        val elapsed = millis - from.millis
        return if (bytes > 0 && elapsed > 0) bytes.toDouble() / elapsed else DEFAULT_BYTES_PER_MILLI
    }

    private fun split(start: Long, bytes: Long, millis: Long, cut: Boolean, targetMillis: Long, out: MutableList<Segment>) {
        val pieces = ceil(millis.toDouble() / targetMillis).toLong().coerceIn(1, maxOf(1, bytes / TsClock.PACKET))
        var previousOffset = start
        var previousMillis = 0L
        var first = true
        for (piece in 1..pieces) {
            val offset = if (piece == pieces) start + bytes else (bytes.toDouble() * piece / pieces).toLong().let { start + it - it % TsClock.PACKET }
            if (offset <= previousOffset) continue
            val at = if (piece == pieces) millis else (millis.toDouble() * (offset - start) / bytes).toLong()
            out += Segment(previousOffset, offset - previousOffset, (at - previousMillis).coerceAtLeast(1), cut && first)
            first = false
            previousOffset = offset
            previousMillis = at
        }
    }
}

object RecordingResume {
    const val MIN_MILLIS = 15_000L
    const val END_MILLIS = 60_000L

    fun keep(positionMillis: Long, durationMillis: Long): Long? = when {
        positionMillis < MIN_MILLIS -> null
        durationMillis > 0 && durationMillis - positionMillis <= maxOf(END_MILLIS, durationMillis / 50) -> null
        else -> positionMillis
    }

    fun fraction(positionMillis: Long?, durationMillis: Long?): Float? =
        if (positionMillis == null || durationMillis == null || durationMillis <= 0) null else (positionMillis.toFloat() / durationMillis).coerceIn(0f, 1f)
}
