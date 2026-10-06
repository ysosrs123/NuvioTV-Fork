package com.nuvio.tv.data.iptv

import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.iptv.*
import java.util.Collections
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class IncrementalReaderState { NEW, LOADING, READY, WAITING, CAPACITY, ENDED, STOPPED,
    EXPIRED, DISCONTINUITY, FAILED, CANCELLED, RELEASE_BLOCKED, CLOSING, CLOSED }

internal class CaptureReaderBorrow internal constructor()

@UnstableApi
internal data class IncrementalReaderSnapshot(val revision: Long, val state: IncrementalReaderState,
    val batches: List<CaptureLoadedBatch> = emptyList(), val timeline: Timeline = Timeline.EMPTY,
    val boundary: CaptureSampleWindow? = null)

@UnstableApi
internal class IncrementalCaptureReaderConsumer(private val queue: CaptureSampleBatchQueue,
    private val timelineFactory: CaptureMedia3TimelineFactory, private val producerState: () -> CaptureTransportState,
    private val refreshEvents: Flow<Unit> = emptyFlow(), private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val closeTimeoutMs: Long = 15_000,
) : OwnedCaptureConsumer {
    init { require(closeTimeoutMs in 1..120_000) }
    override val minimumMemoryReservationBytes: Long = queue.maxResidentBytes
    private val closeMutex = Mutex()
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val stateMutable = MutableStateFlow(IncrementalReaderSnapshot(0,IncrementalReaderState.NEW))
    val state: StateFlow<IncrementalReaderSnapshot> = stateMutable.asStateFlow()
    private var revision = 0L
    private var started = false
    private var stopping = false
    private var terminal = false
    private var observerFailed = false
    private var lastLoadState = IncrementalReaderState.NEW
    private var lastBoundary: CaptureSampleWindow? = null
    private var dirty = false
    private var worker: Job? = null
    private var observer: Job? = null
    private var closer: Job? = null
    private var cleanupConfirmed = false
    private var known = emptyList<CaptureLoadedBatch>()
    private val retiring = mutableSetOf<CaptureLoadedBatch>()
    private val pending = linkedSetOf<CaptureLoadedBatch>()
    private val blocked = mutableSetOf<CaptureLoadedBatch>()
    private var activeRelease: CaptureLoadedBatch? = null
    private var borrower: CaptureReaderBorrow? = null
    private var borrowed = emptyList<CaptureLoadedBatch>()

    @Synchronized override fun start() {
        check(!started && !stopping); started = true; dirty = true
        worker = CoroutineScope(dispatcher).launch(start=CoroutineStart.LAZY) {
            try {
                for (signal in signals) {
                    try { drain(currentCoroutineContext()) }
                    catch(cancel:CancellationException) { throw cancel }
                    catch (_:Exception) { synchronized(this@IncrementalCaptureReaderConsumer) {
                        terminal = true; dirty = false; lastLoadState = IncrementalReaderState.FAILED; if(!stopping) publishCached(IncrementalReaderState.FAILED)
                    } }
                }
            } catch (_:CancellationException) { }
        }.also { it.start() }
        observer = CoroutineScope(dispatcher).launch(start=CoroutineStart.LAZY) {
            try { refreshEvents.collect { requestLoad() } }
            catch (_:CancellationException) { }
            catch (_:Exception) { synchronized(this@IncrementalCaptureReaderConsumer) {
                observerFailed = true; terminal = true; dirty = false; lastLoadState = IncrementalReaderState.FAILED; if(!stopping) publishCached(IncrementalReaderState.FAILED)
            } }
        }.also { it.start() }
        signals.trySend(Unit)
    }

    @Synchronized fun requestLoad(): Boolean {
        if(!started || stopping || terminal || blocked.isNotEmpty()) return false
        dirty = true; signals.trySend(Unit); return true
    }

    @Synchronized fun borrowSnapshot(expected: IncrementalReaderSnapshot): IncrementalReaderSnapshot? {
        if(stopping || retiring.isNotEmpty() || expected.revision != revision || expected !== stateMutable.value) return null
        return expected
    }

    @Synchronized fun acquireBorrow(expected: IncrementalReaderSnapshot): CaptureReaderBorrow? {
        if(borrower != null || !borrowable(expected)) return null
        return CaptureReaderBorrow().also { borrower = it; borrowed = expected.batches }
    }
    @Synchronized fun refreshBorrow(lease: CaptureReaderBorrow, expected: IncrementalReaderSnapshot): Boolean {
        if(borrower !== lease || !borrowable(expected) || borrowed.any { old -> expected.batches.none { it === old } }) return false
        borrowed = expected.batches; return true
    }
    @Synchronized fun isBorrowOpen(lease: CaptureReaderBorrow): Boolean = borrower === lease && !stopping

    @Synchronized fun releaseBorrow(lease: CaptureReaderBorrow) {
        require(borrower === lease) { "Foreign or released capture borrower" }
        borrower = null; borrowed = emptyList()
    }

    @Synchronized fun retireBorrowedPrefix(lease: CaptureReaderBorrow, count: Int): Boolean {
        if(borrower !== lease || stopping || count !in 1 until borrowed.size || retiring.isNotEmpty()) return false
        val old=borrowed.take(count)
        if(old.any { row -> known.none { it === row } || row in pending || activeRelease === row }) return false
        borrowed=borrowed.drop(count); retiring.addAll(old); pending.addAll(old)
        revision=Math.addExact(revision,1); signals.trySend(Unit); return true
    }
    private fun borrowable(expected: IncrementalReaderSnapshot): Boolean = borrowSnapshot(expected) === expected &&
        expected.batches.isNotEmpty() && expected.state in setOf(IncrementalReaderState.READY,IncrementalReaderState.LOADING,
            IncrementalReaderState.WAITING,IncrementalReaderState.CAPACITY,IncrementalReaderState.ENDED,IncrementalReaderState.DISCONTINUITY)

    @Synchronized fun requestRelease(batch: CaptureLoadedBatch): Boolean {
        if(!started || stopping || known.none { it === batch } || borrowed.any { it === batch } || batch in pending || activeRelease === batch) return false
        retiring += batch; pending += batch
        revision = Math.addExact(revision,1)
        signals.trySend(Unit); return true
    }

    private suspend fun drain(context: kotlin.coroutines.CoroutineContext) {
        while(true) {
            context.ensureActive()
            val action: CaptureLoadedBatch? = synchronized(this) {
                if(stopping) return
                if(pending.isNotEmpty()) pending.first().also { pending.remove(it); activeRelease = it }
                else {
                    if(!dirty || terminal || blocked.isNotEmpty()) return
                    dirty = false; null
                }
            }
            if(action != null) {
                val confirmed = try { queue.release(action); true } catch (_:Exception) { false }
                synchronized(this) {
                    activeRelease = null
                    if(confirmed) { retiring.remove(action); blocked.remove(action); if(!terminal && blocked.isEmpty()) dirty = true }
                    else blocked += action
                }
                publish(if(confirmed) null else IncrementalReaderState.RELEASE_BLOCKED)
            } else {
                synchronized(this) { if(!stopping) publishCached(IncrementalReaderState.LOADING) }
                while(true) {
                    context.ensureActive()
                    if(synchronized(this) { stopping || terminal }) break
                    val next = queue.loadNext { context.ensureActive() }
                    synchronized(this) {
                        lastLoadState = IncrementalReaderState.valueOf(next.state.name); lastBoundary = next.boundary
                        if(next.state !in setOf(CaptureSampleLoadState.READY,CaptureSampleLoadState.WAITING,CaptureSampleLoadState.CAPACITY)) terminal = true
                    }
                    publish(IncrementalReaderState.valueOf(next.state.name),next.boundary)
                    if(next.state != CaptureSampleLoadState.READY) break
                    if(synchronized(this) { stopping || pending.isNotEmpty() }) break
                }
            }
        }
    }

    private fun publish(result: IncrementalReaderState?, boundary: CaptureSampleWindow? = null) {
        val all = queue.snapshotBatches()
        val (generation,visible) = synchronized(this) {
            known = all
            if(stopping || observerFailed) return
            revision to all.filterNot { it in retiring }
        }
        val producer = producerState()
        val timeline = timelineFactory.snapshot(visible.map { it.samples },producer)
        synchronized(this) {
            if(stopping || observerFailed || generation != revision) return
            revision = Math.addExact(revision,1)
            stateMutable.value = IncrementalReaderSnapshot(revision,result ?: lastLoadState,Collections.unmodifiableList(visible.toList()),timeline,
                if(result == null) lastBoundary else boundary)
        }
    }
    private fun publishCached(result: IncrementalReaderState) {
        revision = Math.addExact(revision,1)
        stateMutable.value = stateMutable.value.copy(revision=revision,state=result)
    }

    override suspend fun close(): Boolean = closeMutex.withLock {
        val jobs = synchronized(this) {
            if(cleanupConfirmed) { finishClosed(); return@withLock true }
            stopping = true; dirty = false; signals.close(); revision = Math.addExact(revision,1)
            stateMutable.value = IncrementalReaderSnapshot(revision,IncrementalReaderState.CLOSING)
            listOfNotNull(worker,observer)
        }
        jobs.forEach { it.cancel() }
        val confirmed = withTimeoutOrNull(closeTimeoutMs) {
            jobs.forEach { it.join() }
            if(synchronized(this@IncrementalCaptureReaderConsumer) { borrower != null }) return@withTimeoutOrNull false
            val cleanup = synchronized(this@IncrementalCaptureReaderConsumer) {
                closer?.takeUnless { it.isCompleted } ?: CoroutineScope(dispatcher).launch(start=CoroutineStart.LAZY) {
                    val closed = try { queue.close(); queue.isClosed } catch (_:Exception) { false }
                    if(closed) synchronized(this@IncrementalCaptureReaderConsumer) { cleanupConfirmed = true }
                }.also { closer = it; it.start() }
            }
            cleanup.join()
            synchronized(this@IncrementalCaptureReaderConsumer) { cleanupConfirmed }
        } ?: false
        if(confirmed) synchronized(this) { finishClosed() }
        confirmed
    }
    private fun finishClosed() {
        if(stateMutable.value.state == IncrementalReaderState.CLOSED) return
        known = emptyList(); retiring.clear(); pending.clear(); blocked.clear()
        revision = Math.addExact(revision,1)
        stateMutable.value = IncrementalReaderSnapshot(revision,IncrementalReaderState.CLOSED)
    }
}
