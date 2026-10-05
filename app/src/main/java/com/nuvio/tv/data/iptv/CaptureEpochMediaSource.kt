package com.nuvio.tv.data.iptv

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.BaseMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.Allocator
import com.nuvio.tv.core.iptv.OwnedCaptureConsumer
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Actual internal MediaSource + admitted OwnedCaptureConsumer over the incremental reader.
 * Start owns reader/metadata observer; prepare binds the playback Looper, without file IO.
 * At most one queued/in-flight callback and one epoch period. No player/decoder/network is opened.
 * Source/period release alone cannot certify renderer closure. Parent stops/confirms its renderer
 * owners before releasing period/source and retrying runtime close; uncertain closure stays reserved.
 * Controls remain disabled until executed source/player/renderer and measured aggregate gates pass.
 */
@UnstableApi
internal class CaptureEpochMediaSource(private val reader: IncrementalCaptureReaderConsumer,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO, private val closeTimeoutMs: Long=15_000,
) : BaseMediaSource(), OwnedCaptureConsumer {
    init { require(closeTimeoutMs in 1..120_000) }
    override val minimumMemoryReservationBytes = reader.minimumMemoryReservationBytes
    private val closeMutex=Mutex()
    private var started=false
    private var stopping=false
    private var prepared=false
    private var playbackThread:Thread?=null
    private var observer:Job?=null
    private var refresh:CapturePlaybackRefresh?=null
    private var period:CaptureEpochPeriod?=null
    private var failure:IOException?=null
    private var confirmed=false

    @Synchronized override fun start() {
        check(!started && !stopping); started=true
        reader.start()
        observer=CoroutineScope(dispatcher).launch(start=CoroutineStart.LAZY) {
            try { reader.state.collect { synchronized(this@CaptureEpochMediaSource) { refresh }?.request() } }
            catch (_:CancellationException) { }
            catch(e:Exception) { failed(e) }
        }.also { it.start() }
    }
    override fun getMediaItem():MediaItem = MediaItem.EMPTY
    override fun isSingleWindow() = true
    override fun prepareSourceInternal(mediaTransferListener:TransferListener?) {
        val loop=requireNotNull(Looper.myLooper()) { "Capture source requires playback Looper" }
        synchronized(this) { check(started && !stopping && !prepared); prepared=true; playbackThread=Thread.currentThread() }
        val handler=Handler(loop)
        val next=CapturePlaybackRefresh({ handler.post(it) },{ handler.removeCallbacks(it) },::publish,::failed)
        synchronized(this) { refresh=next }
        next.request()
    }
    private fun failed(e:Exception) { synchronized(this) { if(failure==null) failure=IOException("Capture source callback failed",e) } }
    private fun publish() {
        val current=synchronized(this) { if(stopping || !prepared) return; access(); period }
        val s=reader.state.value
        if(reader.borrowSnapshot(s) !== s) return
        if(s.state in errors || s.state==IncrementalReaderState.DISCONTINUITY) {
            if(s.state==IncrementalReaderState.DISCONTINUITY && current?.isPrepared==true) current.refresh(s)
            failed(IOException("Capture source boundary: ${s.state}")); return
        }
        // An initially empty running capture is unresolved; never publish temporary empty EOF.
        if(s.batches.isEmpty() && s.state!=IncrementalReaderState.ENDED) return
        if(current?.isPrepared==true && !current.refresh(s) && reader.borrowSnapshot(s) !== s) return
        if(reader.borrowSnapshot(s) !== s || synchronized(this) { stopping || !prepared }) return
        refreshSourceInfo(s.timeline)
    }
    @Synchronized override fun maybeThrowSourceInfoRefreshError() {
        access(); failure?.let { throw it }
        val s=reader.state.value.state
        if(stopping || s in errors || s==IncrementalReaderState.DISCONTINUITY) throw IOException("Capture source stopped: $s")
    }
    @Synchronized override fun createPeriod(id:MediaSource.MediaPeriodId, allocator:Allocator, startPositionUs:Long):MediaPeriod {
        access(); check(prepared && !stopping && period==null); require(!id.isAd)
        failure?.let { throw it }
        val s=reader.state.value
        if(s.batches.isEmpty() || s.timeline.getIndexOfPeriod(id.periodUid)<0 ||
            startPositionUs < s.batches.first().samples.video.samples.first().timeUs) throw IOException("Capture period target expired or unresolved")
        return CaptureEpochPeriod.create(reader,s,id.periodUid).also { period=it }
    }
    @Synchronized override fun releasePeriod(mediaPeriod:MediaPeriod) {
        access(); require(period === mediaPeriod) { "Foreign capture period" }
        period!!.close(); period=null
    }
    override fun releaseSourceInternal() {
        val jobs=synchronized(this) { access(); prepared=false; stopping=true; refresh to observer }
        jobs.first?.close(); jobs.second?.cancel() // No file IO/join on playback thread.
    }
    private fun access() { check(Thread.currentThread() === playbackThread) { "Capture source playback thread changed" } }
    override suspend fun close():Boolean = closeMutex.withLock {
        val jobs=synchronized(this) {
            if(confirmed) return@withLock true
            stopping=true; refresh?.close(); observer
        }
        jobs?.cancel()
        val joined=withTimeoutOrNull(closeTimeoutMs) { jobs?.join(); true } ?: false
        if(!joined || synchronized(this) { refresh?.close()==false }) return@withLock false
        val closed=reader.close() // Borrowed period prevents confirmed queue closure.
        synchronized(this) {
            if(!closed || prepared || period!=null) return@withLock false
            refresh=null; observer=null; confirmed=true; true
        }
    }
    companion object {
        private val errors=setOf(IncrementalReaderState.STOPPED,IncrementalReaderState.EXPIRED,IncrementalReaderState.FAILED,
            IncrementalReaderState.CANCELLED,IncrementalReaderState.RELEASE_BLOCKED,IncrementalReaderState.CLOSING,IncrementalReaderState.CLOSED)
    }
}
