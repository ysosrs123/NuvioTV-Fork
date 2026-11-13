package com.nuvio.tv.ui.screens.home

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import coil3.Image
import coil3.imageLoader
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/** Keep one outgoing scene while preparing the selected title; no network work in the renderer. */
@Composable
internal fun rememberPreparedHeroScene(
    requested: State<ModernHeroSceneState>, width: Int, height: Int
): State<ModernHeroSceneState> {
    val context = LocalContext.current
    val latest by rememberUpdatedState(requested.value)
    var presentation by remember { mutableStateOf(HeroScenePresentation<ModernHeroSceneState>()) }
    val owner = latest.artwork.ownerKey
    val identity = owner?.let { "$it:${width}x$height" }
    SideEffect { HeroImageRequests.width = width; HeroImageRequests.height = height }

    LaunchedEffect(identity) {
        presentation = presentation.select(identity)
        val ticket = presentation.ticket
        HeroSceneTrace.event("requested", owner, "generation=${ticket.generation} size=${width}x$height")
        // Never leave an unrelated title visible indefinitely on failure or a slow provider.
        launch {
            delay(900)
            if (!presentation.committed) HeroSceneTrace.event("neutral-timeout", owner)
            presentation = presentation.timeout(ticket, latest.neutralHero())
        }
        snapshotFlow { latest }
            .distinctUntilChangedBy { Triple(it.artwork, it.preview?.logo, it.enrichmentActive) }
            .collectLatest { initial ->
                if (presentation.committed || initial.artwork.ownerKey != owner) return@collectLatest
                coroutineScope {
                // Fetch immediately after focus settles, overlapping core metadata resolution.
                val initialBackdrop = async { decodeHeroBackdrop(context, initial, width, height) }
                // Allow core metadata to settle, but an unavailable provider cannot block the UI.
                val target = if (initial.enrichmentActive) {
                    withTimeoutOrNull(350) {
                        snapshotFlow { latest }.first { !it.enrichmentActive }
                    } ?: latest
                } else initial
                    HeroSceneTrace.event("core-selected", owner, "enriching=${target.enrichmentActive}")
                    val logo = async {
                        target.preview?.logo?.takeIf(String::isNotBlank)?.let { url ->
                            withTimeoutOrNull(1200) {
                                (context.imageLoader.execute(HeroImageRequests.logo(context, url).build()) as? SuccessResult)?.image
                            }
                        }
                    }
                    val first = if (target.artwork == initial.artwork) initialBackdrop.await()
                        else {
                            initialBackdrop.cancel()
                            decodeHeroBackdrop(context, target, width, height)
                        }
                    val decoded = first.image
                    val displayedUrl = first.url
                    val logoImage = withTimeoutOrNull(250) { logo.await() }
                    logo.cancel()
                    coroutineContext.ensureActive()
                    val prepared = target.copy(
                        artwork = heroArtworkSelection(owner, displayedUrl), heroBackdrop = displayedUrl,
                        enrichmentActive = false, preparedImage = decoded, preparedLogo = logoImage
                    )
                    if (decoded != null) {
                        presentation = presentation.ready(ticket, prepared)
                        HeroSceneTrace.event("presented", owner, "generation=${ticket.generation} logoReady=${logoImage != null}")
                        launch {
                            delay(500)
                            HeroSceneTrace.rendering(context, owner)
                        }
                    } else {
                        // No full-bleed portrait fallback. Later enrichment may still provide landscape art.
                        presentation = presentation.fallback(ticket, prepared)
                        // A busy start can miss the first window; try again for this title only.
                        if (first.retryable) {
                            val late = retryHeroBackdrop(HERO_BACKDROP_RETRY_DELAYS_MS) {
                                decodeHeroBackdrop(context, target, width, height, HERO_BACKDROP_RETRY_TIMEOUT_MS)
                                    .takeIf { it.image != null || !it.retryable }
                            }
                            if (late?.image != null) {
                                presentation = presentation.ready(ticket, prepared.copy(
                                    artwork = heroArtworkSelection(owner, late.url), heroBackdrop = late.url,
                                    preparedImage = late.image
                                ))
                                HeroSceneTrace.event("late-backdrop", owner, "generation=${ticket.generation}")
                            }
                        }
                    }
                }
            }
    }
    val logoUrl = latest.preview?.logo
    val displayed = presentation.displayed
    LaunchedEffect(identity, logoUrl, displayed?.preparedImage, displayed?.artwork?.ownerKey, displayed?.preparedLogo == null) {
        val ticket = presentation.ticket
        if (displayed == null || ticket.identity != identity || displayed.artwork.ownerKey != owner ||
            displayed.preparedLogo != null || logoUrl.isNullOrBlank()) return@LaunchedEffect
        val image = withTimeoutOrNull(1500) {
            (context.imageLoader.execute(HeroImageRequests.logo(context, logoUrl).build()) as? SuccessResult)?.image
        } ?: return@LaunchedEffect
        HeroSceneTrace.event("late-logo-ready", owner)
        presentation = presentation.revise(ticket) { scene ->
            scene.copy(preparedLogo = image, preview = scene.preview?.copy(logo = logoUrl))
        }
    }
    val latestRequested = rememberUpdatedState(requested)
    return remember { preparedHeroSceneState({ presentation.displayed }, { latestRequested.value }) }
}

