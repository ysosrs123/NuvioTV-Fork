package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil3.imageLoader
import coil3.memory.MemoryCache
import com.nuvio.tv.data.repository.OpenFilesGuard
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.nuvio.tv.core.image.ArtworkMemoryCache
import com.nuvio.tv.core.image.HomePosterPriority
import com.nuvio.tv.core.image.HomePostersOnDisk
import com.nuvio.tv.core.image.HomePosterViewport
import com.nuvio.tv.core.image.homePosterCacheKey
import com.nuvio.tv.core.image.homePosterMemoryWarmBudget
import com.nuvio.tv.core.image.homePosterMemoryWarmColumns
import com.nuvio.tv.core.image.homePosterMemoryWarmRows
import com.nuvio.tv.core.image.homePosterPriority
import com.nuvio.tv.core.image.progressivePosterIndices
import com.nuvio.tv.core.image.progressivePosterRows
import com.nuvio.tv.core.image.sizedHomeArtworkUrl
import com.nuvio.tv.domain.model.isPlaceholder
import com.nuvio.tv.ui.util.StableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/** One request at a time for already-loaded catalogues, independent of lazy row lifetime. */
@OptIn(coil3.annotation.ExperimentalCoilApi::class)
@Composable
internal fun ProgressiveHomePosterWarmup(
    rows: State<StableList<HeroCarouselRow>>,
    viewport: HomePosterViewport,
    rowStates: Map<String, LazyListState>,
    verticalScrolling: State<Boolean>,
    useLandscapePosters: Boolean,
    alwaysShowLandscapeClearlogo: Boolean,
    addonLandscapeBackdropLogo: Boolean,
    portraitWidth: Dp,
    portraitHeight: Dp,
    landscapeWidth: Dp,
    landscapeHeight: Dp
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val loader = context.imageLoader
    val latestRowStates = rememberUpdatedState(rowStates)
    LaunchedEffect(viewport, density, useLandscapePosters, alwaysShowLandscapeClearlogo, addonLandscapeBackdropLogo, portraitWidth, portraitHeight, landscapeWidth, landscapeHeight) {
        fun idle() = !viewport.scrolling && !verticalScrolling.value &&
            latestRowStates.value.values.none { it.isScrollInProgress }
        fun log(message: String) {
            if (android.util.Log.isLoggable("HomePosterCache", android.util.Log.VERBOSE))
                android.util.Log.d("HomePosterCache", message)
        }
        var previousFirstRow = -1
        // Posters already seen on disk in this visit to Home. A pass starts again after every
        // scroll; these are not opened, or waited for, a second time.
        val onDisk = HashSet<String>()
        // Rows whose posters were all on disk when a pass finished them, with the items that were checked.
        val rowsOnDisk = HashMap<String, List<ModernCarouselItem>>()
        var skipped = 0
        while (isActive) {
            snapshotFlow { idle() }.first { it }
            delay(HOME_WARMUP_IDLE_MS)
            while (true) {
                val wait = homeWarmupFocusWaitMs(android.os.SystemClock.uptimeMillis() - viewport.lastFocusMoveAtMs)
                if (wait <= 0L) break
                delay(wait)
            }
            if (!idle()) continue
            val catalogue = rows.value.list
            val firstRow = viewport.firstRow
            val lastRow = viewport.lastRow
            val states = latestRowStates.value
            val bounds = states.mapValues { (_, state) ->
                val visible = state.layoutInfo.visibleItemsInfo
                (visible.firstOrNull()?.index ?: 0) to (visible.lastOrNull()?.index ?: -1)
            }
            val firstColumns = states.mapValues { (_, state) -> state.firstVisibleItemIndex }
            fun unchanged(): Boolean = idle() && rows.value.list === catalogue &&
                viewport.firstRow == firstRow && viewport.lastRow == lastRow &&
                (firstRow..lastRow).all { index ->
                    val row = catalogue.getOrNull(index) ?: return@all true
                    val state = latestRowStates.value[row.key] ?: return@all true
                    val visible = state.layoutInfo.visibleItemsInfo
                    bounds[row.key] == ((visible.firstOrNull()?.index ?: 0) to (visible.lastOrNull()?.index ?: -1))
                }
            // The lists are watched once here; the pass itself runs off the main thread and reads only this flag.
            val changed = java.util.concurrent.atomic.AtomicBoolean(false)
            val watcher = launch {
                snapshotFlow { unchanged() }.first { !it }
                changed.set(true)
            }
            fun current(): Boolean = !changed.get() && !viewport.scrolling && !verticalScrolling.value
            val memoryRows = homePosterMemoryWarmRows(catalogue.size, firstRow, lastRow, previousFirstRow)
            previousFirstRow = firstRow
            val memoryCache = loader.memoryCache
            val complete = withContext(Dispatchers.Default) {
                val screenColumns = visibleRowColumns(catalogue, firstRow, lastRow, bounds)
                var rowsSkipped = 0
                val scans = progressivePosterRows(catalogue.size, firstRow, lastRow).mapNotNull { index ->
                    val row = catalogue[index]
                    val items = row.items.list
                    if (items.isEmpty() || items.first().payload is ModernPayload.ContinueWatching) null
                    else if (index !in memoryRows && rowsOnDisk[row.key] == items) {
                        rowsSkipped++
                        null
                    } else {
                        val (first, last) = bounds[row.key] ?: (0 to -1)
                        val onScreen = index in firstRow..lastRow && last >= first
                        val indices = if (onScreen) progressivePosterIndices(row.items.size, first, last)
                        else row.items.indices.asSequence()
                        val columns = if (onScreen) homePosterMemoryWarmColumns(first, last - first + 1)
                        else homePosterMemoryWarmColumns(firstColumns[row.key] ?: 0, screenColumns)
                        WarmupScan(index, indices.iterator(), columns, wholeRow = !onScreen)
                    }
                }.toList()
                val visibleRows = firstRow..lastRow
                var memoryBudget = homePosterMemoryWarmBudget(
                    (memoryCache as? ArtworkMemoryCache)?.posterMaxSize ?: ((memoryCache?.maxSize ?: 0L) * 3 / 4)
                )
                log("progressive_home_start loadedRows=${scans.size} rowsDone=$rowsSkipped visible=$firstRow..$lastRow memoryRows=$memoryRows")
                // Finish the visible rows' offscreen posters first, then decode the rows ahead into
                // memory. Remaining loaded rows only reach the disk; no new catalogue pages or metadata.
                val phases = listOf(
                    scans.filter { it.row in visibleRows },
                    memoryRows.filter { it !in visibleRows }.mapNotNull { row -> scans.firstOrNull { it.row == row } },
                    scans.filter { it.row !in visibleRows && it.row !in memoryRows }
                )
                for (phase in phases) {
                    while (current() && phase.any { it.iterator.hasNext() }) {
                        for (scan in phase) {
                            if (!current()) break
                            if (!scan.iterator.hasNext()) continue
                            val index = scan.iterator.next()
                            val row = catalogue[scan.row]
                            val item = row.items[index]
                            val landscape = item.usesLandscapeCardTreatment(useLandscapePosters, addonLandscapeBackdropLogo)
                            val url = item.collapsedArtworkUrl(
                                landscape,
                                alwaysShowLandscapeClearlogo = alwaysShowLandscapeClearlogo
                            )?.takeUnless { it.isPlaceholder() }
                            if (url != null && item.payload !is ModernPayload.ContinueWatching) {
                                val metrics = item.catalogCardRequestMetrics(useLandscapePosters,
                                    portraitWidth, portraitHeight, landscapeWidth, landscapeHeight, expandEnabled = false)
                                val width = with(density) { metrics.width.roundToPx() }.coerceAtLeast(1)
                                val height = with(density) { metrics.height.roundToPx() }.coerceAtLeast(1)
                                val diskKey = sizedHomeArtworkUrl(url, width)
                                val memoryKey = homePosterCacheKey(url, width, height)
                                val memoryExtras = item.customPosterCacheExtras(url, landscape)
                                val toMemory = scan.row in memoryRows && index in scan.memoryColumns &&
                                    memoryBudget >= width.toLong() * height * 4 && !OpenFilesGuard.isHigh()
                                if (!toMemory && diskKey in onDisk) {
                                    if (++skipped % 16 == 0) yield()
                                    continue
                                }
                                var saved = false
                                viewport.warmWhenIdle(::current) {
                                    val cached = if (toMemory) {
                                        loader.memoryCache?.get(MemoryCache.Key(memoryKey, memoryExtras)) != null
                                    } else withContext(Dispatchers.IO) {
                                        loader.diskCache?.openSnapshot(diskKey)?.use { true } ?: false
                                    }
                                    scan.checked++
                                    if (cached) scan.hits++
                                    if (cached && !toMemory) {
                                        rememberOnDisk(onDisk, diskKey)
                                        saved = true
                                    }
                                    val (first, last) = bounds[row.key] ?: (0 to -1)
                                    scan.furthest = maxOf(scan.furthest, if (index > last) index - last else first - index)
                                    if (!cached && current()) {
                                        scan.requests++
                                        val result = loader.execute(ImageRequest.Builder(context).data(url).size(width, height)
                                            .scale(coil3.size.Scale.FILL).diskCacheKey(diskKey)
                                            .memoryCacheKey(memoryKey)
                                            .memoryCacheKeyExtras(memoryExtras)
                                            .apply {
                                                if (!toMemory) {
                                                    memoryCachePolicy(CachePolicy.DISABLED)
                                                    decoderFactory(coil3.decode.BlackholeDecoder.Factory())
                                                }
                                            }
                                            .homePosterPriority(HomePosterPriority(viewport, scan.row, prefetch = true, progressive = true))
                                            .build())
                                        if (toMemory) memoryBudget -= (result as? SuccessResult)?.image?.size ?: 0L
                                        else if (result is SuccessResult) {
                                            rememberOnDisk(onDisk, diskKey)
                                            saved = true
                                        }
                                    }
                                }
                                if (!saved) scan.allOnDisk = false
                                if (scan.checked > 0 && scan.checked % 10 == 0)
                                    log("progressive_scan row=${scan.row} checked=${scan.checked} diskHits=${scan.hits} requests=${scan.requests} furthest=${scan.furthest} complete=false")
                            }
                            delay(35) // Yield even for missing/failed artwork; no retry loop at idle.
                        }
                    }
                }
                scans.filter { it.checked > 0 }.forEach { scan ->
                    log("progressive_scan row=${scan.row} checked=${scan.checked} diskHits=${scan.hits} requests=${scan.requests} furthest=${scan.furthest} complete=${!scan.iterator.hasNext()}")
                }
                scans.forEach { scan ->
                    if (scan.wholeRow && scan.allOnDisk && scan.row !in memoryRows && !scan.iterator.hasNext()) {
                        val row = catalogue[scan.row]
                        rowsOnDisk[row.key] = row.items.list
                    }
                }
                scans.none { it.iterator.hasNext() }
            }
            // A pass that ran to the end waits for the lists to change; one that was cut short starts over.
            if (complete) watcher.join() else watcher.cancel()
        }
    }
}

