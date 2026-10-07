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
import com.nuvio.tv.data.iptv.IptvVodLease
import com.nuvio.tv.data.iptv.IptvVodPlayback
import com.nuvio.tv.data.iptv.IptvVodResolution
import com.nuvio.tv.data.iptv.IptvVodResolver
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer

private const val IPTV_VOD_TAG = "NuvioIptvVod"

internal class IptvVodPlaybackException(message: String) : IOException(message)

internal fun Throwable.iptvVodPlaybackFailure(): IptvVodPlaybackException? {
    var current: Throwable? = this
    while (current != null) {
        if (current is IptvVodPlaybackException) return current
        current = current.cause
    }
    return null
}

internal fun PlayerRuntimeController.surfaceIptvVodFailure(message: String) {
    Log.w(IPTV_VOD_TAG, "playback stopped by provider limit")
    finishLoadingDiagnostics("iptv_vod_refused")
    cancelNextEpisodeAutoPlayOnFatalError()
    _uiState.update {
        it.copy(error = message, showSwitchToMpvErrorAction = false, showLoadingOverlay = false, showPauseOverlay = false,
            isBuffering = false, loadingIssueReportVisible = false, loadingIssueElapsedMs = 0L, playbackEnded = false, postPlayMode = null)
    }
}

internal fun PlayerRuntimeController.awaitIptvVodForMpv(url: String, resume: () -> Unit): Boolean {
    val ref = VodRef.parse(url) ?: return false
    if (mediaSourceFactory.iptvVodSession.ready(ref) != null) return false
    if (iptvVodMpvJob?.isActive == true) return true
    setLoadingStatus(phase = "iptv_vod_resolve", message = context.getString(R.string.player_loading_starting), showOverlay = true)
    iptvVodMpvJob = scope.launch {
        val failure = try {
            mediaSourceFactory.iptvVodSession.resolve(ref)
            null
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            error
        }
        iptvVodMpvJob = null
        if (currentStreamUrl != url || !isUsingMpvEngine()) return@launch
        when {
            failure == null -> resume()
            failure is IptvVodPlaybackException -> surfaceIptvVodFailure(failure.message.orEmpty())
            else -> handleMpvPlaybackError(source = "iptv_vod", detailedErrorOverride = failure?.message)
        }
    }
    return true
}

internal fun PlayerRuntimeController.iptvVodMpvMedia(url: String, headers: Map<String, String>): Pair<String, Map<String, String>> {
    val playback = VodRef.parse(url)?.let(mediaSourceFactory.iptvVodSession::ready) ?: return url to headers
    return playback.url to (headers + playback.headers)
}

internal class IptvVodSession(context: Context) {
    private val appContext = context.applicationContext
    private val resolving = Any()
    private var current: IptvVodPlayback? = null
    private var lease: IptvVodLease? = null
    private var generation = 0L
    private var opened = 0

    fun playback(ref: VodRef): IptvVodPlayback = synchronized(resolving) {
        held(ref) ?: try {
            runBlocking { resolve(ref) }
        } catch (_: InterruptedException) {
            throw InterruptedIOException()
        }
    }

    suspend fun resolve(ref: VodRef): IptvVodPlayback {
        held(ref)?.let { return it }
        val (known, started) = prepare(ref)
        return when (val result = resolver(appContext).acquire(ref, known)) {
            is IptvVodResolution.Ready -> adopt(result, started)
            is IptvVodResolution.Busy -> throw IptvVodPlaybackException(appContext.getString(R.string.iptv_vod_connections_busy, result.sourceLabel))
            IptvVodResolution.Unavailable -> throw IOException(appContext.getString(R.string.iptv_vod_unavailable))
        }
    }

    fun ready(ref: VodRef): IptvVodPlayback? = held(ref)

    fun headers(): Map<String, String> = synchronized(this) { current?.headers.orEmpty() }

    fun refused(): IptvVodPlaybackException = IptvVodPlaybackException(appContext.getString(R.string.iptv_vod_provider_refused))

    fun opened(): Long = synchronized(this) { opened++; generation }

    fun closed(started: Long) {
        synchronized(this) { if (started == generation) opened = (opened - 1).coerceAtLeast(0) }
    }

    fun failed() {
        synchronized(this) { if (opened > 0) null else lease.also { lease = null } }?.close()
    }

    fun release() {
        synchronized(this) {
            generation++
            opened = 0
            lease.also { lease = null }
        }?.close()
    }

    @Synchronized private fun held(ref: VodRef): IptvVodPlayback? = current?.takeIf { it.ref == ref && lease?.active == true }

    private fun prepare(ref: VodRef): Pair<IptvVodPlayback?, Long> {
        val stale = synchronized(this) {
            if (current?.ref == ref) null else { current = null; lease.also { lease = null } }
        }
        stale?.close()
        return synchronized(this) { current to generation }
    }

    private fun adopt(result: IptvVodResolution.Ready, started: Long): IptvVodPlayback {
        val fresh = result.lease
        val replaced = synchronized(this) {
            if (generation != started) fresh
            else { val previous = lease; lease = fresh; current = result.playback; previous }
        }
        replaced?.close()
        if (fresh != null && replaced === fresh) throw InterruptedIOException()
        return result.playback
    }

    private fun resolver(context: Context): IptvVodResolver =
        EntryPointAccessors.fromApplication(context, IptvVodEntryPoint::class.java).iptvVodResolver()
}

@OptIn(UnstableApi::class)
internal class IptvVodDataSourceFactory(private val session: IptvVodSession, private val upstream: DataSource.Factory,
    private val fallbackUserAgent: String?) : DataSource.Factory {

    override fun createDataSource(): DataSource = Source(upstream.createDataSource())

    private fun withHeaders(spec: DataSpec, headers: Map<String, String>): DataSpec {
        val all = if (fallbackUserAgent == null || headers.keys.any { it.equals(USER_AGENT, ignoreCase = true) }) headers
            else headers + (USER_AGENT to fallbackUserAgent)
        return if (all.isEmpty()) spec else spec.withAdditionalHeaders(all)
    }

    private inner class Source(private val delegate: DataSource) : DataSource by delegate, ByteBufferDataReader {
        private var counted: Long? = null

        override fun supportsByteBufferRead(): Boolean = delegate is ByteBufferDataReader && delegate.supportsByteBufferRead()

        override fun read(buffer: ByteBuffer, length: Int): Int = (delegate as ByteBufferDataReader).read(buffer, length)

        override fun open(dataSpec: DataSpec): Long {
            val ref = VodRef.parse(dataSpec.uri.toString())
            val spec = if (ref == null) withHeaders(dataSpec, session.headers()) else {
                val playback = session.playback(ref)
                withHeaders(dataSpec.withUri(Uri.parse(playback.url)), playback.headers)
            }
            return try {
                delegate.open(spec).also { if (counted == null) counted = session.opened() }
            } catch (error: HttpDataSource.InvalidResponseCodeException) {
                session.failed()
                if (!VodStreams.providerRefusal(error.responseCode)) throw error
                Log.w(IPTV_VOD_TAG, "provider refused status=${error.responseCode}")
                throw session.refused()
            } catch (error: IOException) {
                session.failed()
                throw error
            }
        }

        override fun close() {
            try { delegate.close() } finally { counted?.let { counted = null; session.closed(it) } }
        }
    }

    private companion object {
        const val USER_AGENT = "User-Agent"
    }
}
