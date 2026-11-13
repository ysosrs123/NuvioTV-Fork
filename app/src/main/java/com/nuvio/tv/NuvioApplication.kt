package com.nuvio.tv

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.StrictMode
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.imageLoader
import com.nuvio.tv.core.image.CustomPosterFallbackInterceptor
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.gif.GifDecoder
import coil3.gif.AnimatedImageDecoder
import coil3.svg.SvgDecoder
import coil3.request.crossfade
import coil3.request.allowHardware
import coil3.request.maxBitmapSize
import coil3.bitmapFactoryMaxParallelism

import okio.Path.Companion.toOkioPath
import com.nuvio.tv.core.runtime.GuardedLifecycleCallbacks
import com.nuvio.tv.core.runtime.PluginRuntimeHooks
import com.nuvio.tv.core.sync.StartupSyncService
import com.nuvio.tv.core.sync.androidtv.AndroidTvChannelSyncService
import com.nuvio.tv.core.network.IPv4FirstDns
import com.nuvio.tv.core.network.NoDelaySocketFactory
import com.nuvio.tv.core.network.ServerTrust
import com.nuvio.tv.core.network.imageNetworkFetcherFactory
import com.nuvio.tv.core.network.withServerTrust
import com.nuvio.tv.data.mediaserver.ServerUserStateProjection
import com.nuvio.tv.data.simkl.SimklAnimeIdPreferenceHolder
import dagger.hilt.android.HiltAndroidApp
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class NuvioApplication : Application(), SingletonImageLoader.Factory {

    @Inject lateinit var startupSyncService: StartupSyncService
    @Inject lateinit var androidTvChannelSyncService: AndroidTvChannelSyncService
    @Inject lateinit var playerSettingsDataStore: PlayerSettingsDataStore
    @Inject lateinit var simklAnimeIdPreferenceHolder: SimklAnimeIdPreferenceHolder
    @Inject lateinit var serverUserStateProjection: ServerUserStateProjection
    @Inject lateinit var siloArtworkRefresher: com.nuvio.tv.data.mediaserver.silo.SiloArtworkRefresher
    @Inject lateinit var plexArtworkSigner: com.nuvio.tv.data.mediaserver.plex.PlexArtworkSigner
    @Inject lateinit var reportOutbox: com.nuvio.tv.data.repository.ReportOutbox
    @Inject lateinit var screensaverController: com.nuvio.tv.core.player.ScreensaverController

    companion object {
        private const val CACHE_TAG = "NuvioCache"
        private const val IMAGE_CACHE_DIR = "image_cache"
        private const val HTTP_CACHE_DIR = "http_cache"
        private const val HTTP_CACHE_VALIDATED_DIR = "http_cache_validated"

        private val START_PREFERENCES = listOf(
            "server_configuration", "floppy_auth", "mdblist_auth", "wetrakr_auth", "media_servers",
            "member_access_snapshot", "member_catalogs", "backup_restore", "app_locale"
        )

        /** Long enough that the walk cannot contend with cold start. */
        private const val CACHE_READOUT_DELAY_MS = 15_000L

        /**
         * Shared cookie jar for CloudStream extension HTTP requests.
         * Accessible so the player's OkHttpClient can share cookies
         * obtained during scraping (e.g., session tokens needed for playback).
         */
        val extensionCookieJar: CookieJar = object : CookieJar {
            private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val hostCookies = store[url.host] ?: return emptyList()
                synchronized(hostCookies) {
                    return hostCookies.filter { cookie ->
                        cookie.expiresAt > System.currentTimeMillis()
                    }
                }
            }

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val hostCookies = store.getOrPut(url.host) { mutableListOf() }
                synchronized(hostCookies) {
                    cookies.forEach { newCookie ->
                        hostCookies.removeAll { it.name == newCookie.name }
                        hostCookies.add(newCookie)
                    }
                }
            }
        }
    }

    private val guardedLifecycleCallbacks =
        ConcurrentHashMap<ActivityLifecycleCallbacks, GuardedLifecycleCallbacks>()

    override fun registerActivityLifecycleCallbacks(callback: ActivityLifecycleCallbacks) {
        if (!GuardedLifecycleCallbacks.isFromLoadedCode(callback, javaClass.classLoader)) {
            super.registerActivityLifecycleCallbacks(callback)
            return
        }
        val guarded = GuardedLifecycleCallbacks(callback)
        guardedLifecycleCallbacks[callback] = guarded
        super.registerActivityLifecycleCallbacks(guarded)
    }

    override fun unregisterActivityLifecycleCallbacks(callback: ActivityLifecycleCallbacks) {
        super.unregisterActivityLifecycleCallbacks(guardedLifecycleCallbacks.remove(callback) ?: callback)
    }

    override fun attachBaseContext(base: Context) {
        com.nuvio.tv.core.util.ArcDispatchers.configure(base)
        super.attachBaseContext(base)
    }

    // Files read on the screen thread while the app is set up: opened here first so they load in parallel.
    private fun warmStartPreferences() {
        Thread({
            START_PREFERENCES.forEach { name -> runCatching { getSharedPreferences(name, Context.MODE_PRIVATE) } }
        }, "arc-prefs-warm").start()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate() {
        com.nuvio.tv.core.util.StartupLatencyTrace.coldOnce("app_create")
        warmStartPreferences()
        com.nuvio.tv.core.network.ArcDns.init(this)
        super.onCreate()
        com.nuvio.tv.core.util.StartupLatencyTrace.coldOnce("app_injected")
        com.nuvio.tv.data.repository.ArcCrashFile.install(this) { screensaverController.playbackActive.value }
        com.nuvio.tv.data.repository.ArcBuildLog.start(this)
        com.nuvio.tv.data.repository.ArcMainWatchdog.start(this)
        com.nuvio.tv.data.repository.ArcHeartbeat.start(this) { screensaverController.playbackActive.value }
        PluginRuntimeHooks.onApplicationCreate(this)
        androidTvChannelSyncService.start()
        // Hydrate the AFR fps cache from disk (background load) so cold
        // rewatches take the preflight hit path (switch before prepare).
        com.nuvio.tv.core.player.FrameRateUtils.initFrameRateCachePersistence(this)
        serverUserStateProjection.start()
        reportOutbox.start()
        // Load locale synchronously so it's available before Activity.attachBaseContext.
        // SharedPreferences reads are fast (cached in memory after first access).
        val tag = getSharedPreferences("app_locale", Context.MODE_PRIVATE)
            .getString("locale_tag", null)
        LocaleCache.localeTag = tag ?: ""

        // Logs how much OkHttp's 50 MB http_cache holds and Coil's COMPUTED
        // disk cap (a percent-based cap is otherwise invisible). Deliberately
        // delayed and on IO: walking thousands of cache entries must not land
        // on the cold-start path.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            delay(CACHE_READOUT_DELAY_MS)
            logCacheSizes()
        }

        // The playback window follows the Automatic speed-up switch from the first connection on, prewarm included.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            playerSettingsDataStore.playerSettings
                .map { it.autoSpeedupEnabled }
                .distinctUntilChanged()
                .collect { com.nuvio.tv.core.network.PlaybackSocketFactory.windowEnabled = it }
        }

        // Expire stored seek thumbnails even when nothing is played.
        com.nuvio.tv.core.player.thumbnail.SeekThumbnails.scheduleStartupHousekeeping(this)
    }

    private fun dirBytes(name: String): Long {
        val d = java.io.File(cacheDir, name)
        if (!d.isDirectory) return -1L
        return try {
            d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } catch (_: Exception) {
            -2L
        }
    }

    private fun logCacheSizes() {
        try {
            val mib = 1024L * 1024L
            val am = getSystemService(android.app.ActivityManager::class.java)
            val loader = imageLoader
            val disk = loader.diskCache
            val mem = loader.memoryCache
            // Locals, not nested literals inside a string template: an escaped
            // quote inside a template is a syntax error, and hoisting removes
            // the whole class of hazard.
            val imageMiB = dirBytes(IMAGE_CACHE_DIR) / mib
            val httpMiB = dirBytes(HTTP_CACHE_DIR) / mib
            val httpValidatedMiB = dirBytes(HTTP_CACHE_VALIDATED_DIR) / mib
            val diskSizeMiB = (disk?.size ?: -1L) / mib
            val diskMaxMiB = (disk?.maxSize ?: -1L) / mib
            val memSizeMiB = (mem?.size ?: -1L) / mib
            val memMaxMiB = (mem?.maxSize ?: -1L) / mib
            val freeMiB = cacheDir.usableSpace / mib
            android.util.Log.i(
                CACHE_TAG,
                "CACHE_SIZES image=${imageMiB}MiB http=${httpMiB}MiB " +
                    "httpValidated=${httpValidatedMiB}MiB " +
                    "coilDisk=${diskSizeMiB}MiB coilDiskMax=${diskMaxMiB}MiB " +
                    "coilMem=${memSizeMiB}MiB coilMemMax=${memMaxMiB}MiB " +
                    "lowRam=${am?.isLowRamDevice} memClass=${am?.memoryClass}MiB " +
                    "largeMemClass=${am?.largeMemoryClass}MiB freeMiB=$freeMiB"
            )
        } catch (e: Exception) {
            android.util.Log.w(CACHE_TAG, "CACHE_SIZES failed: ${e.message}")
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun newImageLoader(context: android.content.Context): ImageLoader {
        val imageOkHttpClient by lazy {
            val imageDispatcher = okhttp3.Dispatcher().apply {
                maxRequests = 32
                maxRequestsPerHost = 16
            }
            OkHttpClient.Builder()
                .eventListenerFactory(com.nuvio.tv.core.image.ArtworkDiagnostics.networkEvents)
                .withServerTrust()
                .dispatcher(imageDispatcher)
                .dns(IPv4FirstDns())
                .socketFactory(NoDelaySocketFactory)
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .callTimeout(8, TimeUnit.SECONDS)
                .addInterceptor(
                    com.nuvio.tv.data.mediaserver.silo.SiloArtworkInterceptor({
                        if (::siloArtworkRefresher.isInitialized) siloArtworkRefresher else null
                    })
                )
                .addInterceptor(
                    com.nuvio.tv.data.mediaserver.plex.PlexArtworkInterceptor({
                        if (::plexArtworkSigner.isInitialized) plexArtworkSigner else null
                    })
                )
                .addInterceptor { chain ->
                    try {
                        chain.proceed(chain.request())
                    } catch (e: java.net.SocketTimeoutException) {
                        chain.withConnectTimeout(3, TimeUnit.SECONDS)
                            .withReadTimeout(4, TimeUnit.SECONDS)
                            .proceed(chain.request())
                    }
                }
                .followRedirects(true)
                .followSslRedirects(true)
                // Default 1-week cache for image responses that ship no usable
                // Cache-Control (e.g. btttr.cc rating posters, which otherwise
                // re-download from NETWORK on every scroll return). Ratings can
                // be up to a week stale in exchange for killing the reload thrash.
                .addNetworkInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    val cc = response.header("Cache-Control")?.lowercase()
                    if (cc == null || "no-store" in cc || "no-cache" in cc || "max-age=0" in cc) {
                        response.newBuilder()
                            .header("Cache-Control", "max-age=604800, public")
                            .removeHeader("Pragma")
                            .build()
                    } else {
                        response
                    }
                }
                .build()
                .also {
                    ServerTrust.closeConnectionsOnWithdrawal(it.connectionPool)
                    com.nuvio.tv.core.network.ArcDns.onChange(it.connectionPool::evictAll)
                }
        }

        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val diskDecodeSlots = com.nuvio.tv.core.image.homePosterDiskDecodeSlots(
            totalRamBytes = memoryInfo.totalMem,
            lowRamDevice = activityManager.isLowRamDevice,
            cores = Runtime.getRuntime().availableProcessors()
        )

        return ImageLoader.Builder(this)
            .eventListenerFactory(com.nuvio.tv.core.image.ArtworkDiagnostics.imageEvents)
            .components {
                add(com.nuvio.tv.core.image.ScoutMixArt.Factory())
                add(CustomPosterFallbackInterceptor())
                add(com.nuvio.tv.core.image.HomePosterLoadingInterceptor(diskDecodeSlots))
                // Size-aware TMDB downscaler: the Bingecat catalogue serves original-size
                // TMDB image URLs (multi-MB, 600-1200ms to decode). Rewrite them to a bucket
                // matching the resolved display target. Only touches image.tmdb.org
                // /t/p/original/ at poster-sized targets (<=780px); screen-sized backdrops
                // (>780px) and unsized requests keep original. Sizes off the resolved target,
                // so it tracks the card size AND the UI-scale feature automatically.
                add(coil3.map.Mapper<String, String> { data, options ->
                    val w = (options.size.width as? coil3.size.Dimension.Pixels)?.px
                    com.nuvio.tv.core.image.sizedHomeArtworkUrl(data, w).takeIf { it != data }
                })
                if (Build.VERSION.SDK_INT >= 28) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(SvgDecoder.Factory())
                add(
                    com.nuvio.tv.core.image.SavedCopyOnErrorFetcher.Factory(imageNetworkFetcherFactory(
                        callFactory = { imageOkHttpClient },
                        // Upstream's StaleWhileRevalidateCacheStrategy is deliberately not used:
                        // its background revalidation client has no cache, so a
                        // 200 downloads the new body and discards it; the card's reload then hits the
                        // unchanged disk entry and the strategy serves the stale copy again (and
                        // re-fetches the body every 10-minute cooldown). Server cache rules plus the
                        // interceptor above keep the foreground conditional-GET path that does update
                        // the disk entry; saved copies from the big artwork hosts count as fresh for a week.
                        cacheStrategy = { com.nuvio.tv.core.image.imageCacheStrategy },
                    ))
                )
            }
            .memoryCache {
                val totalRamMb = memoryInfo.totalMem / (1024 * 1024)
                // Low-RAM devices (≤2GB): use 0.15 — larger cache reduces GC pressure
                // from rapid bitmap eviction during scrolling.
                // Mid-range devices (≤3GB): use 0.20 for decent image caching.
                // Normal devices (>3GB): use 0.25 for snappy image loading.
                val cachePercent = when {
                    totalRamMb <= 2048 -> 0.15
                    totalRamMb <= 3072 -> 0.20
                    else -> 0.25
                }
                val heapBudget = MemoryCache.Builder()
                    .maxSizePercent(context, cachePercent)
                    .build().maxSize
                com.nuvio.tv.core.image.ArtworkMemoryCache(
                    com.nuvio.tv.core.image.pictureMemoryCacheBytes(heapBudget, memoryInfo.totalMem)
                )
            }
            .diskCache {
                val freeStorage = runCatching { cacheDir.usableSpace }.getOrDefault(0L)
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(com.nuvio.tv.core.image.imageDiskCacheBytes(memoryInfo.totalMem, freeStorage))
                    .build()
            }
            .crossfade(false)
            .precision(coil3.size.Precision.INEXACT)
            .allowHardware(true)
            .maxBitmapSize(
                com.nuvio.tv.core.image.pictureMaxBitmapSide(
                    resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels
                ).let { coil3.size.Size(it, it) }
            )
            // No allowRgb565: inert on the hardware decode path (API 26+), and
            // actively harmful on the software paths (blur inputs decode at 16-bit
            // before processing, baking in quantisation).
            .bitmapFactoryMaxParallelism(com.nuvio.tv.core.image.HOME_POSTER_BITMAP_PARALLELISM)
            .build()
    }
}