/** One row of a warm-up pass. [wholeRow] is false for a row on screen, whose visible cards load themselves. */
private class WarmupScan(val row: Int, val iterator: Iterator<Int>, val memoryColumns: IntRange, val wholeRow: Boolean) {
    var checked = 0
    var hits = 0
    var requests = 0
    var furthest = 0
    var allOnDisk = true
}

internal const val HOME_WARMUP_IDLE_MS = 700L
internal const val HOME_WARMUP_FOCUS_QUIET_MS = 1_200L

/** How much longer the idle warm-up holds off after a focus move; 0 once the viewer has stopped pressing keys. */
internal fun homeWarmupFocusWaitMs(sinceLastFocusMoveMs: Long): Long =
    (HOME_WARMUP_FOCUS_QUIET_MS - sinceLastFocusMoveMs).coerceIn(0L, HOME_WARMUP_FOCUS_QUIET_MS)

private const val ON_DISK_REMEMBERED = 4_000

private fun rememberOnDisk(onDisk: HashSet<String>, diskKey: String) {
    if (onDisk.size >= ON_DISK_REMEMBERED) onDisk.clear()
    onDisk += diskKey
    HomePostersOnDisk.add(diskKey)
}

private fun visibleRowColumns(
    catalogue: List<HeroCarouselRow>,
    firstRow: Int,
    lastRow: Int,
    bounds: Map<String, Pair<Int, Int>>
): Int = (firstRow..lastRow).mapNotNull { index ->
    catalogue.getOrNull(index)?.let { bounds[it.key] }?.takeIf { (first, last) -> last >= first }
}.maxOfOrNull { (first, last) -> last - first + 1 } ?: 8
