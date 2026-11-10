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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.semantics.contentDescription
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
import com.nuvio.tv.ui.components.AutoScrollingText
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
            preparedImage = state().preparedImage,
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
    preparedImage: coil3.Image?,
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
    val latestShouldPlay = rememberUpdatedState(shouldPlayHeroTrailer)
    val latestTrailerRendered = rememberUpdatedState(heroTrailerFirstFrameRendered)
    val shouldPlay by remember { derivedStateOf { latestShouldPlay.value() } }
    val trailerRendered by remember { derivedStateOf { latestTrailerRendered.value() } }
    val transitionProgressState = animateFloatAsState(
        targetValue = if (shouldPlay && trailerRendered) 1f else 0f,
        animationSpec = tween(durationMillis = 480),
        label = "heroBackdropTrailerCrossfadeProgress"
    )
    val localContext = LocalContext.current
    val artworkAccent = com.nuvio.tv.ui.v2.appearance.LocalArtworkAccent.current

    val menuBackdrop = com.nuvio.tv.ui.v2.components.LocalMainMenuBackdrop.current
    val displayedUrl = artwork.urls.firstOrNull()
    val publishedMenuUrl = remember { arrayOfNulls<String>(1) }
    androidx.compose.runtime.DisposableEffect(menuBackdrop) {
        onDispose {
            if (menuBackdrop?.artworkUrl == publishedMenuUrl[0]) menuBackdrop?.artworkUrl = null
        }
    }
    LaunchedEffect(artwork.ownerKey, displayedUrl, preparedImage, fullScreenBackdrop) {
        val page = HeroBackdropState.selectPageArtwork(artwork.ownerKey, displayedUrl)
        if (preparedImage != null && displayedUrl != null) {
            shownHeroImage.keep(displayedUrl, requestWidthPx, requestHeightPx, preparedImage)
            HeroBackdropState.recordDisplayedArtwork(page, displayedUrl)
            artworkAccent?.imageLoaded(displayedUrl)
        }
        if (fullScreenBackdrop) menuBackdrop?.artworkUrl = displayedUrl
        else if (menuBackdrop?.artworkUrl == publishedMenuUrl[0]) menuBackdrop?.artworkUrl = null
        publishedMenuUrl[0] = if (fullScreenBackdrop) displayedUrl else null
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
        HeroBackdropImages(
            image = preparedImage,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                alpha = if (featheredTrailer) 1f else 1f - transitionProgressState.value
            }.then(if (liveTrailerGlass) Modifier else Modifier.v2GlassSource())
        )
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

private val FULL_SCREEN_HERO_SCRIM = listOf(0.0f to 1f, 0.22f to 0.90f, 0.46f to 0.80f, 0.76f to 0.42f, 1.0f to 0f)

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
    val menuLayout = com.nuvio.tv.ui.v2.components.LocalSidebarPageLayout.current.takeIf { !pureDark }
    Box(
        modifier = modifier
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Auto
                alpha = if (isTrailerPlayingFullScreen()) 0f else 1f
            }
            .drawWithCache {
                val fullScreen = isFullScreen()
                val horizontalFadeEndX = size.width * if (fullScreen) 0.65f else 0.45f
                val menuProgress = if (fullScreen) menuLayout?.let { it.expansion() } ?: 0f else 0f
                val colorStops = if (fullScreen && menuProgress > 0f) {
                    com.nuvio.tv.ui.v2.components.openMenuScrimStops(
                        FULL_SCREEN_HERO_SCRIM, horizontalFadeEndX, menuLayout!!.expandedWidth.toPx(), menuProgress
                    ).map { (f, a) -> f to bgColor.copy(alpha = a) }.toTypedArray()
                } else if (fullScreen) {
                    FULL_SCREEN_HERO_SCRIM.map { (f, a) -> f to bgColor.copy(alpha = a) }.toTypedArray()
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
    preparedLogo: coil3.Image? = null,
    portraitMode: Boolean,
    descriptionMaxLines: Int,
    showImdbRatings: Boolean,
    mdbListShowOnHero: Boolean = false,
    mdbListRatingOrder: List<String> = com.nuvio.tv.domain.model.MDBListSettings.DEFAULT_RATING_ORDER,
    trailerPlaying: () -> Boolean = { false },
    descriptionAutoScrollEnabled: Boolean = false,
    modifier: Modifier = Modifier
) {
    // The providers read hero state that is rewritten on every focus move, often with an equal
    // value. Deriving the pair recomposes this block only when what it shows actually changes.
    val latestPreviewProvider = rememberUpdatedState(previewProvider)
    val latestEnrichmentActive = rememberUpdatedState(enrichmentActive)
    val displayInputs by remember {
        derivedStateOf { latestPreviewProvider.value() to latestEnrichmentActive.value() }
    }
    val currentPreview = displayInputs.first
    val isEnriching = displayInputs.second

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
            preparedLogo = preparedLogo,
            portraitMode = portraitMode,
            descriptionMaxLines = descriptionMaxLines,
            showImdbRatings = showImdbRatings,
            mdbListShowOnHero = mdbListShowOnHero,
            mdbListRatingOrder = mdbListRatingOrder,
            trailerPlaying = trailerPlaying,
            descriptionAutoScrollEnabled = descriptionAutoScrollEnabled
        )
    }
}

