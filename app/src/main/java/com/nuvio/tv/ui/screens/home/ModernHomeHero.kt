package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.ui.theme.NuvioMotion

import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.components.v2GlassSource
import dev.chrisbanes.haze.hazeSource
import androidx.compose.foundation.layout.offset

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.painter.Painter
import coil3.compose.asPainter
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.imageLoader
import coil3.request.SuccessResult
import coil3.size.Scale
import coil3.request.crossfade
import com.nuvio.tv.ui.util.LocalRecompositionHighlighterEnabled
import com.nuvio.tv.ui.util.contentTextDirection
import com.nuvio.tv.ui.util.recompositionHighlighter
import coil3.request.transitionFactory
import com.nuvio.tv.R
import kotlinx.coroutines.delay
import com.nuvio.tv.ui.components.ImdbRatingSourceLabel
import com.nuvio.tv.ui.components.TrailerPlayer
import androidx.compose.ui.res.stringResource

private data class ModernHeroSecondaryMeta(
    val highlightText: String?,
    val ageRating: String?,
    val status: String?,
    val details: List<String>
)

@Composable
internal fun ModernHeroScene(
    state: () -> ModernHeroSceneState,
    isFullScreen: () -> Boolean,
    bgColor: Color,
    trailerBottomLimit: androidx.compose.ui.unit.Dp,
    modifier: Modifier,
    requestWidthPx: Int,
    requestHeightPx: Int,
    onTrailerEnded: () -> Unit,
    onFirstFrameRendered: () -> Unit
) {
    // Navigation must sample the same darkened image seen on the page, not raw artwork.
    // This source attaches only while the glass sidebar is visible. Home stops trailers
    // on sidebar entry; other glass controls retain their existing capture/quality policy.
    val sidebarSource = com.nuvio.tv.ui.v2.components.LocalSidebarBackdropSource.current
    Box(modifier.then(if (sidebarSource != null) Modifier.hazeSource(sidebarSource) else Modifier)
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        ModernHeroMediaLayer(
            artwork = state().artwork,
            featheredTrailer = state().featheredTrailer,
            fullScreenBackdrop = state().fullScreenBackdrop,
            trailerBottomLimit = trailerBottomLimit,
            shouldPlayHeroTrailer = { state().shouldPlayTrailer },
            heroTrailerFirstFrameRendered = { state().trailerFirstFrameRendered },
            heroTrailerUrl = { state().trailerUrl },
            heroTrailerAudioUrl = { state().trailerAudioUrl },
            heroTrailerPlaybackKey = { state().trailerPlaybackKey },
            muted = { state().trailerMuted },
            onTrailerEnded = onTrailerEnded,
            onFirstFrameRendered = onFirstFrameRendered,
            modifier = Modifier.fillMaxSize(),
            requestWidthPx = requestWidthPx,
            requestHeightPx = requestHeightPx
        )
        val isTrailerPlayingFullScreen = {
            val s = state()
            !s.featheredTrailer && s.fullScreenBackdrop && s.shouldPlayTrailer && s.trailerFirstFrameRendered
        }
        ModernHeroGradientLayer(
            bgColor = bgColor,
            isFullScreen = isFullScreen,
            isTrailerPlayingFullScreen = isTrailerPlayingFullScreen,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
internal fun ModernHeroMediaLayer(
    artwork: HeroArtworkSelection,
    featheredTrailer: Boolean = false,
    fullScreenBackdrop: Boolean = false,
    trailerBottomLimit: androidx.compose.ui.unit.Dp,
    shouldPlayHeroTrailer: () -> Boolean,
    heroTrailerFirstFrameRendered: () -> Boolean,
    heroTrailerUrl: () -> String?,
    heroTrailerAudioUrl: () -> String?,
    heroTrailerPlaybackKey: () -> String?,
    muted: () -> Boolean,
    onTrailerEnded: () -> Unit,
    onFirstFrameRendered: () -> Unit,
    modifier: Modifier,
    requestWidthPx: Int,
    requestHeightPx: Int
) {
    val shouldPlay by remember { derivedStateOf { shouldPlayHeroTrailer() } }
    val trailerRendered by remember { derivedStateOf { heroTrailerFirstFrameRendered() } }
    val transitionProgressState = animateFloatAsState(
        targetValue = if (shouldPlay && trailerRendered) 1f else 0f,
        animationSpec = tween(durationMillis = 480),
        label = "heroBackdropTrailerCrossfadeProgress"
    )
    val localContext = LocalContext.current
    val artworkAccent = com.nuvio.tv.ui.v2.appearance.LocalArtworkAccent.current

    var artworkState by remember { mutableStateOf(HeroArtworkLoadState<Painter>(artwork)) }
    if (artworkState.selection != artwork) artworkState = artworkState.select(artwork)
    val ticket = artworkState.ticket
    // Start from the focused title's available artwork, without the metadata gate.
    // Render the decoded result directly: no second image request/cache lookup.
    LaunchedEffect(ticket, artwork.ownerKey, requestWidthPx, requestHeightPx) {
        val pageSelection = HeroBackdropState.selectPageArtwork(artwork.ownerKey, ticket?.url)
        if (ticket != null) {
            val cached = localContext.imageLoader.memoryCache?.get(coil3.memory.MemoryCache.Key(ticket.url))?.image
            // Back and warmed A-B-A should display ready artwork without a focus
            // debounce. Coil still validates the actual request/cache compatibility.
            if (cached == null || cached.width < requestWidthPx || cached.height < requestHeightPx) delay(250)
            val request = ImageRequest.Builder(localContext)
                .data(ticket.url)
                .size(requestWidthPx, requestHeightPx)
                .scale(Scale.FILL)
                .build()
            val result = localContext.imageLoader.execute(request)
            coroutineContext.ensureActive()
            if (result is SuccessResult) {
                artworkState = artworkState.loaded(ticket, result.image.asPainter(localContext))
                HeroBackdropState.recordDisplayedArtwork(pageSelection, ticket.url)
                artworkAccent?.imageLoaded(ticket.url)
            } else {
                // A broken/missing backdrop advances to this title's next candidate.
                artworkState = artworkState.failed(ticket)
            }
        }
    }
    val cinematic = com.nuvio.tv.ui.v2.appearance.LocalV2Appearance.current?.visualStyle ==
        com.nuvio.tv.domain.model.VisualStyle.CINEMATIC_GLASS
    val liveTrailerGlass = cinematic && com.nuvio.tv.ui.v2.quality.LocalGlassTokens.current.liveBlur &&
        com.nuvio.tv.ui.v2.quality.LocalVisualQuality.current.tier == com.nuvio.tv.ui.v2.quality.VisualQualityTier.MAXIMUM
    val glassFrame = remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    LaunchedEffect(liveTrailerGlass, shouldPlay) {
        if (liveTrailerGlass && shouldPlay) {
            var lastCapture = 0L
            while (true) {
                androidx.compose.runtime.withFrameNanos { frame ->
                    if (frame - lastCapture >= 33_000_000L) {
                        glassFrame.longValue = frame
                        lastCapture = frame
                    }
                }
            }
        }
    }
    // Capture artwork and its compatible TextureView together so their alpha
    // and corner masks are preserved, with no foreground controls in the source.
    Box(modifier = if (liveTrailerGlass) modifier.v2GlassSource().drawWithContent {
        // Texture updates do not necessarily invalidate Compose's captured layer.
        // Read only in drawing: refresh the source, without recomposition/layout.
        glassFrame.longValue
        drawContent()
    } else modifier) {
      BoxWithConstraints(Modifier.fillMaxSize()) {
        // A crossfade may retain an outgoing painter only within the SAME title.
        key(artwork.ownerKey) {
        androidx.compose.animation.Crossfade(
            targetState = artworkState.ready,
            animationSpec = tween(durationMillis = NuvioMotion.tokens.durations.overlay),
            label = "heroBackdropCrossfade"
        ) { painter ->
            if (painter != null) Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // Auto, not Offscreen: this layer holds a single
                        // full-screen image (no overlapping content), so
                        // Auto composites the transition alpha correctly while
                        // avoiding a redundant offscreen buffer at rest. Inside a
                        // Crossfade, Offscreen meant two buffered full-screen
                        // backdrops alive at once during a hero swap. The hero
                        // scrim layer keeps Offscreen (overlapping gradients).
                        compositingStrategy = CompositingStrategy.Auto
                        alpha = if (featheredTrailer) 1f else 1f - transitionProgressState.value
                    }.then(if (liveTrailerGlass) Modifier else Modifier.v2GlassSource()),
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopEnd
            )
        }
        }
        if (shouldPlay) {
            val trailerUrlVal = heroTrailerUrl()
            val playbackKeyVal = heroTrailerPlaybackKey()
            val audioUrlVal = heroTrailerAudioUrl()
            val mutedVal = muted()
            // Feathered mode retains artwork behind the corner video.
            // The enclosing layer supplies live trailer pixels only at Maximum.
            // The fullscreen canvas runs behind the rows; use their actual viewport
            // boundary as a separate window constraint. Compact placement is unchanged.
            val windowWidth = com.nuvio.tv.ui.components.featheredTrailerWidth(
                maxWidth.value, maxHeight.value, fullScreenBackdrop, trailerBottomLimit.value
            ).dp
            var videoAspect by remember(playbackKeyVal, trailerUrlVal) { mutableStateOf(16f / 9f) }
            val trailerModifier = if (featheredTrailer) Modifier.align(Alignment.TopEnd)
                .padding(top = 12.dp, end = 12.dp)
                .width(windowWidth).aspectRatio(16f / 9f)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithCache {
                    // FIT can letterbox inside this viewport. Mask the displayed image,
                    // not the outer PlayerView, so native widescreen edges never bypass it.
                    val image = com.nuvio.tv.ui.components.fittedTrailerImage(size.width, size.height, videoAspect)
                    val x = minOf(48.dp.toPx() / image.width, .09f)
                    val inset = com.nuvio.tv.ui.components.trailerVerticalFeatherInset(videoAspect)
                    val y = minOf(56.dp.toPx() / image.height, .14f)
                    val sides = Brush.horizontalGradient(0f to Color.Transparent,
                        x * .3f to Color.White.copy(alpha = .16f), x to Color.White,
                        1f - x to Color.White, 1f - x * .3f to Color.White.copy(alpha = .16f), 1f to Color.Transparent,
                        startX = image.left, endX = image.left + image.width)
                    val ends = Brush.verticalGradient(0f to Color.Transparent,
                        inset to Color.Transparent,
                        inset + y * .3f to Color.White.copy(alpha = .16f), inset + y to Color.White,
                        1f - inset - y to Color.White, 1f - inset - y * .3f to Color.White.copy(alpha = .16f),
                        1f - inset to Color.Transparent, 1f to Color.Transparent,
                        startY = image.top, endY = image.top + image.height)
                    onDrawWithContent {
                        drawContent()
                        drawRect(sides, blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                        drawRect(ends, blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                    }
                } else Modifier.fillMaxSize()
            key(playbackKeyVal ?: trailerUrlVal) {
              // TrailerPlayer wraps AndroidView in AnimatedVisibility; position
              // this outer Box so that wrapper cannot discard BoxScope alignment.
              Box(trailerModifier.graphicsLayer { alpha = transitionProgressState.value }) {
                TrailerPlayer(
                    trailerUrl = trailerUrlVal,
                    trailerAudioUrl = audioUrlVal,
                    isPlaying = true,
                    onEnded = onTrailerEnded,
                    onFirstFrameRendered = onFirstFrameRendered,
                    muted = mutedVal,
                    cropToFill = !featheredTrailer,
                    transparentVideoBackground = featheredTrailer,
                    onVideoAspectRatioChanged = { videoAspect = it },
                    overscanZoom = MODERN_TRAILER_OVERSCAN_ZOOM,
                    modifier = Modifier.fillMaxSize()
                )
              }
            }
        }
      }
    }
}

@Composable
internal fun ModernHeroGradientLayer(
    bgColor: Color,
    isFullScreen: () -> Boolean,
    isTrailerPlayingFullScreen: () -> Boolean = { false },
    modifier: Modifier
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val cinematic = com.nuvio.tv.ui.v2.appearance.LocalV2Appearance.current?.visualStyle ==
        com.nuvio.tv.domain.model.VisualStyle.CINEMATIC_GLASS
    val pureDark = com.nuvio.tv.ui.v2.appearance.LocalV2Appearance.current?.visualStyle ==
        com.nuvio.tv.domain.model.VisualStyle.PURE_LIQUID_DARK
    Box(
        modifier = modifier
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Auto
                alpha = if (isTrailerPlayingFullScreen()) 0f else 1f
            }
            .drawWithCache {
                val fullScreen = isFullScreen()
                val horizontalFadeEndX = size.width * if (fullScreen) 0.65f else 0.45f
                val colorStops = if (fullScreen) {
                    arrayOf(
                        0.0f to bgColor,
                        0.22f to bgColor.copy(alpha = 0.90f),
                        0.46f to bgColor.copy(alpha = 0.80f),
                        0.76f to bgColor.copy(alpha = 0.42f),
                        1.0f to Color.Transparent
                    )
                } else {
                    arrayOf(
                        0.0f to bgColor,
                        0.22f to bgColor.copy(alpha = 0.86f),
                        0.46f to bgColor.copy(alpha = 0.56f),
                        0.76f to bgColor.copy(alpha = 0.16f),
                        1.0f to Color.Transparent
                    )
                }
                val horizontalGradient = if (isRtl) {
                    Brush.horizontalGradient(
                        colorStops = colorStops,
                        startX = size.width,
                        endX = size.width - horizontalFadeEndX
                    )
                } else {
                    Brush.horizontalGradient(
                        colorStops = colorStops,
                        startX = 0f,
                        endX = horizontalFadeEndX
                    )
                }

                val bottomStripStartY = size.height * if (fullScreen) {
                    if (cinematic) 0.32f else 0.64f
                } else 0.82f
                val verticalGradient = Brush.verticalGradient(
                    colorStops = if (fullScreen) {
                        arrayOf(
                            0.0f to Color.Transparent,
                            0.30f to bgColor.copy(alpha = 0.35f),
                            0.60f to bgColor.copy(alpha = 0.75f),
                            1.0f to bgColor
                        )
                    } else {
                        arrayOf(
                            0.0f to Color.Transparent,
                            0.40f to bgColor.copy(alpha = 0.25f),
                            0.75f to bgColor.copy(alpha = 0.65f),
                            1.0f to bgColor
                        )
                    },
                    startY = bottomStripStartY,
                    endY = size.height
                )

                onDrawBehind {
                    if (pureDark) drawRect(Color(0xFF03080D).copy(alpha = 0.12f))
                    // 1. Horizontal fade (reversed in RTL)
                    val rectLeft = if (isRtl) size.width - horizontalFadeEndX else 0f
                    drawRect(
                        brush = horizontalGradient,
                        topLeft = Offset(rectLeft, 0f),
                        size = Size(horizontalFadeEndX, size.height)
                    )
                    
                    // 2. Bottom vertical strip
                    drawRect(
                        brush = verticalGradient,
                        topLeft = Offset(0f, bottomStripStartY),
                        size = Size(size.width, size.height - bottomStripStartY)
                    )
                }
            }
    )
}

@Composable
internal fun HeroTitleBlock(
    previewProvider: () -> HeroPreview?,
    enrichmentActive: () -> Boolean = { false },
    portraitMode: Boolean,
    descriptionMaxLines: Int,
    showImdbRatings: Boolean,
    mdbListShowOnHero: Boolean = false,
    mdbListRatingOrder: List<String> = com.nuvio.tv.domain.model.MDBListSettings.DEFAULT_RATING_ORDER,
    trailerPlaying: () -> Boolean = { false },
    modifier: Modifier = Modifier
) {
    val currentPreview = previewProvider()
    val isEnriching = enrichmentActive()
    
    var stablePreview by remember { mutableStateOf<HeroPreview?>(null) }

    LaunchedEffect(Unit) {
        snapshotFlow { Pair(previewProvider(), enrichmentActive()) }.collect { (p, e) ->
            if (!e && p != null) {
                if (stablePreview != p) stablePreview = p
            } else if (e) {
                if (stablePreview != null) stablePreview = null
            }
        }
    }

    val displayPreview = if (com.nuvio.tv.ui.v2.appearance.LocalV2Appearance.current != null) currentPreview
        else if (!isEnriching && currentPreview != null) currentPreview else stablePreview
    if (displayPreview == null) return
    
    Box(
        modifier = modifier,
        contentAlignment = Alignment.BottomStart
    ) {
        HeroTitleContent(
            previewProvider = { displayPreview },
            portraitMode = portraitMode,
            descriptionMaxLines = descriptionMaxLines,
            showImdbRatings = showImdbRatings,
            mdbListShowOnHero = mdbListShowOnHero,
            mdbListRatingOrder = mdbListRatingOrder,
            trailerPlaying = trailerPlaying
        )
    }
}

@Composable
private fun HeroTitleContent(
    previewProvider: () -> HeroPreview?,
    portraitMode: Boolean,
    descriptionMaxLines: Int,
    showImdbRatings: Boolean,
    mdbListShowOnHero: Boolean = false,
    mdbListRatingOrder: List<String> = com.nuvio.tv.domain.model.MDBListSettings.DEFAULT_RATING_ORDER,
    trailerPlaying: () -> Boolean = { false }
) {
    val preview = previewProvider() ?: return
    val highlighterEnabled = LocalRecompositionHighlighterEnabled.current
    val descriptionScale = if (portraitMode) 0.90f else 1f
    val titleScale = if (portraitMode) 0.92f else 1f
    val metaScale = 1f
    val titleSpacing = NuvioTheme.spacing.sm * titleScale
    val metaSpacing = NuvioTheme.spacing.sm * metaScale
    val imdbMetaSpacing = NuvioTheme.spacing.xs * metaScale
    val context = LocalContext.current
    val density = LocalDensity.current
    val headlineLarge = MaterialTheme.typography.headlineLarge
    val labelMedium = MaterialTheme.typography.labelMedium
    val bodyMedium = MaterialTheme.typography.bodyMedium
    val logoMaxWidthPx = remember(density) { with(density) { 220.dp.roundToPx() } }
    val logoHeightPx = remember(density) { with(density) { 100.dp.roundToPx() } }

    val logoModel = remember(context, preview.logo, logoMaxWidthPx, logoHeightPx) {
        preview.logo?.let {
            ImageRequest.Builder(context)
                .data(it)
                .memoryCacheKey(com.nuvio.tv.core.image.titleLogoCacheKey(it))
                .crossfade(true)
                .size(width = logoMaxWidthPx, height = logoHeightPx)
                .build()
        }
    }
    val trailerPlayingValue = trailerPlaying()
    val metaAlpha by animateFloatAsState(
        targetValue = if (trailerPlayingValue) 0f else 1f,
        animationSpec = tween(durationMillis = 480),
        label = "heroMetaFade"
    )
    val scaledTitleStyle = remember(headlineLarge, titleScale) {
        headlineLarge.copy(
            fontSize = headlineLarge.fontSize * titleScale,
            lineHeight = headlineLarge.lineHeight * titleScale
        )
    }
    val scaledDescriptionStyle = remember(bodyMedium, descriptionScale) {
        bodyMedium.copy(
            fontSize = bodyMedium.fontSize * descriptionScale,
            lineHeight = bodyMedium.lineHeight * descriptionScale
        )
    }

    Column(
        modifier = if (highlighterEnabled) Modifier.recompositionHighlighter() else Modifier,
        verticalArrangement = Arrangement.spacedBy(titleSpacing)
    ) {
        var logoLoadFailed by remember(preview.logo) { mutableStateOf(false) }
        val showLogo = !preview.logo.isNullOrBlank() && !logoLoadFailed
        if (showLogo) {
            AsyncImage(
                model = logoModel,
                contentDescription = preview.title,
                onError = { logoLoadFailed = true },
                modifier = Modifier
                    .height(100.dp)
                    .widthIn(min = 100.dp, max = 220.dp)
                    .fillMaxWidth()
                    .graphicsLayer { alpha = metaAlpha },
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart
            )
        } else if (preview.title.isNotBlank()) {
            Text(
                text = preview.title,
                style = scaledTitleStyle,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer { alpha = metaAlpha }
            )
        }

        val strStatusEnded = stringResource(if (preview.isSeries) R.string.series_status_ended else R.string.movie_status_ended)
        val strStatusContinuing = stringResource(if (preview.isSeries) R.string.series_status_continuing else R.string.movie_status_continuing)
        val strStatusCurrent = stringResource(if (preview.isSeries) R.string.series_status_current else R.string.movie_status_current)
        val strStatusCancelled = stringResource(if (preview.isSeries) R.string.series_status_cancelled else R.string.movie_status_cancelled)
        val strStatusReleased = stringResource(if (preview.isSeries) R.string.series_status_released else R.string.movie_status_released)
        val strStatusPlanned = stringResource(if (preview.isSeries) R.string.series_status_planned else R.string.movie_status_planned)
        val strStatusRumored = stringResource(if (preview.isSeries) R.string.series_status_rumored else R.string.movie_status_rumored)
        val strStatusInProduction = stringResource(if (preview.isSeries) R.string.series_status_in_production else R.string.movie_status_in_production)
        val strStatusPostProduction = stringResource(if (preview.isSeries) R.string.series_status_post_production else R.string.movie_status_post_production)
        val secondaryMeta = remember(
            preview.secondaryHighlightText,
            preview.ageRatingText,
            preview.statusText,
            preview.languageText
        ) {
            ModernHeroSecondaryMeta(
                highlightText = preview.secondaryHighlightText?.trim()?.takeIf { it.isNotBlank() },
                ageRating = preview.ageRatingText?.trim()?.takeIf { it.isNotBlank() },
                status = when (preview.statusText?.trim()?.lowercase()) {
                    "ended" -> strStatusEnded.uppercase()
                    "continuing", "returning series" -> strStatusContinuing.uppercase()
                    "current" -> strStatusCurrent.uppercase()
                    "cancelled", "canceled" -> strStatusCancelled.uppercase()
                    "released" -> strStatusReleased.uppercase()
                    "planned" -> strStatusPlanned.uppercase()
                    "rumored" -> strStatusRumored.uppercase()
                    "in production" -> strStatusInProduction.uppercase()
                    "post production" -> strStatusPostProduction.uppercase()
                    else -> preview.statusText?.trim()?.takeIf { it.isNotBlank() }?.uppercase()
                },
                details = buildList {
                    preview.languageText?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
                }
            )
        }

        val secondaryHighlightText = secondaryMeta.highlightText
        val ageRatingBadge = secondaryMeta.ageRating
        val statusBadge = secondaryMeta.status
        val secondaryDetails = secondaryMeta.details
        val hasSecondaryBadge = ageRatingBadge != null || statusBadge != null
        val hasImdbRatingForLayout = !preview.imdbText.isNullOrBlank()
        val hasMdbListRatings = mdbListShowOnHero && preview.mdbListRatings != null && !preview.mdbListRatings.isEmpty()
        // When MDBList ratings are shown, don't reserve space for standalone IMDb badge.
        val reserveImdbInPrimary = !hasMdbListRatings && !preview.isSeries && !hasSecondaryBadge && hasImdbRatingForLayout
        val reserveImdbInPrimaryWithHighlight = reserveImdbInPrimary && secondaryHighlightText == null
        val reserveImdbInSecondary = !hasMdbListRatings && hasImdbRatingForLayout &&
            (preview.isSeries || hasSecondaryBadge || secondaryHighlightText != null)
        val showImdbInPrimaryWithHighlight = showImdbRatings && reserveImdbInPrimaryWithHighlight
        val showImdbInSecondary = showImdbRatings && reserveImdbInSecondary

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = metaAlpha },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(metaSpacing)
        ) {
            val leadingMetaText = remember(preview.contentTypeText, preview.genres, context) {
                buildList {
                    preview.contentTypeText?.takeIf { it.isNotBlank() }?.let(::add)
                    preview.genres.firstOrNull()?.takeIf { it.isNotBlank() }?.let { genre ->
                        add(com.nuvio.tv.ui.util.localizedGenreLabel(context, genre))
                    }
                }.joinToString(separator = " • ")
            }
            val hasLeadingMeta = leadingMetaText.isNotBlank()

            val runtimeText = preview.runtimeText
            val yearText = preview.yearText
            val hasTrailingMeta = !runtimeText.isNullOrBlank() ||
                !yearText.isNullOrBlank() ||
                reserveImdbInPrimaryWithHighlight

            if (hasLeadingMeta) {
                Text(
                    text = leadingMetaText,
                    style = labelMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (hasTrailingMeta) {
                        Modifier.weight(1f, fill = false)
                    } else {
                        Modifier
                    }
                )
            }

            if (hasTrailingMeta) {
                if (hasLeadingMeta) {
                    HeroMetaDivider(metaScale)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(metaSpacing)
                ) {
                    if (!runtimeText.isNullOrBlank()) {
                        Text(
                            text = runtimeText,
                            style = labelMedium,
                            color = NuvioTheme.colors.TextSecondary,
                            maxLines = 1
                        )
                    }
                    if (!runtimeText.isNullOrBlank() && !yearText.isNullOrBlank()) {
                        HeroMetaDivider(metaScale)
                    }
                    if (!yearText.isNullOrBlank()) {
                        Text(
                            text = yearText,
                            style = labelMedium,
                            color = NuvioTheme.colors.TextSecondary,
                            maxLines = 1
                        )
                    }
                    if (reserveImdbInPrimaryWithHighlight) {
                        HeroImdbMeta(
                            imdbText = preview.imdbText.orEmpty(),
                            textStyle = labelMedium,
                            textColor = NuvioTheme.colors.TextSecondary,
                            logoSize = NuvioTheme.spacing.xl * metaScale,
                            spacing = imdbMetaSpacing,
                            visible = showImdbInPrimaryWithHighlight
                        )
                    }
                }
            }
        }

        if (secondaryHighlightText != null || ageRatingBadge != null || reserveImdbInSecondary || statusBadge != null || secondaryDetails.isNotEmpty() || hasMdbListRatings) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = metaAlpha },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(metaSpacing)
            ) {
                val semiBoldLabelMedium = remember(labelMedium) { labelMedium.copy(fontWeight = FontWeight.SemiBold) }
        secondaryHighlightText?.let { text ->
                    Text(
                        text = text,
                        style = semiBoldLabelMedium,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (secondaryHighlightText != null && (hasSecondaryBadge || reserveImdbInSecondary || secondaryDetails.isNotEmpty() || hasMdbListRatings)) {
                    HeroMetaDivider(
                        scale = metaScale,
                        visible = hasSecondaryBadge || showImdbInSecondary || secondaryDetails.isNotEmpty() || hasMdbListRatings
                    )
                }
                if (ageRatingBadge != null && statusBadge != null) {
                    HeroCombinedMetaBadge(
                        leftText = ageRatingBadge,
                        rightText = statusBadge,
                        textStyle = labelMedium,
                        contentColor = NuvioTheme.colors.TextPrimary
                    )
                } else {
                    ageRatingBadge?.let { badge ->
                        HeroMetaBadge(
                            text = badge,
                            textStyle = labelMedium,
                            contentColor = NuvioTheme.colors.TextPrimary
                        )
                    }
                    statusBadge?.let { badge ->
                        HeroMetaBadge(
                            text = badge,
                            textStyle = labelMedium,
                            contentColor = NuvioTheme.colors.TextPrimary
                        )
                    }
                }
                if ((ageRatingBadge != null || statusBadge != null) && (reserveImdbInSecondary || secondaryDetails.isNotEmpty() || hasMdbListRatings)) {
                    HeroMetaDivider(
                        scale = metaScale,
                        visible = showImdbInSecondary || secondaryDetails.isNotEmpty() || hasMdbListRatings
                    )
                }
                if (reserveImdbInSecondary) {
                    HeroImdbMeta(
                        imdbText = preview.imdbText.orEmpty(),
                        textStyle = labelMedium,
                        textColor = NuvioTheme.colors.TextSecondary,
                        logoSize = NuvioTheme.spacing.xl * metaScale,
                        spacing = imdbMetaSpacing,
                        visible = showImdbInSecondary
                    )
                }
                if (reserveImdbInSecondary && secondaryDetails.isNotEmpty()) {
                    HeroMetaDivider(
                        scale = metaScale,
                        visible = showImdbInSecondary
                    )
                }
                secondaryDetails.forEachIndexed { index, value ->
                    Text(
                        text = value,
                        style = labelMedium,
                        color = NuvioTheme.colors.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (index < secondaryDetails.lastIndex) {
                        HeroMetaDivider(metaScale)
                    }
                }
                // MDBList ratings inline after other secondary meta.
                if (showImdbRatings && hasMdbListRatings) {
                    // Divider before MDBList ratings is already handled by the
                    // badge/IMDb/details divider logic above — only add one
                    // when IMDb or details were the last visible element.
                    if (showImdbInSecondary || secondaryDetails.isNotEmpty()) {
                        HeroMetaDivider(metaScale)
                    }
                    com.nuvio.tv.ui.components.MDBListRatingsRow(
                        ratings = preview.mdbListRatings!!,
                        maxItems = 3,
                        order = mdbListRatingOrder
                    )
                }
            }
        }

        preview.description?.takeIf { it.isNotBlank() }?.let { description ->
            Text(
                text = description,
                style = scaledDescriptionStyle.copy(textDirection = description.contentTextDirection()),
                color = NuvioTheme.colors.TextPrimary,
                maxLines = descriptionMaxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer { alpha = metaAlpha }
            )
        }
    }
}

