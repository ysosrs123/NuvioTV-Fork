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
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * IMDb trailer discovery. Loads the title page in a windowless WebView (which
 * clears IMDb's JS challenge) and returns the best progressive mp4 of
 * primaryVideos.edges[0], the video IMDb features in the hero "Play trailer"
 * slot, as a [TrailerPlaybackSource]. Capped at 1080p; below 720p it returns
 * null so the caller falls back to YouTube.
 *
 * The title page usually embeds the playback URLs, so one page load is enough;
 * if only HLS/AUTO is inline, the hero's /video/vi... page is loaded and parsed.
 * Static sub-resources are blocked by file extension (never scripts, XHR or
 * the document).
 *
 * A fresh WebView is built and destroyed per attempt, on the main thread;
 * resolve() retries up to LOAD_ATTEMPTS, each bounded by TITLE_BUDGET_MS.
 */
@Singleton
class ImdbTrailerResolver @Inject constructor(
    @ApplicationContext private val appContext: Context
) {
    private companion object {
        const val TAG = "ImdbTrailerResolver"
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"
        const val REFERER = "https://www.imdb.com/"
        const val POLL_MS = 300L           // finer cadence (cheap probe, not full-DOM marshalling)
        const val POLL_MAX = 60            // 60 * 300ms = ~18s ceiling (unchanged wall-clock)
        const val DEAD_STALL_POLLS = 24    // 24 * 300ms = ~7s of no progress before dead-fast bail
        const val MAX_EVAL_MS = 4_000L
        const val TITLE_BUDGET_MS = 45_000L
        const val MAX_NULL_STREAK = 10     // scaled to 300ms polls (~3s of null probes)
        const val LOAD_ATTEMPTS = 3

        const val RES_FLOOR = 720          // never serve below this from IMDb
        const val RES_CAP = 1080           // cap picks at 1080p even when 4K exists (throwaway hero backdrop)

        val VI_ID = Regex("""^vi\d{6,}$""")
        val CDN_MP4 = Regex("""https://imdb-video\.media-imdb\.com/[^\s"'<>\\]+?\.mp4[^\s"'<>\\]*""")

        // Static sub-resources we never need for __NEXT_DATA__. Extension-only: never matches scripts,
        // XHR/fetch (extensionless), the main document, or the awsWaf challenge.
        val BLOCK_EXT = Regex("""\.(?:css|woff2?|ttf|otf|eot|jpe?g|png|gif|webp|bmp|ico|svg|mp4|m3u8|ts|mp3|aac)(?:[?#].*)?$""", RegexOption.IGNORE_CASE)
    }

    private val blockedAssets = AtomicInteger(0)
    private val webViews = TrailerResourceGate(Dispatchers.Main)

    private data class Encoding(val def: String, val height: Int, val mp4: Boolean, val url: String)
    private data class VideoInfo(val contentType: String?, val name: String?, val encodings: List<Encoding>)
    private data class Hero(val vi: String, val name: String?, val runtime: Int?, val encodings: List<Encoding>)
    private data class Page(val html: String, val nextData: String?)
    private class NetFlag { @Volatile var tripped = false }

    /**
     * Resolve the IMDb hero trailer for [imdbId] (a `tt...` id). Returns a playback source pointing at a
     * signed CloudFront mp4 (>=720p, <=1080p), or null if discovery failed / the hero video has no
     * acceptable mp4 (caller should fall back to YouTube on null). [type] is unused (kept for source
     * compatibility) -- mirror-IMDb selects the hero regardless of title type.
     */
    suspend fun resolve(imdbId: String, type: String? = null): TrailerPlaybackSource? {
        if (!imdbId.startsWith("tt")) {
            Log.w(TAG, "resolve called with non-imdb id: $imdbId")
            return null
        }
        repeat(LOAD_ATTEMPTS) { attempt ->
            val flag = NetFlag()
            val result = withTimeoutOrNull(TITLE_BUDGET_MS) {
                webViews.use(
                    create = { buildWebView(flag) },
                    destroy = { wv ->
                        // A failed stop must not prevent destruction, even on timeout.
                        wv?.let {
                            runCatching { it.stopLoading() }
                            runCatching { it.destroy() }
                        }
                    }
                ) { wv ->
                    wv?.let { resolveInternal(imdbId, it, flag) }
                }
            }
            if (result != null) return result
            if (attempt < LOAD_ATTEMPTS - 1) {
                Log.w(TAG, "[$imdbId] resolve attempt ${attempt + 1}/$LOAD_ATTEMPTS produced nothing; fresh WebView + retry")
                delay(500)
            }
        }
        Log.w(TAG, "[$imdbId] resolve exhausted $LOAD_ATTEMPTS attempts -> null (YouTube fallback)")
        return null
    }

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

    private suspend fun resolveInternal(imdbId: String, wv: WebView, flag: NetFlag): TrailerPlaybackSource? {
        val titleUrl = "https://www.imdb.com/title/$imdbId/"
        val titlePage = loadAndSettle(wv, titleUrl, imdbId, flag, includeHtml = false)
        if (titlePage == null) {
            Log.w(TAG, "[$imdbId] title page never committed")
            return null
        }

        // Mirror IMDb: the hero "Play trailer" video is primaryVideos.edges[0]. Take exactly that node
        // -- no ranking, scoring or runtime gate.
        val hero = withContext(Dispatchers.Default) { readHeroVideo(titlePage.nextData) }
        if (hero == null) {
            Log.w(TAG, "[$imdbId] no primaryVideos edge[0] hero node -> null (YouTube fallback)")
            return null
        }
        Log.i(TAG, "[$imdbId] hero video ${hero.vi} \"${hero.name ?: "?"}\" rt=${hero.runtime ?: "-"} inlineEncs=${hero.encodings.size}")

        // Fast path: the title page usually embeds the hero's mp4 rungs -> resolve with one page load.
        val inlineBest = pickBestMp4(hero.encodings)
        if (inlineBest != null && inlineBest.height >= RES_FLOOR) {
            Log.i(TAG, "[$imdbId] SELECTED ${hero.vi} (title-page) def=${inlineBest.def} h=${inlineBest.height}")
            return TrailerPlaybackSource(videoUrl = normalise(inlineBest.url))
        }

        // Fallback: load only the hero's video page and parse its playback data (mp4 rung not inline).
        val videoUrl = "https://www.imdb.com/video/${hero.vi}"
        val videoPage = loadAndSettle(wv, videoUrl, hero.vi, flag)
        if (videoPage == null) {
            Log.w(TAG, "[$imdbId] hero video page never committed -> null (YouTube fallback)")
            return null
        }
        val vinfo = withContext(Dispatchers.Default) { parseVideo(videoPage.nextData, videoPage.html) }
        val best = vinfo?.let { pickBestMp4(it.encodings) }
        if (best == null || best.height < RES_FLOOR) {
            val why = when {
                best == null -> "no mp4"
                best.height <= 0 -> "unverified-res(${best.def})"
                else -> "${best.height}p"
            }
            Log.i(TAG, "[$imdbId] hero ${hero.vi} best mp4 $why < ${RES_FLOOR}p floor -> null (YouTube fallback)")
            return null
        }
        Log.i(TAG, "[$imdbId] SELECTED ${hero.vi} (video-page) def=${best.def} h=${best.height}")
        return TrailerPlaybackSource(videoUrl = normalise(best.url))
    }

    // ---- hero extraction (mirror IMDb: primaryVideos.edges[0]) ----

    private fun readHeroVideo(nextData: String?): Hero? {
        if (nextData == null) return null
        val root = try { JSONObject(nextData) } catch (_: Exception) { return null }
        val node = findPrimaryVideosEdge0(root) ?: return null
        val vi = node.optString("id", "")
        if (!VI_ID.matches(vi)) return null
        val name = deep(node, "name", "value") ?: node.optString("name", null)
        val runtime = deepInt(node, "runtime", "value") ?: deepInt(node, "runtime", "seconds")
        val encs = parsePlaybackURLs(node.optJSONArray("playbackURLs"))
        return Hero(vi, name, runtime, encs)
    }

    /** Depth-first search for the first primaryVideos.edges[0].node in the __NEXT_DATA__ tree. */
    private fun findPrimaryVideosEdge0(any: Any?): JSONObject? {
        when (any) {
            is JSONObject -> {
                val pv = any.optJSONObject("primaryVideos")
                if (pv != null) {
                    val node = pv.optJSONArray("edges")?.optJSONObject(0)?.optJSONObject("node")
                    if (node != null && VI_ID.matches(node.optString("id", ""))) return node
                }
                val it = any.keys()
                while (it.hasNext()) {
                    val found = findPrimaryVideosEdge0(any.opt(it.next()))
                    if (found != null) return found
                }
            }
            is JSONArray -> for (i in 0 until any.length()) {
                val found = findPrimaryVideosEdge0(any.opt(i))
                if (found != null) return found
            }
        }
        return null
    }

    // ---- video-page parse (fallback path) ----

    private fun parseVideo(nextData: String?, html: String): VideoInfo? {
        if (nextData != null) {
            try {
                val root = JSONObject(nextData)
                val video = deepObj(root, "props", "pageProps", "videoPlaybackData", "video")
                if (video != null) {
                    val type = deep(video, "contentType", "displayName", "value") ?: deep(video, "contentType", "id")
                    val name = deep(video, "name", "value")
                    val encs = parsePlaybackURLs(video.optJSONArray("playbackURLs"))
                    if (encs.isNotEmpty()) return VideoInfo(type, name, encs)
                }
            } catch (_: Exception) {}
        }
        val u = CDN_MP4.find(normalise(html))?.value ?: return null
        return VideoInfo(null, null, listOf(Encoding("unknown", 0, true, u)))
    }

    /**
     * Parse an IMDb playbackURLs array into encodings. Resolution: prefer videoDefinition
     * (DEF_1080p/DEF_SD/DEF_AUTO...), then displayName.value ("1080p"/"SD"/"AUTO"), then legacy
     * "definition". mp4 vs HLS from videoMimeType ("MP4"/"M3U8"), url and legacy mimeType as fallback.
     */
    private fun parsePlaybackURLs(urls: JSONArray?): List<Encoding> {
        val encs = mutableListOf<Encoding>()
        if (urls != null) {
            for (i in 0 until urls.length()) {
                val e = urls.optJSONObject(i) ?: continue
                val u = normalise(e.optString("url", ""))
                if (u.isBlank()) continue
                val vdef = e.optString("videoDefinition", "")
                val ddef = deep(e, "displayName", "value") ?: ""
                val def = when {
                    vdef.isNotBlank() -> vdef
                    ddef.isNotBlank() -> ddef
                    else -> e.optString("definition", "")
                }
                val vmime = e.optString("videoMimeType", "").ifBlank { e.optString("mimeType", "") }
                val isHls = vmime.contains("m3u8", true) || def.contains("AUTO", true) || u.contains(".m3u8", true)
                val mp4 = !isHls && (vmime.contains("mp4", true) || u.contains(".mp4", true))
                encs.add(Encoding(def, defHeight(def), mp4, u))
            }
        }
        return encs
    }

    /** Best progressive mp4 <= RES_CAP; if only >cap rungs exist take the smallest of those; height 0 (unknown) last. */
    private fun pickBestMp4(encodings: List<Encoding>): Encoding? {
        val mp4s = encodings.filter { it.mp4 }
        if (mp4s.isEmpty()) return null
        val capped = mp4s.filter { it.height in 1..RES_CAP }
        if (capped.isNotEmpty()) return capped.maxByOrNull { it.height }
        val overCap = mp4s.filter { it.height > RES_CAP }
        if (overCap.isNotEmpty()) return overCap.minByOrNull { it.height }
        return mp4s.maxByOrNull { it.height } // all unknown height
    }

    // ---- webview + settle ----

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
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                // Never intercept the main document; block only static assets by extension.
                if (request?.isForMainFrame == true) return null
                val u = request?.url?.toString() ?: return null
                if (BLOCK_EXT.containsMatchIn(u)) {
                    blockedAssets.incrementAndGet()
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

    /**
     * Load [url] and settle when the target data has arrived. The per-poll cost is a tiny in-WebView
     * probe (returns a short status string, not the DOM); the ~1.4 MB DOM is marshalled once, on settle.
     * Progress is tracked via a monotonic signal (navigation -> __NEXT_DATA__ present -> data growing);
     * no progress for DEAD_STALL_POLLS polls -> dead-fast bail.
     */
    private suspend fun loadAndSettle(wv: WebView, url: String, expectKey: String, flag: NetFlag, includeHtml: Boolean = true): Page? {
        flag.tripped = false
        blockedAssets.set(0)
        withContext(Dispatchers.Main) { wv.loadUrl("about:blank") }
        delay(200)
        withContext(Dispatchers.Main) { wv.loadUrl(url, mapOf("Referer" to REFERER)) }
        var lastSignal = -1L
        var nullStreak = 0
        var lastProgressPoll = 0
        repeat(POLL_MAX) { pollIndex ->
            delay(POLL_MS)
            if (flag.tripped) {
                Log.w(TAG, "main-frame net error during load ($url) -> abandon (fail-fast)")
                return null
            }
            val status = probe(wv, expectKey)
            if (status == null || status == "err") {
                if (++nullStreak >= MAX_NULL_STREAK) return null
                if (pollIndex - lastProgressPoll >= DEAD_STALL_POLLS) {
                    Log.w(TAG, "[$expectKey] load stalled (probe null) after ${pollIndex + 1} polls -> abandon (dead-fast)")
                    return null
                }
                return@repeat
            }
            nullStreak = 0
            if (status.startsWith("ready:")) {
                return Page(if (includeHtml) readOuterHtml(wv).orEmpty() else "", readNextData(wv))
            }
            // Monotonic progress signal: nav(0) < nonext(1) < wait+len(2+). Any forward move = progress.
            val signal = when {
                status == "nav" -> 0L
                status == "nonext" -> 1L
                status.startsWith("wait:") -> 2L + (status.substringAfter(':').toLongOrNull() ?: 0L)
                else -> 0L
            }
            if (signal > lastSignal) {
                lastSignal = signal
                lastProgressPoll = pollIndex
            }
            if (pollIndex - lastProgressPoll >= DEAD_STALL_POLLS) {
                Log.w(TAG, "[$expectKey] load stalled (no data progress) after ${pollIndex + 1} polls -> abandon (dead-fast)")
                return null
            }
        }
        val s = probe(wv, expectKey)
        return if (s != null && (s.startsWith("wait:") || s.startsWith("ready:"))) {
            Page(if (includeHtml) readOuterHtml(wv).orEmpty() else "", readNextData(wv))
        } else null
    }

    /**
     * Small in-WebView probe. Returns a short status string (never the DOM):
     *   "nav"       navigation hasn't reached the expected URL yet
     *   "nonext"    navigated, but __NEXT_DATA__ not in the DOM yet
     *   "wait:N"    __NEXT_DATA__ present (length N), target data not yet
     *   "ready:N"   target data (playbackURLs / CDN mp4) present -> settle
     *   "err"/null  probe failed
     */
    private suspend fun probe(wv: WebView, expectKey: String): String? = evalJs(
        wv,
        "(function(){try{" +
            "if(location.href.indexOf('$expectKey')<0)return 'nav';" +
            "var e=document.getElementById('__NEXT_DATA__');" +
            "if(!e)return 'nonext';" +
            "var t=e.textContent||'';" +
            "if(t.indexOf('imdb-video.media-imdb.com')>=0||t.indexOf('\"playbackURLs\"')>=0)return 'ready:'+t.length;" +
            "return 'wait:'+t.length;" +
            "}catch(x){return 'err';}})()"
    )

    private suspend fun readOuterHtml(wv: WebView): String? = evalJs(wv, "(function(){return document.documentElement.outerHTML})()")
    private suspend fun readNextData(wv: WebView): String? =
        evalJs(wv, "(function(){var e=document.getElementById('__NEXT_DATA__');return e?e.textContent:''})()")
            ?.takeIf { it.isNotBlank() }

    private suspend fun evalJs(wv: WebView, js: String): String? = withTimeoutOrNull(MAX_EVAL_MS) {
        val raw = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont: CancellableContinuation<String?> ->
                try {
                    wv.evaluateJavascript(js) { raw -> if (cont.isActive) cont.resume(raw) }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
        // The settled HTML/JSON may be megabytes. Only deliver the raw callback on Main.
        withContext(Dispatchers.Default) { decodeJsString(raw) }
    }

    private fun decodeJsString(raw: String?): String? {
        if (raw == null || raw == "null") return null
        return try { JSONArray("[$raw]").getString(0) } catch (e: Exception) { raw }
    }

    // ---- json helpers ----

    private fun normalise(s: String): String =
        s.replace("\\u002F", "/").replace("\\u0026", "&").replace("\\/", "/").replace("&amp;", "&")

    private fun deep(o: JSONObject?, vararg keys: String): String? {
        var cur: Any? = o
        for (k in keys) { cur = (cur as? JSONObject)?.opt(k) ?: return null }
        return (cur as? String) ?: cur?.toString()?.takeIf { it != "null" }
    }

    private fun deepInt(o: JSONObject?, vararg keys: String): Int? {
        var cur: Any? = o
        for (k in keys) { cur = (cur as? JSONObject)?.opt(k) ?: return null }
        return when (cur) {
            is Int -> cur
            is Number -> cur.toInt()
            is String -> cur.toIntOrNull()
            else -> null
        }
    }

    private fun deepObj(o: JSONObject?, vararg keys: String): JSONObject? {
        var cur: Any? = o
        for (k in keys) { cur = (cur as? JSONObject)?.opt(k) ?: return null }
        return cur as? JSONObject
    }

    /** Height from an IMDb definition label, hardened against non-`NNNNp` variants (4K/UHD/HD/SD...). */
    private fun defHeight(def: String): Int {
        val d = def.trim().uppercase().removePrefix("DEF_").uppercase()
        when {
            d.contains("2160") || d == "4K" || d == "UHD" -> return 2160
            d.contains("1440") || d == "2K" || d == "QHD" -> return 1440
            d.contains("1080") || d == "FHD" || d == "FULLHD" || d == "FULL HD" -> return 1080
            d.contains("720") || d == "HD" -> return 720
            d.contains("480") -> return 480
            d == "SD" -> return 480
            d.contains("360") -> return 360
            d.contains("240") -> return 240
            d == "AUTO" || d.isBlank() -> return 0 // adaptive/HLS or unknown -> unverified
        }
        return Regex("""(\d{3,4})""").find(d)?.value?.toIntOrNull() ?: 0
    }
}
