package com.nuvio.tv.core.image

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import coil3.BitmapImage
import coil3.EventListener
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.imageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import coil3.size.Size
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import java.util.concurrent.atomic.AtomicLong
import okio.ByteString.Companion.encodeUtf8

/** Opt-in local diagnostics: adb shell setprop log.tag.NuvioArtwork VERBOSE.
 * Disabled by default, no URL paths, credentials, titles or response bodies are logged.
 * Bounds inspection is diagnostic work; disable the tag for clean frame measurements.
 */
object ArtworkDiagnostics {
    private const val TAG = "NuvioArtwork"
    private val ids = AtomicLong()
    fun enabled() = Log.isLoggable(TAG, Log.VERBOSE)
    fun key(value: String?) = value?.encodeUtf8()?.sha256()?.hex()?.take(16) ?: "none"
    private fun host(value: String) = value.toHttpUrlOrNull()?.host ?: "local"
    fun log(message: String) { Log.i(TAG, message) }

    val imageEvents = EventListener.Factory { request ->
        if (!enabled()) EventListener.NONE else object : EventListener() {
            private val id = ids.incrementAndGet()
            private val start = SystemClock.elapsedRealtime()
            private var decodeStart = 0L
            private fun emit(event: String) = log("image=$id $event")
            override fun onStart(request: ImageRequest) {
                emit("start url=${key(request.data.toString())} host=${host(request.data.toString())} key=${key(request.memoryCacheKey)} network=${request.networkCachePolicy}")
            }
            override fun resolveSizeEnd(request: ImageRequest, size: Size) { emit("target=$size") }
            override fun mapEnd(request: ImageRequest, output: Any) { emit("mapped=${key(output.toString())} host=${host(output.toString())}") }
            override fun fetchEnd(request: ImageRequest, fetcher: Fetcher, options: Options, result: FetchResult?) {
                if (result is SourceFetchResult) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    runCatching { result.source.source().peek().inputStream().use { BitmapFactory.decodeStream(it, null, bounds) } }
                    emit("fetch=${result.dataSource} encoded=${bounds.outWidth}x${bounds.outHeight}")
                }
            }
            override fun decodeStart(request: ImageRequest, decoder: Decoder, options: Options) { decodeStart = SystemClock.elapsedRealtime() }
            override fun decodeEnd(request: ImageRequest, decoder: Decoder, options: Options, result: DecodeResult?) {
                if (result == null) return
                emit("decodeMs=${SystemClock.elapsedRealtime() - decodeStart} pixels=${result.image.width}x${result.image.height} bytes=${result.image.size}")
            }
            override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                val cache = request.context.imageLoader.memoryCache
                emit("success=${result.dataSource} elapsedMs=${SystemClock.elapsedRealtime() - start} pixels=${result.image.width}x${result.image.height} bytes=${result.image.size} config=${(result.image as? BitmapImage)?.bitmap?.config} mem=${cache?.size}/${cache?.maxSize} key=${key(result.memoryCacheKey.toString())} disk=${key(result.diskCacheKey)}")
            }
            override fun onCancel(request: ImageRequest) { emit("cancel elapsedMs=${SystemClock.elapsedRealtime() - start}") }
            override fun onError(request: ImageRequest, result: ErrorResult) { emit("error=${result.throwable.javaClass.simpleName} elapsedMs=${SystemClock.elapsedRealtime() - start}") }
        }
    }

    val networkEvents = okhttp3.EventListener.Factory { call ->
        if (!enabled()) okhttp3.EventListener.NONE else object : okhttp3.EventListener() {
            private val id = ids.incrementAndGet()
            private val url = key(call.request().url.toString())
            override fun requestHeadersEnd(call: Call, request: okhttp3.Request) {
                log("http=$id request url=$url host=${request.url.host} conditional=${request.header("If-None-Match") != null || request.header("If-Modified-Since") != null}")
            }
            override fun responseHeadersEnd(call: Call, response: Response) {
                val cc = response.cacheControl
                log("http=$id response url=$url code=${response.code} length=${response.body?.contentLength()} maxAge=${cc.maxAgeSeconds} noCache=${cc.noCache} noStore=${cc.noStore} httpCache=${response.cacheResponse != null}")
            }
            override fun responseBodyEnd(call: Call, byteCount: Long) { log("http=$id body url=$url bytes=$byteCount") }
            override fun callFailed(call: Call, ioe: java.io.IOException) { log("http=$id failed url=$url kind=${ioe.javaClass.simpleName}") }
        }
    }
}
