package com.nuvio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackTransferDataSourceTest {
    private fun uri(text: String): Uri {
        val value = mockk<Uri>(relaxed = true)
        every { value.toString() } returns text
        return value
    }

    @Test fun `factory owns one leaf listener and read is not counted a second time`() {
        val url = "https://source.example/movie"
        val owner = PlaybackTransferSession(url, PlaybackTransferCoverage.PROGRESSIVE)
        val upstream = mockk<DataSource>()
        val listener = slot<TransferListener>()
        every { upstream.addTransferListener(capture(listener)) } just Runs
        every { upstream.uri } returns uri("https://cdn.example:8443/movie")
        every { upstream.responseHeaders } returns emptyMap()
        every { upstream.close() } just Runs
        val spec = DataSpec.Builder().setUri(uri(url)).build()
        every { upstream.open(spec) } answers { listener.captured.onTransferStart(upstream, spec, true); 100L }
        every { upstream.read(any(), any(), any()) } answers {
            listener.captured.onBytesTransferred(upstream, spec, true, 30); 30
        }
        val counted = CountingDataSourceFactory(DataSource.Factory { upstream }, owner).createDataSource()
        assertEquals(100L, counted.open(spec))
        assertEquals(30, counted.read(ByteArray(30), 0, 30))
        counted.close()
        val sample = owner.snapshot { 100 }
        assertEquals(30L, sample.readBytes)
        assertEquals(30L, sample.networkBytes)
        assertEquals(100L, sample.contentLength)
        assertEquals(8443, sample.endpoint?.port)
        verify(exactly = 1) { upstream.addTransferListener(any()); upstream.close() }
    }

    @Test fun `late listener cannot update replacement source and local reads do not change endpoint`() {
        val url = "https://source.example/movie"
        val old = PlaybackByteCounter.beginSource(url, PlaybackTransferCoverage.PROGRESSIVE)
        val listener = PlaybackTransferListener(old) { true }
        val current = PlaybackByteCounter.beginSource(url, PlaybackTransferCoverage.PROGRESSIVE)
        val source = mockk<DataSource>(); every { source.uri } returns uri("https://old.example/movie")
        val spec = DataSpec.Builder().setUri(uri(url)).build()
        listener.onTransferStart(source, spec, true)
        listener.onBytesTransferred(source, spec, true, 100)
        assertNull(current.snapshot { 0 }.endpoint)
        assertEquals(0L, current.snapshot { 0 }.networkBytes)
        PlaybackTransferListener(current) { true }.onTransferStart(source, spec, false)
        assertNull(current.snapshot { 0 }.endpoint)
        PlaybackByteCounter.reset()
        assertNull(PlaybackByteCounter.contentLengthFor(url))
    }
    @Test fun `unmarked ancillary open cannot update media endpoint bytes or size`() {
        val owner = PlaybackTransferSession("https://source.example/movie", PlaybackTransferCoverage.PROGRESSIVE)
        val upstream = mockk<DataSource>()
        val listener = slot<TransferListener>()
        every { upstream.addTransferListener(capture(listener)) } just Runs
        every { upstream.uri } returns uri("https://subtitles.example/file.srt")
        every { upstream.responseHeaders } returns emptyMap()
        val spec = DataSpec.Builder().setUri(uri("https://subtitles.example/file.srt")).build()
        every { upstream.open(spec) } answers {
            listener.captured.onTransferStart(upstream, spec, true)
            listener.captured.onBytesTransferred(upstream, spec, true, 900)
            900L
        }
        val counted = CountingDataSourceFactory(DataSource.Factory { upstream }, owner).createDataSource()
        assertEquals(900L, counted.open(spec))
        val snapshot = owner.snapshot { 0 }
        assertNull(snapshot.endpoint); assertNull(snapshot.contentLength)
        assertEquals(0L, snapshot.networkBytes)
    }
}
