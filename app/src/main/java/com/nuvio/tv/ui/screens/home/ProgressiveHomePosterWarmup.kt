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
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.nuvio.tv.core.image.ArtworkMemoryCache
import com.nuvio.tv.core.image.HomePosterPriority
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
import kotlinx.coroutines.withContext

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
    portraitWidth: Dp,
    portraitHeight: Dp,
    landscapeWidth: Dp,
    landscapeHeight: Dp
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val loader = context.imageLoader
    val latestRowStates = rememberUpdatedState(rowStates)
    LaunchedEffect(viewport, density, useLandscapePosters, alwaysShowLandscapeClearlogo, portraitWidth, portraitHeight, landscapeWidth, landscapeHeight) {
        fun idle() = !viewport.scrolling && !verticalScrolling.value &&
            latestRowStates.value.values.none { it.isScrollInProgress }
        fun log(message: String) {
            if (android.util.Log.isLoggable("HomePosterCache", android.util.Log.VERBOSE))
                android.util.Log.d("HomePosterCache", message)
        }
        var previousFirstRow = -1
        while (isActive) {
            snapshotFlow { idle() }.first { it }
            delay(700)
            if (!idle()) continue
            val catalogue = rows.value.list
            val firstRow = viewport.firstRow
            val lastRow = viewport.lastRow
            val bounds = latestRowStates.value.mapValues { (_, state) ->
                val visible = state.layoutInfo.visibleItemsInfo
                (visible.firstOrNull()?.index ?: 0) to (visible.lastOrNull()?.index ?: -1)
            }
            fun unchanged(): Boolean = idle() && rows.value.list === catalogue &&
                viewport.firstRow == firstRow && viewport.lastRow == lastRow &&
                (firstRow..lastRow).all { index ->
                    val row = catalogue.getOrNull(index) ?: return@all true
                    val state = latestRowStates.value[row.key] ?: return@all true
                    val visible = state.layoutInfo.visibleItemsInfo
                    bounds[row.key] == ((visible.firstOrNull()?.index ?: 0) to (visible.lastOrNull()?.index ?: -1))
                }
            val screenColumns = visibleRowColumns(catalogue, firstRow, lastRow, bounds)
            class Scan(val row: Int, val iterator: Iterator<Int>, val memoryColumns: IntRange) {
                var checked = 0
                var hits = 0
                var requests = 0
                var furthest = 0
            }
            val scans = progressivePosterRows(catalogue.size, firstRow, lastRow).mapNotNull { index ->
                val row = catalogue[index]
                if (row.items.list.isEmpty() || row.items.list.first().payload is ModernPayload.ContinueWatching) null
                else {
                    val (first, last) = bounds[row.key] ?: (0 to -1)
                    val onScreen = index in firstRow..lastRow && last >= first
                    val indices = if (onScreen) progressivePosterIndices(row.items.size, first, last)
                    else row.items.indices.asSequence()
                    val columns = if (onScreen) homePosterMemoryWarmColumns(first, last - first + 1)
                    else homePosterMemoryWarmColumns(latestRowStates.value[row.key]?.firstVisibleItemIndex ?: 0, screenColumns)
                    Scan(index, indices.iterator(), columns)
                }
            }.toList()
            val visibleRows = firstRow..lastRow
            val memoryRows = homePosterMemoryWarmRows(catalogue.size, firstRow, lastRow, previousFirstRow)
            previousFirstRow = firstRow
            val memoryCache = loader.memoryCache
            var memoryBudget = homePosterMemoryWarmBudget(
                (memoryCache as? ArtworkMemoryCache)?.posterMaxSize ?: ((memoryCache?.maxSize ?: 0L) * 3 / 4)
            )
            log("progressive_home_start loadedRows=${scans.size} visible=$firstRow..$lastRow memoryRows=$memoryRows")
            // Finish the visible rows' offscreen posters first, then decode the rows ahead into
            // memory. Remaining loaded rows only reach the disk; no new catalogue pages or metadata.
            val phases = listOf(
                scans.filter { it.row in visibleRows },
                memoryRows.filter { it !in visibleRows }.mapNotNull { row -> scans.firstOrNull { it.row == row } },
                scans.filter { it.row !in visibleRows && it.row !in memoryRows }
            )
            for (phase in phases) {
                while (unchanged() && phase.any { it.iterator.hasNext() }) {
                    for (scan in phase) {
                        if (!unchanged()) break
                        if (!scan.iterator.hasNext()) continue
                        val index = scan.iterator.next()
                        val row = catalogue[scan.row]
                        val item = row.items[index]
                        val url = item.collapsedArtworkUrl(
                            useLandscapePosters,
                            alwaysShowLandscapeClearlogo = alwaysShowLandscapeClearlogo
                        )?.takeUnless { it.isPlaceholder() }
                        if (url != null && item.payload !is ModernPayload.ContinueWatching) {
                            val metrics = item.catalogCardRequestMetrics(useLandscapePosters,
                                portraitWidth, portraitHeight, landscapeWidth, landscapeHeight, expandEnabled = false)
                            val width = with(density) { metrics.width.roundToPx() }.coerceAtLeast(1)
                            val height = with(density) { metrics.height.roundToPx() }.coerceAtLeast(1)
                            val diskKey = sizedHomeArtworkUrl(url, width)
                            val memoryKey = homePosterCacheKey(url, width, height)
                            val memoryExtras = item.customPosterCacheExtras(url, useLandscapePosters)
                            val toMemory = scan.row in memoryRows && index in scan.memoryColumns &&
                                memoryBudget >= width.toLong() * height * 4
                            viewport.warmWhenIdle(::unchanged) {
                                val cached = if (toMemory) {
                                    loader.memoryCache?.get(MemoryCache.Key(memoryKey, memoryExtras)) != null
                                } else withContext(Dispatchers.IO) {
                                    loader.diskCache?.openSnapshot(diskKey)?.use { true } ?: false
                                }
                                scan.checked++
                                if (cached) scan.hits++
                                val (first, last) = bounds[row.key] ?: (0 to -1)
                                scan.furthest = maxOf(scan.furthest, if (index > last) index - last else first - index)
                                if (!cached && unchanged()) {
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
                                }
                            }
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
            if (unchanged()) snapshotFlow { unchanged() }.first { !it }
        }
    }
}

private fun visibleRowColumns(
    catalogue: List<HeroCarouselRow>,
    firstRow: Int,
    lastRow: Int,
    bounds: Map<String, Pair<Int, Int>>
): Int = (firstRow..lastRow).mapNotNull { index ->
    catalogue.getOrNull(index)?.let { bounds[it.key] }?.takeIf { (first, last) -> last >= first }
}.maxOfOrNull { (first, last) -> last - first + 1 } ?: 8
