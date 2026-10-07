package com.nuvio.tv.core.iptv

sealed interface CatchupStep {
    data class Seek(val positionMillis: Long, val programme: GuideProgramme?) : CatchupStep
    data class Tune(val programme: GuideProgramme, val fromMillis: Long?) : CatchupStep
    data object Live : CatchupStep
    data object Stay : CatchupStep
}

data class CatchupStream(val startMillis: Long, val endMillis: Long?, val seekable: Boolean)

data class ScrubBar(val startMillis: Long, val endMillis: Long, val position: Float, val live: Float?)

object CatchupScrub {
    const val LIVE_EDGE_MILLIS = 10_000L
    const val STEP_MILLIS = 30_000L
    const val TAIL_MILLIS = 60 * 60 * 1000L
    private const val MINUTE_MILLIS = 60_000L

    fun programmeAt(programmes: List<GuideProgramme>, timeMillis: Long): GuideProgramme? =
        programmes.lastOrNull { it.start.epochMillis <= timeMillis && (it.stop?.epochMillis ?: Long.MAX_VALUE) > timeMillis }

    fun plan(targetMillis: Long, stream: CatchupStream, programmes: List<GuideProgramme>, nowMillis: Long): CatchupStep {
        if (targetMillis >= nowMillis - LIVE_EDGE_MILLIS) return CatchupStep.Live
        val end = stream.endMillis
        if (stream.seekable && targetMillis >= stream.startMillis && (end == null || targetMillis < end))
            return CatchupStep.Seek(targetMillis - stream.startMillis, programmeAt(programmes, targetMillis))
        val programme = programmeAt(programmes, targetMillis)
        if (programme == null || !programme.start.precise) {
            val first = programmes.firstOrNull { it.start.precise && it.start.epochMillis > targetMillis && it.start.epochMillis < nowMillis }
            return when {
                first != null && targetMillis < stream.startMillis && first.start.epochMillis < stream.startMillis -> CatchupStep.Tune(first, null)
                stream.seekable && targetMillis < stream.startMillis -> CatchupStep.Seek(0, programmeAt(programmes, stream.startMillis))
                else -> CatchupStep.Stay
            }
        }
        return CatchupStep.Tune(programme, fromWithin(targetMillis, programme))
    }

    fun afterEnd(current: GuideProgramme?, endedAtMillis: Long, programmes: List<GuideProgramme>, nowMillis: Long): CatchupStep {
        val boundary = maxOf(current?.stop?.epochMillis ?: endedAtMillis, endedAtMillis)
        if (boundary >= nowMillis - LIVE_EDGE_MILLIS) return CatchupStep.Live
        val next = programmeAt(programmes, boundary)?.takeIf { it != current }
            ?: programmes.firstOrNull { it.start.epochMillis >= boundary && it != current }
            ?: return CatchupStep.Live
        if (!next.start.precise || next.start.epochMillis >= nowMillis - LIVE_EDGE_MILLIS) return CatchupStep.Live
        val stop = next.stop?.epochMillis
        return if (stop == null || stop > nowMillis) CatchupStep.Tune(next, next.start.epochMillis) else CatchupStep.Tune(next, fromWithin(boundary, next))
    }

    fun streamEnd(programme: GuideProgramme, fromMillis: Long?, nowMillis: Long): Long {
        val stop = programme.stop?.epochMillis
        if (fromMillis == null) return stop ?: (programme.start.epochMillis + TAIL_MILLIS)
        return if (stop == null || stop > nowMillis - TAIL_MILLIS) maxOf(stop ?: 0L, nowMillis) + TAIL_MILLIS else stop
    }

    fun nearLive(positionMillis: Long, nowMillis: Long): Boolean = positionMillis >= nowMillis - LIVE_EDGE_MILLIS

    fun bar(programme: GuideProgramme?, positionMillis: Long, nowMillis: Long): ScrubBar {
        val start = programme?.start?.epochMillis ?: (positionMillis - TAIL_MILLIS / 2)
        val end = programme?.stop?.epochMillis?.takeIf { it > start } ?: maxOf(start + TAIL_MILLIS, nowMillis)
        fun fraction(time: Long) = ((time - start).toDouble() / (end - start)).toFloat().coerceIn(0f, 1f)
        return ScrubBar(start, end, fraction(positionMillis), if (nowMillis < end) fraction(nowMillis) else null)
    }

    private fun fromWithin(targetMillis: Long, programme: GuideProgramme): Long? {
        val floored = Math.floorDiv(targetMillis, MINUTE_MILLIS) * MINUTE_MILLIS
        return floored.takeIf { it > programme.start.epochMillis }
    }
}
