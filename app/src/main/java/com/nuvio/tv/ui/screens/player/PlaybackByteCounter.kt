package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import androidx.media3.common.ByteBufferDataReader
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.nio.ByteBuffer

/** Active owner only. Factories capture a session rather than writing to resettable global totals. */
internal object PlaybackByteCounter {
    @Volatile private var current = PlaybackTransferSession(null, PlaybackTransferCoverage.UNAVAILABLE)

    /** Called on the loading thread when the active source's first response gives its total length. */
    @Volatile var firstLengthListener: ((url: String, length: Long) -> Unit)? = null

    fun onFirstLength(owner: PlaybackTransferSession, length: Long) {
        val url = owner.sourceUrl ?: return
        if (owner === current) firstLengthListener?.invoke(url, length)
    }

    fun beginSource(url: String, coverage: PlaybackTransferCoverage): PlaybackTransferSession =
        PlaybackTransferSession(url, coverage).also { current = it }

    fun reset() { current = PlaybackTransferSession(null, PlaybackTransferCoverage.UNAVAILABLE) }
    fun snapshot(): PlaybackTransferSnapshot = current.snapshot(SystemClock::elapsedRealtime)
    /** Media bytes read for [url] since its source began, or null when this route is not counted. */
    fun mediaBytesFor(url: String?): Long? {
        val owner = current
        if (url == null || url != owner.sourceUrl || !owner.coverage.available) return null
        return owner.snapshot(SystemClock::elapsedRealtime).readBytes
    }
    fun contentLengthFor(url: String?): Long? {
        val owner = current
        return if (url != null && url == owner.sourceUrl) owner.snapshot(SystemClock::elapsedRealtime).contentLength else null
    }
}

@UnstableApi
internal class PlaybackTransferListener(private val owner: PlaybackTransferSession, private val isMediaRequest: () -> Boolean) : TransferListener {
    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        if (isMediaRequest() && isNetwork) owner.recordEndpoint(source.uri?.toString())
    }
    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
        if (isMediaRequest()) owner.recordBytes(bytesTransferred, isNetwork)
    }
    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
}

/** Counts forwarded leaf reads once; does not add a second counter to read(). */
@UnstableApi
internal class CountingDataSource(private val upstream: DataSource, private val owner: PlaybackTransferSession) :
    DataSource by upstream, ByteBufferDataReader {
    @Volatile private var isMediaRequest = false
    init { upstream.addTransferListener(PlaybackTransferListener(owner) { isMediaRequest }) }
    override fun open(dataSpec: DataSpec): Long {
        // Includes unmarked ancillary URIs that legitimately use the generic source path.
        // Child range/redirect requests inherit this root request's ownership.
        isMediaRequest = dataSpec.uri.toString() == owner.sourceUrl
        val length = upstream.open(dataSpec)
        val encoding = upstream.responseHeaders.entries.firstOrNull { it.key.equals("Content-Encoding", true) }?.value
        val firstLength = owner.recordLength(dataSpec.uri.toString(), dataSpec.position, length,
            dataSpec.length == C.LENGTH_UNSET.toLong(), encoding.isNullOrEmpty() || encoding.all { it.equals("identity", true) })
        if (firstLength) PlaybackByteCounter.onFirstLength(owner, dataSpec.position + length)
        return length
    }
    override fun supportsByteBufferRead(): Boolean =
        upstream is ByteBufferDataReader && upstream.supportsByteBufferRead()
    override fun read(buffer: ByteBuffer, length: Int): Int = (upstream as ByteBufferDataReader).read(buffer, length)
    override fun close() = upstream.close()
}

/** Install before subtitle routing: ancillary bodies cannot set media size, endpoint or counters. */
@UnstableApi
internal class CountingDataSourceFactory(private val upstream: DataSource.Factory, private val owner: PlaybackTransferSession) : DataSource.Factory {
    override fun createDataSource(): DataSource = CountingDataSource(upstream.createDataSource(), owner)
}
