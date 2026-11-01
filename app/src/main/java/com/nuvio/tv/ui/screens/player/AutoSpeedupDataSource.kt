package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.ByteBufferDataReader
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * One progressive stream with Custom network off: a single download while [downloads] says 1, the chunk session
 * (limited live to [downloads]) while it says more. The path changes between reads, only on a piece boundary so the
 * chunk session starts on a fresh piece instead of fetching again what was already read, and only after a long
 * sequential run, so a short side read never changes path. The new path is opened at the boundary ahead of time, so
 * its first piece downloads while the old path finishes the current one. [received] counts what arrives on the
 * single path; the chunk session counts its own. [multiOpen] counts instances reading from the chunk session.
 * With [pieces], the chunk session also changes to the piece size [pieceBytes] asks for, on a boundary of both sizes,
 * with the first piece of the new size fetched ahead.
 */
@UnstableApi
internal class AutoSpeedupDataSource(
    private val single: DataSource.Factory,
    private val multi: DataSource.Factory,
    private val pieceBytes: PieceBytes,
    private val downloads: () -> Int,
    private val received: AtomicLong,
    private val onMultiClosed: () -> Unit = {},
    private val multiOpen: AtomicInteger = AtomicInteger(),
    private val onFirstByte: (Long) -> Unit = {},
    private val onOpened: (String?) -> Unit = {},
    private val pieces: PieceSwitch? = null
) : DataSource, ByteBufferDataReader {
    private class Pending(val multi: Boolean, val atRead: Long, val source: DataSource, val opened: Future<*>)
    private class Prestarted(val pieceBytes: Long, val atRead: Long, val uri: Uri)

    /** Asked on every read, so it returns a plain long. */
    fun interface PieceBytes {
        operator fun invoke(): Long
    }

    /** Chunk sessions by piece size. */
    interface PieceSwitch {
        fun source(pieceBytes: Long): DataSource

        /** Fetches the piece at [position] ahead; false when a session of the new size must not be started now. */
        fun prestart(pieceBytes: Long, uri: Uri, position: Long): Boolean

        fun dropPrestart(uri: Uri)

        fun inUse(pieceBytes: Long)
    }

    private var activePiece = 0L
    private var prestarted: Prestarted? = null

    private val listeners = mutableListOf<TransferListener>()
    private var spec: DataSpec? = null
    private var current: DataSource? = null
    private var multiActive = false
    private var readThisOpen = 0L
    private var switchAfter = MIN_RUN_BYTES
    private var pending: Pending? = null
    private var scratch: ByteArray? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        dropPending()
        spec = dataSpec
        readThisOpen = 0L
        switchAfter = MIN_RUN_BYTES
        val wantMulti = downloads() > 1
        if (!wantMulti) onMultiClosed()
        dropPrestart()
        val source = create(wantMulti, if (wantMulti) pieceBytes() else 0L)
        current = source
        val startNs = System.nanoTime()
        val length = source.open(dataSpec)
        if (!wantMulti) onFirstByte((System.nanoTime() - startNs) / 1_000_000L)
        onOpened(source.uri?.toString())
        setMulti(wantMulti)
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val capped = capToBoundary(length)
        return counted(checkNotNull(current).read(buffer, offset, capped))
    }

    override fun supportsByteBufferRead(): Boolean = current.let { it is ByteBufferDataReader && it.supportsByteBufferRead() }

    /** The path can change inside this call, so a path without buffer reads is copied through a small array. */
    override fun read(buffer: ByteBuffer, length: Int): Int {
        val capped = capToBoundary(length)
        val source = checkNotNull(current)
        if (source is ByteBufferDataReader && source.supportsByteBufferRead()) return counted(source.read(buffer, capped))
        val array = scratch ?: ByteArray(SCRATCH_BYTES).also { scratch = it }
        val read = source.read(array, 0, minOf(capped, array.size))
        if (read > 0) buffer.put(array, 0, read)
        return counted(read)
    }

    private fun capToBoundary(length: Int): Int {
        val toBoundary = maybeSwitch()
        if (current == null) throw IOException("Not open")
        return if (toBoundary in 1L until length.toLong()) toBoundary.toInt() else length
    }

    private fun counted(read: Int): Int {
        if (read > 0) {
            readThisOpen += read
            if (!multiActive) received.addAndGet(read.toLong())
        }
        return read
    }

    /** Switches when on a piece boundary; otherwise returns the bytes left to the next one (0 = no switch wanted). */
    private fun maybeSwitch(): Long {
        val wantMulti = downloads() > 1
        val wantPiece = if (pieces != null && wantMulti && multiActive) pieceBytes() else activePiece
        val pieceChange = wantMulti && multiActive && activePiece > 0L && wantPiece != activePiece
        if (prestarted != null && prestarted?.pieceBytes != wantPiece) dropPrestart()
        if (wantMulti == multiActive && !pieceChange) {
            dropPending()
            return 0L
        }
        if (readThisOpen < switchAfter) return 0L
        val open = spec ?: return 0L
        val position = open.position + readThisOpen
        if (pieceChange) return switchPiece(open, position, wantPiece)
        val piece = if (multiActive && activePiece > 0L) activePiece else pieceBytes()
        val toBoundary = (piece - position % piece) % piece
        val boundaryRead = readThisOpen + toBoundary
        if (open.length != C.LENGTH_UNSET.toLong() && boundaryRead >= open.length) return 0L
        var next = pending
        if (next != null && (next.multi != wantMulti || next.atRead != boundaryRead)) {
            dropPending()
            next = null
        }
        if (next == null) {
            val source = create(wantMulti, piece)
            val nextSpec = open.subrange(boundaryRead)
            next = Pending(wantMulti, boundaryRead, source, OPENER.submit { source.open(nextSpec) })
            pending = next
        }
        if (toBoundary > 0L) return toBoundary
        pending = null
        try {
            next.opened.get()
        } catch (e: Exception) {
            Log.w(TAG, "AUTO_SPEEDUP: switch to ${pathName(wantMulti)} failed, staying", e)
            runCatching { next.source.close() }
            switchAfter = readThisOpen + RETRY_AFTER_BYTES
            return 0L
        }
        val old = current
        val oldWasMulti = multiActive
        current = next.source
        spec = open.subrange(readThisOpen)
        readThisOpen = 0L
        switchAfter = MIN_RUN_BYTES
        setMulti(wantMulti)
        Log.i(TAG, "AUTO_SPEEDUP: path=${pathName(wantMulti)} at=$position")
        runCatching { old?.close() }
        if (oldWasMulti) onMultiClosed()
        return 0L
    }

    /**
     * Moves the chunk session to [want] pieces on the next position that starts a piece of both sizes, so the old
     * session finishes its piece and the new one starts on a whole one. The new session's first piece is fetched ahead
     * once the boundary is close; the old session is closed as the new one opens.
     */
    private fun switchPiece(open: DataSpec, position: Long, want: Long): Long {
        val switch = checkNotNull(pieces)
        dropPending()
        val step = lcm(activePiece, want)
        val toBoundary = (step - position % step) % step
        val boundaryRead = readThisOpen + toBoundary
        if (open.length != C.LENGTH_UNSET.toLong() && boundaryRead >= open.length) return 0L
        if (toBoundary > PRESTART_PIECES * activePiece) return toBoundary.coerceAtMost(activePiece)
        val ahead = prestarted
        if (ahead == null || ahead.atRead != boundaryRead || toBoundary == 0L) {
            if (ahead != null && ahead.atRead != boundaryRead) dropPrestart()
            if (!switch.prestart(want, open.uri, position + toBoundary)) {
                dropPrestart()
                switchAfter = readThisOpen + RETRY_AFTER_BYTES
                Log.i(TAG, "AUTO_SPEEDUP: piece ${activePiece / MB}->${want / MB}MB held, another session is waiting")
                return 0L
            }
            prestarted = Prestarted(want, boundaryRead, open.uri)
        }
        if (toBoundary > 0L) return toBoundary
        prestarted = null
        val oldPiece = activePiece
        val source = create(true, want, announce = false)
        val nextSpec = open.subrange(readThisOpen)
        try {
            source.open(nextSpec)
        } catch (e: Exception) {
            Log.w(TAG, "AUTO_SPEEDUP: piece ${oldPiece / MB}->${want / MB}MB failed, reopening at ${oldPiece / MB}MB", e)
            runCatching { source.close() }
            reopenAt(oldPiece, nextSpec)
            return 0L
        }
        val old = current
        current = source
        spec = nextSpec
        readThisOpen = 0L
        switchAfter = MIN_RUN_BYTES
        activePiece = want
        Log.i(TAG, "AUTO_SPEEDUP: piece ${oldPiece / MB}->${want / MB}MB at=$position")
        runCatching { old?.close() }
        switch.inUse(want)
        return 0L
    }

    /** The new size's open has already ended the old session, so the old size is opened again on a fresh one. */
    private fun reopenAt(piece: Long, nextSpec: DataSpec) {
        val back = create(true, piece, announce = false)
        try {
            back.open(nextSpec)
        } catch (e: Exception) {
            runCatching { back.close() }
            throw e
        }
        val old = current
        current = back
        spec = nextSpec
        readThisOpen = 0L
        switchAfter = RETRY_AFTER_BYTES
        runCatching { old?.close() }
    }

    private fun dropPrestart() {
        val ahead = prestarted ?: return
        prestarted = null
        pieces?.dropPrestart(ahead.uri)
    }

    /** A path opened ahead that is no longer wanted is closed after its open finishes, on the same thread. */
    private fun dropPending() {
        val stale = pending ?: return
        pending = null
        OPENER.execute { runCatching { stale.opened.get() }; runCatching { stale.source.close() } }
    }

    private fun setMulti(on: Boolean) {
        if (on != multiActive) {
            if (on) multiOpen.incrementAndGet() else multiOpen.decrementAndGet()
        }
        multiActive = on
    }

    private fun create(multiPath: Boolean, piece: Long, announce: Boolean = true): DataSource {
        val source = when {
            !multiPath -> single.createDataSource()
            pieces != null -> pieces.source(piece)
            else -> multi.createDataSource()
        }
        if (multiPath) {
            activePiece = piece
            if (announce) pieces?.inUse(piece)
        }
        return source.also { listeners.forEach(it::addTransferListener) }
    }

    private fun pathName(multiPath: Boolean) = if (multiPath) "multi" else "single"

    override fun getUri(): Uri? = current?.uri ?: spec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        dropPending()
        dropPrestart()
        val source = current
        val wasMulti = multiActive
        current = null
        setMulti(false)
        try {
            source?.close()
        } finally {
            if (wasMulti && downloads() <= 1) onMultiClosed()
        }
    }

    class Factory(
        private val single: DataSource.Factory,
        private val multi: DataSource.Factory,
        private val pieceBytes: PieceBytes,
        private val downloads: () -> Int,
        private val received: AtomicLong,
        private val onMultiClosed: () -> Unit = {},
        private val multiOpen: AtomicInteger = AtomicInteger(),
        private val onFirstByte: (Long) -> Unit = {},
        private val onOpened: (String?) -> Unit = {},
        private val pieces: PieceSwitch? = null
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            AutoSpeedupDataSource(single, multi, pieceBytes, downloads, received, onMultiClosed, multiOpen, onFirstByte,
                onOpened, pieces)
    }

    companion object {
        private const val TAG = "AutoSpeedup"
        const val MIN_RUN_BYTES = 4L * 1024L * 1024L
        const val RETRY_AFTER_BYTES = 16L * 1024L * 1024L
        /** The new size's first piece is fetched this many old pieces before the change. */
        const val PRESTART_PIECES = 2
        private const val MB = 1024L * 1024L

        fun lcm(a: Long, b: Long): Long {
            var x = a
            var y = b
            while (y != 0L) {
                val t = x % y
                x = y
                y = t
            }
            return a / x * b
        }
        private const val SCRATCH_BYTES = 64 * 1024
        private val OPENER = Executors.newSingleThreadExecutor { r ->
            Thread(r, "auto-speedup-open").apply { isDaemon = true }
        }
    }
}
