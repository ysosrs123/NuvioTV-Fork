package com.nuvio.tv.data.iptv

import androidx.media3.common.C
import androidx.media3.common.Timeline
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
import java.io.IOException

@UnstableApi
internal class CaptureEpochPeriod private constructor(private val reader: IncrementalCaptureReaderConsumer,
    private val lease: CaptureReaderBorrow, initial: IncrementalReaderSnapshot, val uid: Any, val epoch: Long,
    private val retirePlayedBatches: Boolean = false,
) : MediaPeriod, AutoCloseable {
    private var snapshot = initial
    private var batches = initial.batches
    private var groups = TrackGroupArray(TrackGroup("capture-epoch-video",batches.first().samples.video.format),
        TrackGroup("capture-epoch-audio",batches.first().samples.audio.format))
    private var callback: MediaPeriod.Callback? = null
    private var thread: Thread? = null
    private var closed = false
    private var presentationUs = firstVideo().timeUs
    private var startBatch = 0
    private val streams = mutableSetOf<Stream>()

    val isPrepared: Boolean get() = synchronized(this) { thread != null && !closed }

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        synchronized(this) { check(thread == null && !closed); thread = Thread.currentThread(); access(); this.callback = callback; reset(positionUs) }
        callback.onPrepared(this)
        if(!closed) refresh(reader.state.value)
    }

    fun refresh(next: IncrementalReaderSnapshot): Boolean {
        val cb = synchronized(this) {
            access()
            if(next.revision <= snapshot.revision || !valid(next,uid,epoch) ||
                next.batches.size < batches.size || batches.indices.any { next.batches[it] !== batches[it] } ||
                !reader.refreshBorrow(lease,next)) return false
            snapshot = next; batches = next.batches; callback
        }
        cb?.onContinueLoadingRequested(this)
        return true
    }

    @Synchronized fun retireConsumedPrefix(beforeUs: Long): Boolean {
        access()
        val count=batches.takeWhile { it.samples.video.samples.last().timeUs<beforeUs &&
            it.samples.audio.samples.last().timeUs<beforeUs }.size.coerceAtMost(batches.size-1)
        streams.forEach { it.normalize() }
        if(count==0 || streams.isEmpty() || streams.any { it.row<count } || !reader.retireBorrowedPrefix(lease,count)) return false
        batches=batches.drop(count); snapshot=snapshot.copy(batches=batches)
        streams.forEach { it.row-=count }; startBatch=(startBatch-count).coerceAtLeast(0)
        presentationUs=presentationUs.coerceAtLeast(firstVideo().timeUs)
        return true
    }
    @Synchronized override fun maybeThrowPrepareError() { access(); errorState() }
    @Synchronized override fun getTrackGroups(): TrackGroupArray { access(); return groups }
    @Synchronized override fun selectTracks(selections: Array<out ExoTrackSelection?>, mayRetainStreamFlags: BooleanArray,
        output: Array<SampleStream?>, streamResetFlags: BooleanArray, positionUs: Long): Long {
        access()
        require(selections.size in 1..16 && selections.size == mayRetainStreamFlags.size && selections.size == output.size && selections.size == streamResetFlags.size)
        val chosen=selections.map { s -> s?.let {
            val kind=(0..1).singleOrNull { k -> it.trackGroup === groups[k] } ?: throw IllegalArgumentException("Foreign epoch track")
            require(it.length()==1 && it.getIndexInTrackGroup(0)==0 && it.selectedIndexInTrackGroup==0); kind
        } }
        require(chosen.filterNotNull().distinct().size == chosen.count { it != null })
        output.forEach { require(it==null || it is Stream && it in streams) }
        val moved=floor(positionUs)!=presentationUs
        if(moved) reset(positionUs)
        val kept=mutableSetOf<Stream>()
        for(i in output.indices) {
            val prior=output[i] as Stream?; val kind=chosen[i]
            val retained=prior?.takeIf { kind==it.kind && mayRetainStreamFlags[i] }
            val next=if(kind==null) null else retained ?: Stream(kind)
            output[i]=next; streamResetFlags[i]=next!=null && (retained==null || moved); next?.let(kept::add)
        }
        streams.clear(); streams.addAll(kept); return presentationUs
    }
    @Synchronized override fun seekToUs(positionUs: Long): Long { access(); reset(positionUs); return presentationUs }
    @Synchronized override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long {
        access(); val position=positionUs.coerceIn(firstVideo().timeUs,lastVideo().timeUs)
        val sync=batches.flatMap { it.samples.video.samples }.filter { it.flags and C.BUFFER_FLAG_KEY_FRAME != 0 }
        val before=sync.lastOrNull { it.timeUs<=position } ?: sync.first()
        val after=sync.firstOrNull { it.timeUs>=position } ?: sync.last()
        return floor(seekParameters.resolveSeekPositionUs(position,before.timeUs,after.timeUs))
    }
    @Synchronized override fun readDiscontinuity(): Long { access(); errorState(); return C.TIME_UNSET }
    @Synchronized override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) {
        access()
        if(retirePlayedBatches && batches.size>1) retireConsumedPrefix(positionUs)
    }
    @Synchronized override fun getBufferedPositionUs(): Long { access(); return if(ended()) C.TIME_END_OF_SOURCE else endUs() }
    @Synchronized override fun getNextLoadPositionUs(): Long { access(); return if(ended()) C.TIME_END_OF_SOURCE else endUs() }
    @Synchronized override fun continueLoading(loadingInfo: LoadingInfo): Boolean { access(); return false }
    @Synchronized override fun isLoading(): Boolean { access(); return reader.state.value.state==IncrementalReaderState.LOADING }
    @Synchronized override fun reevaluateBuffer(positionUs: Long) { access() }
    @Synchronized override fun close() {
        if(closed) return
        closed=true; streams.clear(); groups=TrackGroupArray.EMPTY; batches=emptyList(); snapshot=IncrementalReaderSnapshot(snapshot.revision,IncrementalReaderState.CLOSED); callback=null
        reader.releaseBorrow(lease)
    }
    private fun access() {
        check(Thread.currentThread() === thread) { "Epoch period playback thread changed" }
        if(closed || !reader.isBorrowOpen(lease)) throw IOException("Epoch period ownership fenced")
    }
    private fun errorState() {
        val state=reader.state.value.state
        if(state in errors) throw IOException("Capture reader stopped: $state")
        if(snapshot.state in errors) throw IOException("Capture epoch stopped: ${snapshot.state}")
        if(snapshot.state==IncrementalReaderState.DISCONTINUITY) throw IOException("Explicit capture epoch boundary: ${snapshot.boundary?.epoch}")
    }
    private fun ended() = snapshot.state==IncrementalReaderState.ENDED
    private fun endUs() = snapshot.timeline.getPeriod(snapshot.timeline.getIndexOfPeriod(uid),Timeline.Period()).durationUs
    private fun firstVideo() = batches.first().samples.video.samples.first()
    private fun lastVideo() = batches.last().samples.video.samples.last()
    private fun floor(positionUs: Long): Long {
        val bounded=positionUs.coerceIn(firstVideo().timeUs,lastVideo().timeUs)
        val row=batches.last { it.samples.video.samples.first().timeUs<=bounded }
        return row.samples.video.samples.last { it.timeUs<=bounded }.timeUs
    }
    private fun reset(positionUs: Long) {
        presentationUs=floor(positionUs)
        startBatch=batches.indexOfLast { it.samples.video.samples.first().timeUs<=presentationUs }
        streams.forEach { it.row=startBatch; it.sample=0; it.formatSent=false }
    }
    private fun track(row: Int, kind: Int) = if(kind==0) batches[row].samples.video else batches[row].samples.audio
    private inner class Stream(val kind: Int) : SampleStream {
        var row=startBatch; var sample=0; var formatSent=false
        fun normalize() { while(row<batches.size && sample==track(row,kind).samples.size) { row++; sample=0 } }
        override fun isReady(): Boolean = synchronized(this@CaptureEpochPeriod) {
            if(closed || !reader.isBorrowOpen(lease) || this !in streams) false
            else { access(); normalize(); !formatSent || row<batches.size || ended() }
        }
        override fun maybeThrowError() = synchronized(this@CaptureEpochPeriod) { access(); check(this in streams); errorState() }
        override fun readData(holder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int = synchronized(this@CaptureEpochPeriod) {
            access(); check(this in streams); require(readFlags and 7.inv()==0)
            if(!formatSent || readFlags and SampleStream.FLAG_REQUIRE_FORMAT!=0) {
                holder.format=groups[kind].getFormat(0); holder.drmSession=null; formatSent=true; return@synchronized C.RESULT_FORMAT_READ
            }
            normalize()
            if(row==batches.size) {
                errorState()
                if(!ended()) return@synchronized C.RESULT_NOTHING_READ
                buffer.setFlags(C.BUFFER_FLAG_END_OF_STREAM); buffer.timeUs=C.TIME_END_OF_SOURCE; return@synchronized C.RESULT_BUFFER_READ
            }
            val data=track(row,kind).samples[sample]
            buffer.waitingForKeys=false; buffer.timeUs=data.timeUs; buffer.format=groups[kind].getFormat(0)
            buffer.setFlags(data.flags or if(ended() && row==batches.lastIndex && sample==track(row,kind).samples.lastIndex) C.BUFFER_FLAG_LAST_SAMPLE else 0)
            if(readFlags and SampleStream.FLAG_OMIT_SAMPLE_DATA==0) { buffer.ensureSpaceForWrite(data.size); data.copyTo(requireNotNull(buffer.data)) }
            if(readFlags and SampleStream.FLAG_PEEK==0) sample++
            C.RESULT_BUFFER_READ
        }
        override fun skipData(positionUs: Long): Int = synchronized(this@CaptureEpochPeriod) {
            access(); check(this in streams); normalize()
            var count=0; var candidateRow=row; var candidateSample=sample; var traversed=0
            for(r in row until batches.size) {
                val t=track(r,kind).samples
                for(i in (if(r==row) sample else 0) until t.size) {
                    val s=t[i]
                    if(s.timeUs>positionUs) { row=candidateRow; sample=candidateSample; return@synchronized count }
                    if(kind==1 || s.flags and C.BUFFER_FLAG_KEY_FRAME!=0) { candidateRow=r; candidateSample=i; count=traversed }
                    traversed++
                }
            }
            if(positionUs>track(batches.lastIndex,kind).samples.last().timeUs) { row=batches.size; sample=0; traversed }
            else { row=candidateRow; sample=candidateSample; count }
        }
    }
    companion object {
        private val errors=setOf(IncrementalReaderState.STOPPED,IncrementalReaderState.EXPIRED,IncrementalReaderState.FAILED,
            IncrementalReaderState.CANCELLED,IncrementalReaderState.RELEASE_BLOCKED,IncrementalReaderState.CLOSING,IncrementalReaderState.CLOSED)
        private fun valid(s: IncrementalReaderSnapshot, uid: Any, epoch: Long): Boolean {
            val index=s.timeline.getIndexOfPeriod(uid)
            if(index<0 || s.timeline.getPeriod(index,Timeline.Period(),true).id!=epoch || s.batches.isEmpty()) return false
            return s.batches.all { it.samples.window.epoch==epoch && it.samples.video.samples.isNotEmpty() && it.samples.audio.samples.isNotEmpty() &&
                it.samples.video.format == s.batches.first().samples.video.format && it.samples.audio.format == s.batches.first().samples.audio.format } &&
                s.batches.zipWithNext().all { (a,b) -> b.samples.window.proof.segment.sequence==a.samples.window.proof.segment.sequence+1 && b.samples.window.start90k==a.samples.window.endExclusive90k }
        }

        fun create(reader: IncrementalCaptureReaderConsumer, snapshot: IncrementalReaderSnapshot, uid: Any,
            retirePlayedBatches: Boolean = false): CaptureEpochPeriod {
            val index=snapshot.timeline.getIndexOfPeriod(uid); require(index>=0)
            val epoch=snapshot.timeline.getPeriod(index,Timeline.Period(),true).id as? Long ?: error("Missing epoch")
            require(valid(snapshot,uid,epoch))
            val lease=reader.acquireBorrow(snapshot) ?: throw IOException("Stale or owned capture snapshot")
            try { return CaptureEpochPeriod(reader,lease,snapshot,uid,epoch,retirePlayedBatches) }
            catch(t:Throwable) { reader.releaseBorrow(lease); throw t }
        }
    }
}