@Composable
private fun HeroTitleContent(
    previewProvider: () -> HeroPreview?,
    preparedLogo: coil3.Image?,
    portraitMode: Boolean,
    descriptionMaxLines: Int,
    showImdbRatings: Boolean,
    mdbListShowOnHero: Boolean = false,
    mdbListRatingOrder: List<String> = com.nuvio.tv.domain.model.MDBListSettings.DEFAULT_RATING_ORDER,
    trailerPlaying: () -> Boolean = { false },
    descriptionAutoScrollEnabled: Boolean = false
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

    // Same idea as in HeroTitleBlock: only a flip of the result should recompose this content.
    val latestTrailerPlaying = rememberUpdatedState(trailerPlaying)
    val trailerPlayingValue by remember { derivedStateOf { latestTrailerPlaying.value() } }
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

    // One description for the whole block: its many small texts change with every focused title.
    val heroDescription = remember(preview, context) {
        listOfNotNull(
            preview.title,
            preview.contentTypeText,
            preview.genres.firstOrNull()?.let { com.nuvio.tv.ui.util.localizedGenreLabel(context, it) },
            preview.runtimeText,
            preview.yearText,
            preview.secondaryHighlightText,
            preview.ageRatingText,
            preview.statusText,
            preview.imdbText,
            preview.description
        ).filter { it.isNotBlank() }.joinToString(", ")
    }
    Column(
        modifier = (if (highlighterEnabled) Modifier.recompositionHighlighter() else Modifier)
            .clearAndSetSemantics { contentDescription = heroDescription },
        verticalArrangement = Arrangement.spacedBy(titleSpacing)
    ) {
        // Text remains readable on logo failure/timeout; both choices occupy the same slot.
        Box(Modifier.height(100.dp).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            if (preparedLogo != null) {
                Image(
                    painter = preparedLogo.asPainter(context), contentDescription = preview.title,
                    modifier = Modifier.widthIn(max = 220.dp).fillMaxSize().graphicsLayer { alpha = metaAlpha },
                    contentScale = ContentScale.Fit, alignment = Alignment.CenterStart
                )
            } else {
                Text(text = preview.title, style = scaledTitleStyle,
                    color = NuvioTheme.colors.TextPrimary, maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.graphicsLayer { alpha = metaAlpha })
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(titleSpacing)) {
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
                        modifier = Modifier.weight(1f, fill = false),
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

        }

        if (preview.logos.isNotEmpty()) {
            HeroLogoRow(logos = preview.logos, scale = metaScale, modifier = Modifier.graphicsLayer { alpha = metaAlpha })
        }

        // The block sits on the rows and grows upwards, so a short description leaves no gap above them.
        // Weighted, the description is measured last and gives up lines first when the hero is short.
        preview.description?.takeIf { it.isNotBlank() }?.let { description ->
            AutoScrollingText(
                text = description,
                style = scaledDescriptionStyle.copy(textDirection = description.contentTextDirection()),
                color = NuvioTheme.colors.TextPrimary,
                maxLines = descriptionMaxLines,
                autoScrollEnabled = descriptionAutoScrollEnabled,
                paused = trailerPlaying,
                modifier = Modifier.weight(1f, fill = false).graphicsLayer { alpha = metaAlpha }
            )
        }
    }
}

/** The shows behind a hero that stands for several, by their own logos. */
@Composable
private fun HeroLogoRow(logos: List<String>, scale: Float, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val height = NuvioTheme.spacing.xl * scale
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        logos.forEach { logo ->
            AsyncImage(
                model = remember(context, logo) { HeroImageRequests.logo(context, logo).build() },
                contentDescription = null,
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
                modifier = Modifier.height(height).widthIn(max = height * 3.2f)
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

/** Explicit two-layer handoff: rapid input cannot accumulate Crossfade target layers. */
@Composable
private fun HeroBackdropImages(image: coil3.Image?, modifier: Modifier) {
    val context = LocalContext.current
    var current by remember { mutableStateOf(image) }
    var outgoing by remember { mutableStateOf<coil3.Image?>(null) }
    val alpha = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(image) {
        if (image !== current) {
            outgoing = current
            current = image
            alpha.snapTo(0f)
            alpha.animateTo(1f, tween(350))
            outgoing = null
        }
    }
    Box(modifier) {
        outgoing?.let {
            Image(it.asPainter(context), null, Modifier.fillMaxSize().graphicsLayer {
                this.alpha = if (current == null) 1f - alpha.value else 1f
            }, contentScale = ContentScale.Crop, alignment = Alignment.TopEnd)
        }
        current?.let {
            Image(it.asPainter(context), null, Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value },
                contentScale = ContentScale.Crop, alignment = Alignment.TopEnd)
        }
    }
}
