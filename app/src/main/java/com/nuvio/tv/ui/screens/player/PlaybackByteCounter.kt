package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/** Active owner only. Factories capture a session rather than writing to resettable global totals. */
internal object PlaybackByteCounter {
    @Volatile private var current = PlaybackTransferSession(null, PlaybackTransferCoverage.UNAVAILABLE)

    fun beginSource(url: String, coverage: PlaybackTransferCoverage): PlaybackTransferSession =
        PlaybackTransferSession(url, coverage).also { current = it }

    fun reset() { current = PlaybackTransferSession(null, PlaybackTransferCoverage.UNAVAILABLE) }
    fun snapshot(): PlaybackTransferSnapshot = current.snapshot(SystemClock::elapsedRealtime)
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
internal class CountingDataSource(private val upstream: DataSource, private val owner: PlaybackTransferSession) : DataSource by upstream {
    @Volatile private var isMediaRequest = false
    init { upstream.addTransferListener(PlaybackTransferListener(owner) { isMediaRequest }) }
    override fun open(dataSpec: DataSpec): Long {
        // Includes unmarked ancillary URIs that legitimately use the generic source path.
        // Child range/redirect requests inherit this root request's ownership.
        isMediaRequest = dataSpec.uri.toString() == owner.sourceUrl
        val length = upstream.open(dataSpec)
        val encoding = upstream.responseHeaders.entries.firstOrNull { it.key.equals("Content-Encoding", true) }?.value
        owner.recordLength(dataSpec.uri.toString(), dataSpec.position, length,
            dataSpec.length == C.LENGTH_UNSET.toLong(), encoding.isNullOrEmpty() || encoding.all { it.equals("identity", true) })
        return length
    }
    override fun close() = upstream.close()
}

/** Install before subtitle routing: ancillary bodies cannot set media size, endpoint or counters. */
@UnstableApi
internal class CountingDataSourceFactory(private val upstream: DataSource.Factory, private val owner: PlaybackTransferSession) : DataSource.Factory {
    override fun createDataSource(): DataSource = CountingDataSource(upstream.createDataSource(), owner)
}
