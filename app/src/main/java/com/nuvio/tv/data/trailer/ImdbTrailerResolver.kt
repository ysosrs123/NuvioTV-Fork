package com.nuvio.tv.data.trailer

import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.google.gson.JsonParser
import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class ImdbTrailerResolver @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val streamSelector: ImdbStreamSelector
) {
    private companion object {
        const val TAG = "ImdbTrailerResolver"
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"
        const val REFERER = "https://www.imdb.com/"
        const val POLL_MS = 150L
        const val TITLE_BUDGET_MS = 18_000L
        const val LOAD_ATTEMPTS = 2
        // Static sub-resources we never need for __NEXT_DATA__. Extension-only: never matches scripts,
        // XHR/fetch (extensionless), the main document, or the awsWaf challenge.
        val BLOCK_EXT = Regex("""\.(?:css|woff2?|ttf|otf|eot|jpe?g|png|gif|webp|bmp|ico|svg|mp4|m3u8|ts|mp3|aac)(?:[?#].*)?$""", RegexOption.IGNORE_CASE)
    }

    private val webViews = TrailerResourceGate(Dispatchers.Main)
    private val sourceCache = TrailerSourceCache()

    private class NetFlag { @Volatile var tripped = false }

    private data class Resolution(val source: TrailerPlaybackSource?, val definitive: Boolean)

    @Suppress("UNUSED_PARAMETER")
    suspend fun resolve(imdbId: String, type: String? = null): TrailerPlaybackSource? {
        if (!Regex("tt\\d+").matches(imdbId)) return null
        sourceCache.get(imdbId)?.let { return it.source }
        val started = SystemClock.elapsedRealtime()
        // Queue time is included. Transient failures may retry once in the same browser;
        // successfully parsed missing/SD-only heroes never reload the title three times.
        val result = withTimeoutOrNull(TITLE_BUDGET_MS) {
            val flag = NetFlag()
            webViews.use(
                create = { if (sourceCache.get(imdbId) == null) buildWebView(flag) else null },
                destroy = { wv ->
                    wv?.let {
                        runCatching { it.stopLoading() }
                        runCatching { it.destroy() }
                    }
                }
            ) { wv ->
                sourceCache.get(imdbId)?.let { return@use Resolution(it.source, true) }
                if (wv == null) return@use Resolution(null, false)
                repeat(LOAD_ATTEMPTS) { attempt ->
                    if (attempt > 0) wv.settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    val resolved = resolveInternal(imdbId, wv, flag)
                    if (resolved.definitive) {
                        sourceCache.put(imdbId, resolved.source,
                            if (resolved.source == null) 5 * 60_000L else 30 * 60_000L)
                        return@use resolved
                    }
                    if (attempt < LOAD_ATTEMPTS - 1) delay(150)
                }
                Resolution(null, false)
            }
        }
        Log.d(TAG, "resolve durationMs=${SystemClock.elapsedRealtime() - started} success=${result?.source != null} definitive=${result?.definitive == true}")
        return result?.source
    }

    fun invalidate(videoUrl: String) = sourceCache.invalidate(videoUrl)

    // Called inside webViews' main-thread resource scope, without a cancellable return handoff.
    private fun buildWebView(flag: NetFlag): WebView? {
        var created: WebView? = null
        return try {
            val wv = WebView(appContext).also { created = it }
            configureWebView(wv, flag)
            val w = 1280
            val h = 720
            wv.layoutParams = ViewGroup.LayoutParams(w, h)
            wv.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            )
            wv.layout(0, 0, w, h)
            wv.visibility = View.VISIBLE
            runCatching { wv.resumeTimers() }
            wv
        } catch (e: Exception) {
            created?.let { runCatching { it.destroy() } }
            Log.e(TAG, "application-context WebView construction failed: ${e.message}")
            null
        }
    }

    private suspend fun resolveInternal(imdbId: String, wv: WebView, flag: NetFlag): Resolution {
        val title = loadVideoData(wv, "https://www.imdb.com/title/$imdbId/", imdbId, true, flag)
        if (title == ImdbTrailerData.Result.Missing) return Resolution(null, true)
        val hero = (title as? ImdbTrailerData.Result.Found)?.video ?: return Resolution(null, false)
        streamSelector.select(hero).source?.let { source ->
            Log.i(TAG, "SELECTED ${hero.id} title-page")
            return Resolution(source.takeIf { TrailerSourceExpiry.isUsable(it) }, TrailerSourceExpiry.isUsable(source))
        }
        // A hero ID is sufficient: inline playbackURLs need not exist to open its video page.
        val video = loadVideoData(wv, "https://www.imdb.com/video/${hero.id}/", hero.id, false, flag)
        val info = (video as? ImdbTrailerData.Result.Found)?.video ?: return Resolution(null, false)
        val selection = streamSelector.select(info)
        val source = selection.source
        Log.i(TAG, "video-page ${hero.id} selected=${source != null}")
        return if (source == null || TrailerSourceExpiry.isUsable(source)) Resolution(source, source != null || selection.complete)
            else Resolution(null, false)
    }

    private fun configureWebView(wv: WebView, flag: NetFlag) {
        CookieManager.getInstance().apply {
            setAcceptCookie(true); setAcceptThirdPartyCookies(wv, true)
        }
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = UA
            loadsImagesAutomatically = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                // Never intercept the main document; block only static assets by extension.
                if (request?.isForMainFrame == true) return null
                val u = request?.url?.toString() ?: return null
                if (BLOCK_EXT.containsMatchIn(u)) {
                    return android.webkit.WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    when (error?.errorCode) {
                        ERROR_HOST_LOOKUP, ERROR_CONNECT, ERROR_TIMEOUT, ERROR_IO -> flag.tripped = true
                    }
                }
            }
        }
    }

    private suspend fun loadVideoData(
        wv: WebView, url: String, id: String, titlePage: Boolean, flag: NetFlag
    ): ImdbTrailerData.Result {
        flag.tripped = false
        wv.stopLoading()
        // No about:blank navigation or unconditional 200ms sleep. The probe checks the URL.
        wv.loadUrl(url, mapOf("Referer" to REFERER))
        val script = ImdbTrailerData.probeScript(id, titlePage)
        return withTimeoutOrNull(8_000L) {
            while (!flag.tripped) {
                delay(POLL_MS)
                val json = evalJs(wv, script)
                val data = withContext(Dispatchers.Default) { ImdbTrailerData.parse(json, titlePage) }
                if (data != ImdbTrailerData.Result.Pending) return@withTimeoutOrNull data
            }
            ImdbTrailerData.Result.Pending
        } ?: ImdbTrailerData.Result.Pending
    }

    private suspend fun evalJs(wv: WebView, script: String): String? = withTimeoutOrNull(1_000L) {
        val raw = suspendCancellableCoroutine<String?> { cont ->
            try {
                wv.evaluateJavascript(script) { value -> if (cont.isActive) cont.resume(value) }
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
        withContext(Dispatchers.Default) {
            runCatching { JsonParser.parseString(raw).asString }.getOrNull()
        }
    }
}
