package com.nuvio.tv.core.iptv

data class CapturePlaybackPosition(val epoch: Long, val position90k: Long)
enum class CaptureSeekState { READY, UNAVAILABLE, EXPIRED, STALE }

class CaptureSeekRequest internal constructor(internal val owner: Any,
    val window: CaptureSampleWindow, val position90k: Long, val clamped: Boolean, val returnToTail: Boolean)
data class CaptureSeekPreview(val state: CaptureSeekState, val request: CaptureSeekRequest? = null)
data class CaptureSeekCommit(val state: CaptureSeekState, val input: CaptureSeekInput? = null)

class CaptureSeekInput internal constructor(val request: CaptureSeekRequest,
    val media: InspectedCaptureInput) : AutoCloseable {
    val decodeStart90k get() = request.window.start90k
    val samplePosition90k get() = decodeStart90k +
        ((request.position90k - decodeStart90k) / media.inspection.videoStep90k) * media.inspection.videoStep90k
    override fun close() = media.close()
}

class CaptureSeekController(private val timeline: CaptureSampleTimeline) {
    private val owner = Any()
    private var pending: CaptureSeekRequest? = null
    private var committed: CaptureSeekRequest? = null

    @Synchronized fun begin(epoch: Long, position90k: Long): CaptureSeekPreview {
        pending = null; committed = null
        return preview(timeline.snapshot().windows.filter { it.epoch == epoch }, position90k, false)
    }

    @Synchronized fun move(current: CapturePlaybackPosition, delta90k: Long): CaptureSeekPreview {
        require(delta90k in -324_000_000L..324_000_000L)
        val anchor = pending?.let { CapturePlaybackPosition(it.window.epoch, it.position90k) } ?: current
        val rows = timeline.snapshot().windows.filter { it.epoch == anchor.epoch }
        if (rows.isEmpty()) return CaptureSeekPreview(if (pending == null) CaptureSeekState.UNAVAILABLE else CaptureSeekState.EXPIRED)
        if (anchor.position90k !in rows.first().start90k..rows.last().lastVideo90k) return CaptureSeekPreview(CaptureSeekState.EXPIRED)
        return preview(rows, Math.addExact(anchor.position90k, delta90k), false)
    }

    @Synchronized fun returnToCapturedTail(): CaptureSeekPreview {
        pending = null; committed = null
        val snapshot = timeline.snapshot()
        val rows = snapshot.windows.filter { it.epoch == snapshot.latestEpoch }
        return preview(rows, rows.lastOrNull()?.lastVideo90k ?: 0, true)
    }

    @Synchronized fun commit(request: CaptureSeekRequest): CaptureSeekCommit {
        if (request.owner !== owner || pending !== request || committed === request) return CaptureSeekCommit(CaptureSeekState.STALE)
        return try {
            val input = timeline.open(request.window)
            committed = request
            CaptureSeekCommit(CaptureSeekState.READY, CaptureSeekInput(request, input))
        } catch (_: CaptureMediaExpired) { CaptureSeekCommit(CaptureSeekState.EXPIRED) }
    }

    @Synchronized fun isCurrentCommitted(request: CaptureSeekRequest): Boolean =
        request.owner === owner && pending === request && committed === request

    @Synchronized fun acknowledge(request: CaptureSeekRequest): Boolean {
        if (request.owner !== owner || pending !== request || committed !== request) return false
        pending = null; committed = null; return true
    }

    @Synchronized fun cancelPending() { pending = null; committed = null }

    private fun preview(rows: List<CaptureSampleWindow>, requested: Long, tail: Boolean): CaptureSeekPreview {
        if (rows.isEmpty()) return CaptureSeekPreview(CaptureSeekState.UNAVAILABLE)
        val position = requested.coerceIn(rows.first().start90k, rows.last().lastVideo90k)
        val row = rows.last { position >= it.start90k }
        val request = CaptureSeekRequest(owner, row, position, position != requested, tail)
        pending = request
        return CaptureSeekPreview(CaptureSeekState.READY, request)
    }
}