/**
 * Home rebuilds the requested state whenever rows or settings change. The result stays one object
 * and reads the newest request, so holders of the first one still see the trailer start.
 */
internal fun preparedHeroSceneState(
    displayed: () -> ModernHeroSceneState?,
    requested: () -> State<ModernHeroSceneState>
): State<ModernHeroSceneState> = derivedStateOf {
    val live = requested().value
    presentedHeroScene(displayed() ?: live.neutralHero(), live)
}

internal fun presentedHeroScene(shown: ModernHeroSceneState, live: ModernHeroSceneState): ModernHeroSceneState =
    if (shown.artwork.ownerKey != live.artwork.ownerKey) {
        shown.copy(shouldPlayTrailer = false, trailerFirstFrameRendered = false)
    } else {
        // Pin the composition. Late text fills reserved slots without blanking the title.
        shown.copy(
            shouldPlayTrailer = live.shouldPlayTrailer,
            trailerFirstFrameRendered = live.trailerFirstFrameRendered,
            trailerUrl = live.trailerUrl, trailerAudioUrl = live.trailerAudioUrl,
            trailerPlaybackKey = live.trailerPlaybackKey, trailerMuted = live.trailerMuted,
            fullScreenBackdrop = live.fullScreenBackdrop, featheredTrailer = live.featheredTrailer,
            preview = shown.preview?.copy(
                genres = live.preview?.genres ?: shown.preview.genres,
                countryText = live.preview?.countryText ?: shown.preview.countryText,
                languageText = live.preview?.languageText ?: shown.preview.languageText,
                description = live.preview?.description ?: shown.preview.description,
                runtimeText = live.preview?.runtimeText ?: shown.preview.runtimeText,
                secondaryHighlightText = live.preview?.secondaryHighlightText ?: shown.preview.secondaryHighlightText,
                yearText = live.preview?.yearText ?: shown.preview.yearText,
                ageRatingText = live.preview?.ageRatingText ?: shown.preview.ageRatingText,
                statusText = live.preview?.statusText ?: shown.preview.statusText,
                mdbListRatings = live.preview?.mdbListRatings ?: shown.preview.mdbListRatings,
                imdbText = live.preview?.imdbText ?: shown.preview.imdbText
            )
        )
    }

internal const val HERO_BACKDROP_FIRST_TRY_MS = 2_000L
internal const val HERO_BACKDROP_RETRY_TIMEOUT_MS = 8_000L
internal val HERO_BACKDROP_RETRY_DELAYS_MS = listOf(1_000L, 3_000L, 8_000L)

/** [retryable] is false when every candidate answered and none was landscape, so asking again cannot help. */
private class HeroBackdropDecode(val image: Image?, val url: String?, val retryable: Boolean)

internal suspend fun <T : Any> retryHeroBackdrop(delaysMs: List<Long>, attempt: suspend () -> T?): T? {
    for (wait in delaysMs) {
        delay(wait)
        attempt()?.let { return it }
    }
    return null
}

private suspend fun decodeHeroBackdrop(
    context: android.content.Context, scene: ModernHeroSceneState, width: Int, height: Int,
    timeoutMs: Long = HERO_BACKDROP_FIRST_TRY_MS
): HeroBackdropDecode {
    var retryable = false
    for (url in scene.artwork.urls) {
        val start = HeroSceneTrace.now()
        val result = withTimeoutOrNull(timeoutMs) {
            context.imageLoader.execute(HeroImageRequests.backdrop(context, url, width, height).build())
        }
        HeroSceneTrace.image(scene.artwork.ownerKey, heroTransferUrl(url, width), width, height, start, result as? SuccessResult)
        if (result == null || result is ErrorResult) retryable = true
        val image = (result as? SuccessResult)?.image
        if (image != null && image.width > image.height) return HeroBackdropDecode(image, heroTransferUrl(url, width), false)
    }
    return HeroBackdropDecode(null, null, retryable)
}

private fun ModernHeroSceneState.neutralHero() = copy(
    artwork = heroArtworkSelection(artwork.ownerKey), heroBackdrop = null,
    preparedImage = null, preparedLogo = null, enrichmentActive = false,
    shouldPlayTrailer = false, trailerFirstFrameRendered = false
)
