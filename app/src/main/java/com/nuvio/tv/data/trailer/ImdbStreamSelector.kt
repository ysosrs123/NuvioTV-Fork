package com.nuvio.tv.data.trailer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class ImdbStreamSelector @Inject constructor() {
    private val client = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS).callTimeout(2, TimeUnit.SECONDS).build()

    internal data class Selection(val source: TrailerPlaybackSource?, val complete: Boolean)

    internal suspend fun select(
        video: ImdbTrailerData.Video,
        loadManifest: suspend (String) -> String? = { fetch(it) }
    ): Selection = withContext(Dispatchers.IO) {
        val mp4 = ImdbTrailerData.bestMp4(video)
        // 4K progressive needs no extra request. Prefer progressive at equal resolution for startup.
        if (mp4 != null && mp4.height >= 2160) return@withContext Selection(TrailerPlaybackSource(mp4.url), true)
        var best = mp4?.let { TrailerPlaybackSource(it.url) }
        var height = mp4?.height ?: 0
        var complete = true
        // Keep manifest work bounded for preview latency. IMDb ordinarily supplies a single master.
        val finished = withTimeoutOrNull(2_000L) {
            for (encoding in video.encodings.filter { it.hls }.distinctBy { it.url }.take(2)) {
                val manifest = loadManifest(encoding.url)
                if (manifest == null) { complete = false; continue }
                val manifestHeight = highestManifestResolution(manifest)
                if (manifestHeight == 0) complete = false
                if (manifestHeight >= 720 && manifestHeight > height) {
                    // Return the master to retain external audio groups; the shared track selector
                    // already requests the highest supported video bitrate without a size cap.
                    best = TrailerPlaybackSource(encoding.url)
                    height = manifestHeight
                }
            }
            true
        }
        Selection(best, complete && finished == true)
    }

    private suspend fun fetch(url: String): String? = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(null)
            }
            override fun onResponse(call: Call, response: Response) {
                val body = response.use {
                    if (!it.isSuccessful) null else runCatching {
                        // A master is small; never consume an unexpected video/error document.
                        val stream = it.body?.byteStream()
                        if (stream == null) null else {
                            val buffer = ByteArray(8 * 1024)
                            val output = java.io.ByteArrayOutputStream()
                            while (output.size() <= 256 * 1024) {
                                val read = stream.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                            }
                            if (output.size() > 256 * 1024) null else output.toString("UTF-8")
                        }
                    }.getOrNull()
                }
                if (continuation.isActive) continuation.resume(body)
            }
        })
    }

    internal companion object {
        fun highestManifestResolution(manifest: String): Int {
            if (!manifest.trimStart().startsWith("#EXTM3U")) return 0
            val lines = manifest.lineSequence().map { it.trim() }.toList()
            return lines.indices.filter { lines[it].startsWith("#EXT-X-STREAM-INF:") }
                .filter { index -> lines.getOrNull(index + 1)?.let { it.isNotEmpty() && !it.startsWith('#') } == true }
                .mapNotNull { index -> Regex("(?:[:,])RESOLUTION=\\d+x(\\d+)(?:,|$)").find(lines[index])?.groupValues?.get(1)?.toIntOrNull() }
                .maxOrNull() ?: 0
        }
    }
}
