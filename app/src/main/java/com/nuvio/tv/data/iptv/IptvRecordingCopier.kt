package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.RecordingClock
import com.nuvio.tv.core.iptv.RecordingFailure
import com.nuvio.tv.core.iptv.RecordingPlaylist
import com.nuvio.tv.core.iptv.RecordingPlaylistParser
import com.nuvio.tv.core.iptv.RecordingRetry
import com.nuvio.tv.core.iptv.RecordingSegment
import com.nuvio.tv.core.iptv.RecordingSegmentCursor
import com.nuvio.tv.core.iptv.RecordingStorage
import com.nuvio.tv.core.iptv.RecordingStreamException
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource

class IptvRecordingProgress {
    @Volatile var bytes: Long = 0
        internal set
    @Volatile var gaps: Int = 0
        internal set
    @Volatile var committed: Long = 0
        internal set
    @Volatile var parts: Int = 1
        internal set
    val clock = RecordingClock()
}

data class IptvRecordingCopy(val bytes: Long, val gaps: Int, val failure: RecordingFailure?, val parts: Int = 1)

class IptvRecordingCopier(
    private val client: OkHttpClient = newClient(),
    private val now: () -> Long = System::currentTimeMillis,
    private val freeBytes: (File) -> Long = { it.usableSpace },
    private val reserveBytes: Long = RecordingStorage.RESERVE_BYTES,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val maxSegmentBytes: Long = 256L * 1024 * 1024,
    private val minimumStallMillis: Long = 30_000,
    private val halt: () -> RecordingFailure? = { null },
) {
    init { require(reserveBytes >= 0 && maxSegmentBytes > 0 && minimumStallMillis > 0) }

    private class Deadline(val stopAtMillis: Long) {
        @Volatile var reached = false
    }

    private inner class Sink(private val output: IptvRecordingOutput, private val progress: IptvRecordingProgress) {
        private var sinceCheck = 0L
        var hls = false
        val position: Long get() = progress.bytes

        fun write(buffer: ByteArray, length: Int) {
            if (length <= 0) return
            if (progress.bytes == 0L) checkSpace()
            val start = output.bytes
            try { output.write(buffer, 0, length, split = !hls) } catch (full: IptvRecordingPartFullException) {
                progress.bytes = output.bytes
                throw full
            } catch (_: IOException) { throw RecordingStreamException(RecordingFailure.STORAGE_ERROR) }
            finally { (output.bytes - start).toInt().takeIf { it > 0 }?.let { progress.clock.feed(start, buffer, 0, it) } }
            progress.bytes = output.bytes
            progress.parts = output.parts
            if (!hls) progress.committed = output.bytes
            sinceCheck += length
            if (sinceCheck >= RecordingStorage.CHECK_INTERVAL_BYTES) { sinceCheck = 0; checkSpace() }
        }

        fun beginSegment() {
            try { output.beforeSegment() } catch (_: IOException) { throw RecordingStreamException(RecordingFailure.STORAGE_ERROR) }
            progress.parts = output.parts
        }

        fun commit() { progress.committed = output.bytes }

        fun cut() = progress.clock.cut(output.bytes)

        fun rollback(position: Long) {
            if (position >= progress.bytes) return
            if (output.rollback(position)) { progress.bytes = output.bytes; progress.clock.rollback(output.bytes) }
        }

        fun sync() = output.sync()

        private fun checkSpace() {
            val free = try { freeBytes(output.directory) } catch (_: Exception) { Long.MAX_VALUE }
            if (!RecordingStorage.canContinue(free, reserveBytes)) throw RecordingStreamException(RecordingFailure.LOW_STORAGE)
            halt()?.let { throw RecordingStreamException(it) }
        }
    }

    suspend fun copy(address: String, format: IptvStreamFormat, output: File, stopAtMillis: Long,
        progress: IptvRecordingProgress = IptvRecordingProgress()): IptvRecordingCopy {
        val directory = output.absoluteFile.parentFile ?: return IptvRecordingCopy(0, 0, RecordingFailure.STORAGE_ERROR)
        return copy(address, format, IptvRecordingOutput(directory, { index, _ -> if (index == 1) output.absoluteFile else File(directory, "${output.name}.$index") }),
            stopAtMillis, progress)
    }

    suspend fun copy(address: String, format: IptvStreamFormat, output: IptvRecordingOutput, stopAtMillis: Long,
        progress: IptvRecordingProgress = IptvRecordingProgress()): IptvRecordingCopy = withContext(Dispatchers.IO) {
        val url = address.toHttpUrlOrNull() ?: return@withContext IptvRecordingCopy(0, 0, RecordingFailure.SOURCE_UNAVAILABLE)
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return@withContext IptvRecordingCopy(0, 0, RecordingFailure.SOURCE_UNAVAILABLE)
        try { output.open() } catch (_: IOException) { return@withContext IptvRecordingCopy(0, 0, RecordingFailure.STORAGE_ERROR) }
        progress.bytes = output.bytes
        progress.committed = output.bytes
        progress.parts = output.parts
        progress.clock.cut(output.bytes)
        val cursor = RecordingSegmentCursor()
        var failure: RecordingFailure? = null
        output.use {
            val sink = Sink(output, progress)
            try {
                var attempt = 0
                var received = progress.bytes > 0
                while (now() < stopAtMillis) {
                    currentCoroutineContext().ensureActive()
                    val before = progress.bytes
                    val deadline = Deadline(stopAtMillis)
                    var ended = false
                    try {
                        ended = session(url.toString(), format, sink, deadline, cursor, progress)
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (error: RecordingStreamException) {
                        if (error.failure != RecordingFailure.NETWORK) { failure = error.failure; break }
                    } catch (error: IOException) {
                        if (error !is IptvRecordingPartFullException && !deadline.reached && now() < stopAtMillis && currentCoroutineContext().isActive) {
                            IptvLog.failure("recording connection", error)
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    if (ended || deadline.reached || now() >= stopAtMillis) break
                    val gotData = progress.bytes > before
                    if (gotData) { received = true; attempt = 0; if (!sink.hls) progress.gaps += 1 }
                    if (RecordingRetry.giveUp(attempt, received)) { failure = RecordingFailure.NETWORK; break }
                    val wait = minOf(RecordingRetry.delayMillis(attempt), stopAtMillis - now())
                    attempt += 1
                    if (wait > 0) pause(wait)
                }
            } finally {
                sink.sync()
                client.dispatcher.cancelAll()
                client.connectionPool.evictAll()
            }
        }
        IptvRecordingCopy(progress.bytes, progress.gaps, failure, progress.parts)
    }

    private suspend fun session(address: String, format: IptvStreamFormat, sink: Sink, deadline: Deadline,
        cursor: RecordingSegmentCursor, progress: IptvRecordingProgress): Boolean = call<SessionResult>(address, deadline) { response ->
        val body = response.body
        val source = body.source()
        val type = body.contentType()?.toString()?.lowercase().orEmpty()
        val playlist = format == IptvStreamFormat.HLS || "mpegurl" in type ||
            (format != IptvStreamFormat.MPEG_TS && source.request(7) && source.buffer.snapshot(7).utf8().let { it == "#EXTM3U" || it.startsWith("﻿#EXT") })
        if (playlist) {
            sink.hls = true
            val parsed = RecordingPlaylistParser.parse(text(source), response.request.url.toUri())
            return@call Pending(parsed, response.request.url.toUri())
        }
        sink.hls = false
        val offset = syncOffset(source)
        if (offset > 0) source.skip(offset)
        sink.cut()
        val buffer = ByteArray(COPY_BUFFER)
        while (true) {
            if (deadline.reached || now() >= deadline.stopAtMillis) return@call Finished(false)
            val size = try { source.read(buffer) } catch (error: IOException) { if (deadline.reached) return@call Finished(false); throw error }
            if (size < 0) return@call Finished(false)
            sink.write(buffer, size)
        }
        @Suppress("UNREACHABLE_CODE") Finished(false)
    }.let { result ->
        when (result) {
            is Finished -> result.ended
            is Pending -> hls(result.playlist, address, sink, deadline, cursor, progress)
        }
    }

    private sealed interface SessionResult
    private class Finished(val ended: Boolean) : SessionResult
    private class Pending(val playlist: RecordingPlaylist, val base: URI) : SessionResult

    private suspend fun hls(first: RecordingPlaylist, address: String, sink: Sink, deadline: Deadline,
        cursor: RecordingSegmentCursor, progress: IptvRecordingProgress): Boolean {
        var location = address
        var playlist = first
        if (playlist is RecordingPlaylist.Variants) {
            location = playlist.addresses.first().toString()
            playlist = fetchPlaylist(location, deadline)
            if (playlist !is RecordingPlaylist.Media) throw RecordingStreamException(RecordingFailure.UNSUPPORTED_STREAM)
        }
        var lastSegmentAt = monotonic()
        while (!deadline.reached && now() < deadline.stopAtMillis) {
            val media = playlist as? RecordingPlaylist.Media ?: throw RecordingStreamException(RecordingFailure.UNSUPPORTED_STREAM)
            val step = cursor.next(media)
            if (step.gap) { progress.gaps += 1; sink.cut() }
            for (segment in step.segments) {
                if (deadline.reached || now() >= deadline.stopAtMillis) return false
                if (segment.discontinuity) sink.cut()
                append(segment, sink, deadline)
                if (deadline.reached) return false
                cursor.appended(segment)
                lastSegmentAt = monotonic()
            }
            if (media.ended && (media.segments.isEmpty() || cursor.lastSequence == media.segments.last().sequence)) return true
            if (monotonic() - lastSegmentAt > maxOf(media.targetMs * 3, minimumStallMillis)) throw IOException("Playlist stalled")
            val wait = minOf(if (step.segments.isEmpty()) media.targetMs / 2 else media.targetMs, deadline.stopAtMillis - now())
            if (wait > 0) pause(wait)
            if (deadline.reached || now() >= deadline.stopAtMillis) return false
            playlist = fetchPlaylist(location, deadline)
        }
        return false
    }

    private suspend fun fetchPlaylist(address: String, deadline: Deadline): RecordingPlaylist = call(address, deadline) { response ->
        RecordingPlaylistParser.parse(text(response.body.source()), response.request.url.toUri())
    }

    private suspend fun append(segment: RecordingSegment, sink: Sink, deadline: Deadline) {
        sink.beginSegment()
        val mark = sink.position
        try {
            call(segment.address.toString(), deadline) { response ->
                val source = response.body.source()
                if (!source.request(1)) throw IOException("Empty segment")
                if (source.buffer[0] != SYNC) throw RecordingStreamException(RecordingFailure.UNSUPPORTED_STREAM)
                val declared = response.body.contentLength()
                if (declared > maxSegmentBytes) throw RecordingStreamException(RecordingFailure.UNSUPPORTED_STREAM)
                val buffer = ByteArray(COPY_BUFFER)
                var count = 0L
                while (true) {
                    val size = source.read(buffer)
                    if (size < 0) break
                    count += size
                    if (count > maxSegmentBytes) throw RecordingStreamException(RecordingFailure.UNSUPPORTED_STREAM)
                    sink.write(buffer, size)
                }
                if (declared >= 0 && count != declared) throw IOException("Short segment")
            }
            sink.commit()
        } catch (error: Throwable) {
            sink.rollback(mark)
            throw error
        }
    }

    private suspend fun <T> call(address: String, deadline: Deadline, block: (Response) -> T): T = coroutineScope {
        val url = address.toHttpUrlOrNull() ?: throw RecordingStreamException(RecordingFailure.UNSUPPORTED_STREAM)
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept-Encoding", "identity").build())
        val watcher = launch {
            try {
                delay((deadline.stopAtMillis - now()).coerceAtLeast(0))
                deadline.reached = true
            } finally { call.cancel() }
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val encoding = response.header("Content-Encoding")
                if (encoding != null && !encoding.equals("identity", ignoreCase = true)) throw RecordingStreamException(RecordingFailure.UNSUPPORTED_STREAM)
                block(response)
            }
        } finally { watcher.cancel() }
    }

    private fun text(source: BufferedSource): String {
        val buffer = Buffer()
        while (buffer.size <= RecordingPlaylistParser.MAX_BYTES) {
            if (source.read(buffer, 8192) < 0) return buffer.readUtf8()
        }
        throw RecordingStreamException(RecordingFailure.UNSUPPORTED_STREAM)
    }

    private fun syncOffset(source: BufferedSource): Long {
        if (!source.request(1)) throw IOException("Empty stream")
        source.request(SYNC_SCAN + PACKET.toLong() + 1)
        val available = source.buffer.size
        for (offset in 0L until minOf(SYNC_SCAN, available)) {
            if (source.buffer[offset] != SYNC) continue
            if (offset + PACKET >= available || source.buffer[offset + PACKET] == SYNC) return offset
        }
        throw RecordingStreamException(RecordingFailure.UNSUPPORTED_STREAM)
    }

    private fun monotonic(): Long = System.nanoTime() / 1_000_000

    companion object {
        private const val USER_AGENT = "Nuvio-Live/1"
        private const val COPY_BUFFER = 64 * 1024
        private const val PACKET = 188
        private const val SYNC_SCAN = 1024L
        private const val SYNC: Byte = 0x47

        fun newClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).followRedirects(true).followSslRedirects(true).build()
    }
}
