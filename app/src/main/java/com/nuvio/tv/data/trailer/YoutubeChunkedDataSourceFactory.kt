package com.nuvio.tv.data.trailer

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener

/**
 * A DataSource.Factory that wraps DefaultHttpDataSource and appends YouTube's
 * `&range=start-end` query parameter on each request. YouTube throttles (and
 * kills) connections that try to download full adaptive streams in one shot,
 * but honours chunked range-param requests at full speed.
 *
 * Only activates for googlevideo.com URLs; all other URLs pass through untouched.
 */
@UnstableApi
class YoutubeChunkedDataSourceFactory(
    private val chunkSizeBytes: Long = CHUNK_SIZE
) : DataSource.Factory {

    companion object {
        private const val TAG = "YTChunkedDS"
        /** 10 MB chunks – large enough to avoid too many requests, small enough to dodge throttle. */
        private const val CHUNK_SIZE = 10L * 1024 * 1024
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
    }

    override fun createDataSource(): DataSource {
        val upstream = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)
            .createDataSource()
        return YoutubeChunkedDataSource(upstream, chunkSizeBytes)
    }

    private class YoutubeChunkedDataSource(
        private val upstream: DefaultHttpDataSource,
        private val chunkSize: Long
    ) : DataSource {

        private var currentUri: Uri? = null
        private var isYouTubeStream = false
        private var originalDataSpec: DataSpec? = null
        private val chunks = YoutubeChunkReader(
            chunkSize = chunkSize,
            openChunk = ::openChunk,
            readChunk = upstream::read,
            closeChunk = upstream::close
        )

        override fun addTransferListener(transferListener: TransferListener) {
            upstream.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val uri = dataSpec.uri
            val host = uri.host.orEmpty()
            isYouTubeStream = host.contains("googlevideo.com")

            if (!isYouTubeStream) {
                return upstream.open(dataSpec)
            }

            originalDataSpec = dataSpec
            chunks.open(dataSpec.position, dataSpec.length)
            return dataSpec.length
        }

        /** False when the server says the range starts past the end of the stream. */
        private fun openChunk(start: Long, end: Long): Boolean {
            val spec = originalDataSpec ?: throw IllegalStateException("No DataSpec")

            // Append &range=start-end to the URL (YouTube's own range param, not HTTP Range header)
            val rangedUri = spec.uri.buildUpon()
                .appendQueryParameter("range", "$start-$end")
                .build()

            val chunkedSpec = spec.buildUpon()
                .setUri(rangedUri)
                .setPosition(0)           // position within this chunk's response
                .setLength(C.LENGTH_UNSET.toLong()) // let the server decide
                .build()

            return try {
                upstream.open(chunkedSpec)
                true
            } catch (e: HttpDataSource.InvalidResponseCodeException) {
                if (e.responseCode == HTTP_RANGE_NOT_SATISFIABLE) return false
                Log.w(TAG, "Failed to open chunk at $start: ${e.message}")
                throw e
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!isYouTubeStream) {
                return upstream.read(buffer, offset, length)
            }
            return chunks.read(buffer, offset, length)
        }

        override fun getUri(): Uri? = upstream.uri ?: currentUri

        override fun close() {
            upstream.close()
            currentUri = null
            originalDataSpec = null
        }
    }
}
