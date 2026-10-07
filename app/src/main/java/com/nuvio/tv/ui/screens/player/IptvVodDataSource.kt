package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.ByteBufferDataReader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.nuvio.tv.R
import com.nuvio.tv.core.di.IptvVodEntryPoint
import com.nuvio.tv.core.iptv.VodRef
import com.nuvio.tv.core.iptv.VodStreams
import com.nuvio.tv.data.iptv.IptvVodPlayback
import com.nuvio.tv.data.iptv.IptvVodResolution
import com.nuvio.tv.data.iptv.IptvVodResolver
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer

@OptIn(UnstableApi::class)
internal class IptvVodDataSourceFactory(context: Context, private val upstream: DataSource.Factory) : DataSource.Factory {
    private val appContext = context.applicationContext
    @Volatile private var resolved: IptvVodPlayback? = null

    override fun createDataSource(): DataSource = Source(upstream.createDataSource())

    private fun playback(ref: VodRef): IptvVodPlayback {
        resolved?.takeIf { it.ref == ref }?.let { return it }
        val result = try {
            runBlocking { resolver(appContext).resolve(ref) }
        } catch (_: InterruptedException) {
            throw InterruptedIOException()
        }
        return when (result) {
            is IptvVodResolution.Ready -> result.playback.also { resolved = it }
            is IptvVodResolution.Busy -> throw IOException(appContext.getString(R.string.iptv_vod_connections_busy, result.sourceLabel))
            IptvVodResolution.Unavailable -> throw IOException(appContext.getString(R.string.iptv_vod_unavailable))
        }
    }

    private inner class Source(private val delegate: DataSource) : DataSource by delegate, ByteBufferDataReader {
        override fun supportsByteBufferRead(): Boolean = delegate is ByteBufferDataReader && delegate.supportsByteBufferRead()

        override fun read(buffer: ByteBuffer, length: Int): Int = (delegate as ByteBufferDataReader).read(buffer, length)

        override fun open(dataSpec: DataSpec): Long {
            val ref = VodRef.parse(dataSpec.uri.toString()) ?: return delegate.open(dataSpec)
            val playback = playback(ref)
            val spec = dataSpec.withUri(Uri.parse(playback.url)).let { if (playback.headers.isEmpty()) it else it.withAdditionalHeaders(playback.headers) }
            return try {
                delegate.open(spec)
            } catch (error: HttpDataSource.InvalidResponseCodeException) {
                if (!VodStreams.providerRefusal(error.responseCode)) throw error
                Log.w(TAG, "provider refused status=${error.responseCode}")
                throw IOException(appContext.getString(R.string.iptv_vod_provider_refused))
            }
        }
    }

    companion object {
        private const val TAG = "NuvioIptvVod"

        fun resolveForMpv(context: Context, url: String, headers: Map<String, String>): Pair<String, Map<String, String>> {
            val ref = VodRef.parse(url) ?: return url to headers
            val result = try {
                runBlocking(Dispatchers.IO) { resolver(context.applicationContext).resolve(ref) }
            } catch (error: Exception) {
                Log.w(TAG, "mpv resolve failed ${error.javaClass.simpleName}")
                null
            }
            val playback = (result as? IptvVodResolution.Ready)?.playback ?: return url to headers
            return playback.url to (headers + playback.headers)
        }

        private fun resolver(context: Context): IptvVodResolver =
            EntryPointAccessors.fromApplication(context, IptvVodEntryPoint::class.java).iptvVodResolver()
    }
}
