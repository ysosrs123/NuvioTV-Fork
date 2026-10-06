package com.nuvio.tv.data.iptv

import androidx.media3.common.C
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import com.nuvio.tv.core.iptv.CaptureSeekInput
import java.io.IOException

@UnstableApi
internal class PinnedCaptureSegmentPeriod internal constructor(batch: CapturedSampleBatch,
    private val seek: CaptureSeekInput, private val limits: CaptureSampleStagingLimits) : MediaPeriod, AutoCloseable {
    private var batch: CapturedSampleBatch? = batch
    private val groups: TrackGroupArray
    private val streams = mutableSetOf<Stream>()
    private var accessThread: Thread? = null
    private var fenced = false
    private var ownerReleased = false
    private var presentationUs: Long
    val released: Boolean get() = synchronized(this) { ownerReleased }
    val presentationStartUs: Long get() = synchronized(this) { presentationUs }

    init {
        require(batch.window === seek.request.window && batch.window.proof === seek.media.proof)
        require(seek.media.verified && !seek.media.isClosed) { "Capture period needs a verified open pin" }
        require(batch.chargedBytes in 1..limits.maxBatchBytes)
        require(batch.video.samples.size == seek.media.inspection.videoFrames && batch.audio.samples.size == seek.media.inspection.audioFrames)
        require(batch.video.samples.isNotEmpty() && batch.audio.samples.isNotEmpty())
        require(batch.video.samples.first().flags and C.BUFFER_FLAG_KEY_FRAME != 0)
        require(batch.video.samples.first().timeUs == ticksToUs(batch.window.start90k))
        require(listOf(batch.video,batch.audio).all { t ->
            t.samples.all { it.size in 1..limits.maxSampleBytes && it.flags and C.BUFFER_FLAG_KEY_FRAME.inv() == 0 } &&
                t.samples.zipWithNext().all { (a,b) -> b.timeUs > a.timeUs }
        })
        require(batch.video.format.sampleMimeType == "video/avc" && batch.audio.format.sampleMimeType == "audio/mp4a-latm")
        require(batch.video.format.width > 0 && batch.video.format.height > 0 &&
            batch.video.format.width.toLong()*batch.video.format.height <= limits.maxVideoPixels)
        require(batch.audio.format.sampleRate == seek.media.inspection.audioSampleRate && batch.audio.format.channelCount == seek.media.inspection.audioChannels)
        require(listOf(batch.video,batch.audio).all { it.samples.size <= limits.maxSamplesPerTrack &&
            it.format.drmInitData == null && it.format.initializationData.sumOf { bytes -> bytes.size.toLong() } <= limits.maxInitializationBytes })
        require((batch.video.samples + batch.audio.samples).sumOf { it.size.toLong() } <= batch.chargedBytes)
        groups = TrackGroupArray(TrackGroup("capture-video",batch.video.format),TrackGroup("capture-audio",batch.audio.format))
        presentationUs = floor(ticksToUs(seek.request.position90k))
    }

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        synchronized(this) {
            check(accessThread == null && !fenced && !seek.media.isClosed)
            accessThread = Thread.currentThread(); reset(positionUs)
        }
        callback.onPrepared(this)
    }
    @Synchronized override fun maybeThrowPrepareError() { access() }
    @Synchronized override fun getTrackGroups(): TrackGroupArray { access(); return groups }

    @Synchronized override fun selectTracks(selections: Array<out ExoTrackSelection?>, mayRetainStreamFlags: BooleanArray,
        output: Array<SampleStream?>, streamResetFlags: BooleanArray, positionUs: Long): Long {
        access()
        require(selections.size in 1..16 && selections.size == mayRetainStreamFlags.size && selections.size == output.size && output.size == streamResetFlags.size)

        val chosen = selections.map { selection -> selection?.let {
            val kind=(0..1).singleOrNull { k -> it.trackGroup === groups[k] }
                ?: throw IllegalArgumentException("Foreign capture track group")
            require(it.length() == 1 && it.getIndexInTrackGroup(0) == 0 && it.selectedIndexInTrackGroup == 0)
            kind
        } }
        require(chosen.filterNotNull().distinct().size == chosen.count { it != null })
        output.forEach { require(it == null || it is Stream && it in streams) { "Foreign capture stream" } }
        val position = floor(positionUs)
        val moved = position != presentationUs
        if (moved) reset(position)
        val kept = mutableSetOf<Stream>()
        for (i in output.indices) {
            val previous=output[i] as Stream?
            val kind=chosen[i]
            val retained=previous?.takeIf { kind == it.kind && mayRetainStreamFlags[i] }
            val next=if(kind == null) null else retained ?: Stream(kind)
            streamResetFlags[i] = next != null && (retained == null || moved)
            output[i]=next
            next?.let(kept::add)
        }
        streams.clear(); streams.addAll(kept)
        return presentationUs
    }

    @Synchronized override fun seekToUs(positionUs: Long): Long { access(); reset(positionUs); return presentationUs }
    @Synchronized override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long {
        access()
        val samples=requireNotNull(batch).video.samples
        val position=positionUs.coerceIn(samples.first().timeUs,samples.last().timeUs)
        val sync=samples.filter { it.flags and C.BUFFER_FLAG_KEY_FRAME != 0 }
        val before=sync.lastOrNull { it.timeUs <= position } ?: sync.first()
        val after=sync.firstOrNull { it.timeUs >= position } ?: sync.last()
        return floor(seekParameters.resolveSeekPositionUs(position,before.timeUs,after.timeUs))
    }
    @Synchronized override fun readDiscontinuity(): Long { access(); return C.TIME_UNSET }
    @Synchronized override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) { access() }
    @Synchronized override fun getBufferedPositionUs(): Long { access(); return C.TIME_END_OF_SOURCE }
    @Synchronized override fun getNextLoadPositionUs(): Long { access(); return C.TIME_END_OF_SOURCE }
    @Synchronized override fun continueLoading(loadingInfo: LoadingInfo): Boolean { access(); return false }
    @Synchronized override fun isLoading(): Boolean { access(); return false }
    @Synchronized override fun reevaluateBuffer(positionUs: Long) { access() }

    @Synchronized override fun close() {
        if(ownerReleased) return
        fenced=true
        seek.close()
        batch=null; streams.clear(); ownerReleased=true
    }
    private fun access() {
        check(Thread.currentThread() === accessThread) { "Capture period playback thread changed" }
        if(fenced || seek.media.isClosed) throw IOException("Capture period ownership is closed")
    }
    private fun floor(position: Long): Long {
        val video=requireNotNull(batch).video.samples
        val bounded=position.coerceIn(video.first().timeUs,video.last().timeUs)
        return video.last { it.timeUs <= bounded }.timeUs
    }
    private fun reset(position: Long) {
        presentationUs=floor(position)

        streams.forEach { it.cursor=0; it.formatSent=false }
    }
    private fun track(kind: Int) = if(kind==0) requireNotNull(batch).video else requireNotNull(batch).audio

    private inner class Stream(val kind: Int) : SampleStream {
        var cursor=0; var formatSent=false
        override fun isReady(): Boolean = synchronized(this@PinnedCaptureSegmentPeriod) {
            if(fenced || seek.media.isClosed || this !in streams) false else { access(); true }
        }
        override fun maybeThrowError() = synchronized(this@PinnedCaptureSegmentPeriod) { access(); check(this in streams) }
        override fun readData(holder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int = synchronized(this@PinnedCaptureSegmentPeriod) {
            access(); check(this in streams)
            require(readFlags and 7.inv() == 0)
            val t=track(kind)
            if(!formatSent || readFlags and SampleStream.FLAG_REQUIRE_FORMAT != 0) {
                holder.format=t.format; holder.drmSession=null; formatSent=true
                return@synchronized C.RESULT_FORMAT_READ
            }
            buffer.waitingForKeys=false
            if(cursor == t.samples.size) {
                buffer.setFlags(C.BUFFER_FLAG_END_OF_STREAM); buffer.timeUs=C.TIME_END_OF_SOURCE
                return@synchronized C.RESULT_BUFFER_READ
            }
            val sample=t.samples[cursor]
            buffer.timeUs=sample.timeUs; buffer.format=t.format
            buffer.setFlags(sample.flags or if(cursor==t.samples.lastIndex) C.BUFFER_FLAG_LAST_SAMPLE else 0)
            if(readFlags and SampleStream.FLAG_OMIT_SAMPLE_DATA == 0) {
                buffer.ensureSpaceForWrite(sample.size)
                sample.copyTo(requireNotNull(buffer.data))
            }
            if(readFlags and SampleStream.FLAG_PEEK == 0) cursor++
            C.RESULT_BUFFER_READ
        }
        override fun skipData(positionUs: Long): Int = synchronized(this@PinnedCaptureSegmentPeriod) {
            access(); check(this in streams)
            val samples=track(kind).samples
            val target=if(positionUs > samples.last().timeUs) samples.size else {
                samples.indices.lastOrNull { i -> i >= cursor && samples[i].timeUs <= positionUs &&
                    (kind==1 || samples[i].flags and C.BUFFER_FLAG_KEY_FRAME != 0) } ?: cursor
            }
            val skipped=target-cursor; cursor=target; skipped
        }
    }

    companion object {
        fun stage(seek: CaptureSeekInput, limits: CaptureSampleStagingLimits, checkCancellation: () -> Unit = {}): PinnedCaptureSegmentPeriod {
            val staged=LocalCaptureSampleStager(limits).stage(seek.request.window,seek.media,checkCancellation)
            checkCancellation()
            return PinnedCaptureSegmentPeriod(staged,seek,limits)
        }
        private fun ticksToUs(ticks: Long) = Math.addExact(Math.multiplyExact(ticks / 90_000,1_000_000),(ticks % 90_000)*1_000_000/90_000)
    }
}