@Composable
private fun HeroImdbMeta(
    imdbText: String,
    textStyle: androidx.compose.ui.text.TextStyle,
    textColor: Color,
    logoSize: androidx.compose.ui.unit.Dp,
    spacing: androidx.compose.ui.unit.Dp,
    visible: Boolean = true
) {
    Row(
        modifier = Modifier
            .graphicsLayer { alpha = if (visible) 1f else 0f }
            .then(if (visible) Modifier else Modifier.clearAndSetSemantics {}),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing)
    ) {
        ImdbRatingSourceLabel(
            logoModifier = Modifier.size(logoSize),
            textStyle = textStyle,
            textColor = textColor
        )
        Text(
            text = imdbText,
            style = textStyle,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HeroCombinedMetaBadge(
    leftText: String,
    rightText: String,
    textStyle: androidx.compose.ui.text.TextStyle,
    contentColor: Color
) {
    val dividerColor = contentColor.copy(alpha = 0.55f)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        val semiBoldStyle = remember(textStyle) { textStyle.copy(fontWeight = FontWeight.SemiBold) }
        Text(
            text = leftText,
            style = semiBoldStyle,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Box(
            modifier = Modifier
                .width(NuvioTheme.spacing.hairline)
                .height(NuvioTheme.spacing.md)
                .background(dividerColor)
        )
        Text(
            text = rightText,
            style = semiBoldStyle,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HeroMetaBadge(
    text: String,
    textStyle: androidx.compose.ui.text.TextStyle,
    contentColor: Color
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xs),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = remember(textStyle) { textStyle.copy(fontWeight = FontWeight.SemiBold) },
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HeroMetaDivider(
    scale: Float,
    visible: Boolean = true
) {
    Box(
        modifier = Modifier
            .size((NuvioTheme.spacing.xs * scale).coerceAtLeast(NuvioTheme.spacing.xxs))
            .clip(RoundedCornerShape(percent = 50))
            .graphicsLayer { alpha = if (visible) 1f else 0f }
            .background(NuvioTheme.colors.TextTertiary.copy(alpha = 0.78f))
    )
}
