package com.nuvio.tv

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import com.nuvio.tv.ui.components.AutoResizeText
import com.nuvio.tv.ui.components.BrandWordmark
import com.nuvio.tv.ui.components.ProfileAvatarCircle
import com.nuvio.tv.ui.theme.NuvioComponents
import com.nuvio.tv.ui.theme.NuvioMotion
import com.nuvio.tv.ui.theme.NuvioRadii
import com.nuvio.tv.ui.theme.NuvioStrokes
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.accentBrush
import com.nuvio.tv.ui.util.contentTextDirection
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.draw.drawWithCache
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.hazeEffect
import com.nuvio.tv.ui.v2.components.GlassRole
import com.nuvio.tv.ui.v2.components.nuvioGlass
import com.nuvio.tv.ui.v2.components.nuvioV2Focus

private val SidebarLeadingVisualSize = NuvioComponents.tokens.sidebar.leadingVisual
private val SidebarContentGap = NuvioComponents.tokens.sidebar.contentGap
private val SidebarProfileContentGap = NuvioComponents.tokens.sidebar.contentGap + NuvioTheme.spacing.xs

@Composable
internal fun ModernSidebarBlurPanel(
    drawerItems: List<DrawerItem>,
    selectedDrawerRoute: String?,
    keepSidebarFocusDuringCollapse: Boolean,
    sidebarLabelAlpha: Float,
    sidebarIconScale: Float,
    sidebarExpandProgress: () -> Float,
    isSidebarExpanded: Boolean,
    sidebarCollapsePending: Boolean,
    blurEnabled: Boolean,
    sidebarHazeState: HazeState,
    panelShape: RoundedCornerShape,
    drawerItemFocusRequesters: Map<String, FocusRequester>,
    onDrawerItemFocused: (Int) -> Unit,
    onDrawerItemClick: (String) -> Unit,
    activeProfileName: String,
    activeProfileColorHex: String,
    activeProfileAvatarImageUrl: String?,
    showProfileSelector: Boolean,
    onSwitchProfile: () -> Unit
) {
    val v2Glass = com.nuvio.tv.ui.v2.quality.LocalGlassTokens.current.takeIf {
        com.nuvio.tv.ui.v2.appearance.LocalV2Appearance.current != null
    }
    // V2's fixed-radius blur stays attached throughout the layer animation.
    // Reading its progress in composition would rebuild the panel every frame.
    val delayedBlurProgress = if (v2Glass != null) 1f else
        ((sidebarExpandProgress() - 0.34f) / 0.66f).coerceIn(0f, 1f)
    val showPanelBlur = blurEnabled &&
        isSidebarExpanded &&
        !sidebarCollapsePending &&
        delayedBlurProgress > 0f
    val expandedPanelBlurModifier = if (showPanelBlur && v2Glass == null) {
        Modifier.hazeEffect(state = sidebarHazeState) {
            blurRadius = if (v2Glass != null) v2Glass.blurRadiusDp.dp else NuvioTheme.effects.blurPanel * delayedBlurProgress
            noiseFactor = v2Glass?.noise ?: (0.04f * delayedBlurProgress)
            inputScale = HazeInputScale.Fixed(v2Glass?.inputScale ?: 0.66f)
        }
    } else {
        Modifier
    }
    val colors = NuvioTheme.colors
    val bgElevated = colors.BackgroundElevated
    val bgCard = colors.BackgroundCard
    val borderBase = colors.Border
    val isAmoledBlack = bgElevated == Color.Black
    val panelBackgroundBrush = remember(blurEnabled, isAmoledBlack, bgElevated, bgCard) {
        val baseColor = if (isAmoledBlack) Color.Black else Color(0xFF161618)
        val alpha = when {
            blurEnabled -> 0.65f
            isAmoledBlack -> 1f
            else -> 0.97f
        }
        Brush.verticalGradient(listOf(
            baseColor.copy(alpha = alpha),
            baseColor.copy(alpha = alpha)
        ))
    }
    val panelBorderColor = remember(isAmoledBlack, blurEnabled, borderBase) {
        if (!blurEnabled && isAmoledBlack) borderBase.copy(alpha = 0.9f) else Color.Transparent
    }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .graphicsLayer {
                val p = sidebarExpandProgress()
                alpha = p
                val s = if (v2Glass != null) 1f else 0.97f + (0.03f * p)
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .then(if (v2Glass != null) {
                Modifier.nuvioGlass(GlassRole.NAVIGATION, shape = panelShape,
                    hazeState = sidebarHazeState, trailingEdgeOnly = true)
            } else {
                Modifier.clip(panelShape).then(expandedPanelBlurModifier)
                    .background(brush = panelBackgroundBrush, shape = panelShape)
                    .then(if (panelBorderColor != Color.Transparent) Modifier.border(
                        NuvioStrokes.tokens.hairline, panelBorderColor, panelShape
                    ) else Modifier)
            })
            .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.lg - NuvioTheme.spacing.xxs)
    ) {
        if (v2Glass != null) {
            BrandWordmark(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(.78f).height(32.dp), contentDescription = "Nuvio")
        }
        if (showProfileSelector && activeProfileName.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = NuvioTheme.spacing.md),
                contentAlignment = Alignment.Center
            ) {
                SidebarProfileItem(
                    profileName = activeProfileName,
                    profileColorHex = activeProfileColorHex,
                    profileAvatarImageUrl = activeProfileAvatarImageUrl,
                    focusEnabled = keepSidebarFocusDuringCollapse,
                    labelAlpha = sidebarLabelAlpha,
                    onFocusChanged = { focused ->
                        if (focused) onDrawerItemFocused(drawerItems.size)
                    },
                    onClick = onSwitchProfile,
                    modifier = Modifier.fillMaxWidth(0.92f)
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = NuvioTheme.spacing.md),
                contentAlignment = Alignment.Center
            ) {
                BrandWordmark(
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier
                        .fillMaxWidth(0.72f)
                        .height(36.dp),
                    alpha = sidebarLabelAlpha
                )
            }
        }

        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = if (v2Glass != null) Arrangement.Top else Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.offset(y = if (v2Glass != null) 12.dp else (-12).dp),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm - NuvioTheme.spacing.xxs)
            ) {
                drawerItems.forEachIndexed { index, item ->
                    key(item.route) {
                        SidebarNavigationItem(
                            label = item.label,
                            iconRes = item.iconRes,
                            icon = item.icon,
                            selected = selectedDrawerRoute == item.route,
                            focusEnabled = keepSidebarFocusDuringCollapse,
                            labelAlpha = sidebarLabelAlpha,
                            iconScale = sidebarIconScale,
                            onFocusChanged = {
                                if (it) {
                                    onDrawerItemFocused(index)
                                }
                            },
                            onClick = { onDrawerItemClick(item.route) },
                            modifier = Modifier
                                .fillMaxWidth(0.92f)
                                .focusRequester(drawerItemFocusRequesters.getValue(item.route))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarNavigationItem(
    label: String,
    iconRes: Int?,
    icon: ImageVector?,
    selected: Boolean,
    focusEnabled: Boolean,
    labelAlpha: Float,
    iconScale: Float,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    val isV2 = com.nuvio.tv.ui.v2.appearance.LocalV2Appearance.current != null
    val shape = RoundedCornerShape(if (isV2) 8.dp else NuvioRadii.tokens.full)
    val palette = NuvioTheme.palette
    val accentColor = palette.secondary
    val backgroundColorTarget = when {
        isFocused -> palette.focusBackground
        selected && !isV2 -> accentColor.copy(alpha = 0.15f)
        else -> Color.Transparent
    }
    val animatedBackgroundColor by animateColorAsState(
        targetValue = backgroundColorTarget,
        animationSpec = tween(durationMillis = NuvioMotion.tokens.durations.fast),
        label = "sidebarItemBackground"
    )
    val backgroundColor = if (selected && !isFocused) backgroundColorTarget else animatedBackgroundColor

    val contentColorTarget = when {
        isV2 && isFocused -> Color.White
        selected -> accentColor
        isFocused -> colors.TextPrimary
        else -> colors.text.onOverlay
    }
    val animatedContentColor by animateColorAsState(
        targetValue = contentColorTarget,
        animationSpec = tween(durationMillis = NuvioMotion.tokens.durations.fast),
        label = "sidebarItemContent"
    )
    val contentColor = if (selected && !isFocused) contentColorTarget else animatedContentColor

    val iconBrush = if (selected && !(isV2 && isFocused)) palette.accentBrush() else null
    val iconTintTarget = when {
        selected -> Color.White
        isFocused -> colors.TextPrimary
        else -> colors.text.onOverlay
    }
    val animatedIconTint by animateColorAsState(
        targetValue = iconTintTarget,
        animationSpec = tween(durationMillis = NuvioMotion.tokens.durations.fast),
        label = "sidebarItemIconTint"
    )
    val iconTint = if (selected && !isFocused) iconTintTarget else animatedIconTint
    val itemScale by animateFloatAsState(
        targetValue = if (isFocused && !isV2) 1.1f else 1f,
        animationSpec = tween(durationMillis = NuvioMotion.tokens.durations.fast, easing = NuvioMotion.tokens.easings.standard),
        label = "sidebarItemScale"
    )

    SidebarItemSurface(
        isV2 = isV2,
        backgroundColor = backgroundColor,
        shape = shape,
        onClick = onClick,
        modifier = modifier
            .graphicsLayer {
                scaleX = itemScale
                scaleY = itemScale
                transformOrigin = TransformOrigin.Center
            }
            .onFocusChanged {
                isFocused = it.hasFocus
                onFocusChanged(it.hasFocus)
            }
            .focusProperties { canFocus = focusEnabled }
            // Native elevation shadows show through translucent sidebar rows on Ugoos.
            // The focus modifier's shaped bloom supplies their depth without that caster.
            .then(if (isV2) Modifier.nuvioV2Focus(isFocused, shape, hardwareShadow = false)
                else Modifier)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTheme.spacing.lg - NuvioTheme.spacing.xxs, vertical = NuvioTheme.spacing.sm + NuvioTheme.spacing.xxs),
            verticalAlignment = Alignment.CenterVertically
        ) {
        Box(
            modifier = Modifier
                .size(SidebarLeadingVisualSize)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                },
            contentAlignment = Alignment.Center
        ) {
            val iconModifier = if (iconBrush != null) {
                Modifier
                    .size(NuvioComponents.tokens.sidebar.iconSize)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithCache {
                        onDrawWithContent {
                            drawContent()
                            drawRect(brush = iconBrush, blendMode = BlendMode.SrcIn)
                        }
                    }
            } else {
                Modifier.size(NuvioComponents.tokens.sidebar.iconSize)
            }
            when {
                icon != null -> Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = iconModifier
                )
                iconRes != null -> Icon(
                    painter = rememberRawSvgPainter(iconRes),
                    contentDescription = null,
                    tint = iconTint,
                    modifier = iconModifier
                )
            }
        }
        Spacer(modifier = Modifier.width(SidebarContentGap))

        AutoResizeText(
            text = label,
            color = contentColor,
            modifier = Modifier
                .weight(1f)
                .graphicsLayer { alpha = labelAlpha }
        )
    }
    }
}

/** V2 owns its shaped fill and focus; avoid nesting TV Card's surface layers inside it. */
@Composable
private fun SidebarItemSurface(
    isV2: Boolean,
    backgroundColor: Color,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    if (isV2) {
        Box(modifier.background(backgroundColor, shape)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)) {
            content()
        }
    } else {
        Card(
            onClick = onClick,
            modifier = modifier,
            colors = CardDefaults.colors(
                containerColor = backgroundColor,
                focusedContainerColor = backgroundColor
            ),
            border = CardDefaults.border(
                border = androidx.tv.material3.Border.None,
                focusedBorder = androidx.tv.material3.Border(
                    border = androidx.compose.foundation.BorderStroke(NuvioStrokes.tokens.thin, Color.Transparent),
                    shape = shape
                )
            ),
            shape = CardDefaults.shape(shape = shape),
            scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
        ) { content() }
    }
}

@Composable
private fun SidebarProfileItem(
    profileName: String,
    profileColorHex: String,
    profileAvatarImageUrl: String?,
    focusEnabled: Boolean,
    labelAlpha: Float,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    val isV2 = com.nuvio.tv.ui.v2.appearance.LocalV2Appearance.current != null
    val shape = RoundedCornerShape(if (isV2) 8.dp else NuvioRadii.tokens.full)
    val backgroundColor = if (isFocused) Color.White.copy(alpha = 0.12f) else Color.Transparent
    SidebarItemSurface(
        isV2 = isV2,
        backgroundColor = backgroundColor,
        shape = shape,
        onClick = onClick,
        modifier = modifier
            .onFocusChanged {
                isFocused = it.hasFocus
                onFocusChanged(it.hasFocus)
            }
            .focusProperties { canFocus = focusEnabled }
            .then(if (isV2) Modifier.nuvioV2Focus(isFocused, shape, hardwareShadow = false) else Modifier)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTheme.spacing.lg - NuvioTheme.spacing.xxs, vertical = NuvioTheme.spacing.sm + NuvioTheme.spacing.xxs),
            verticalAlignment = Alignment.CenterVertically
        ) {
        Box(
            modifier = Modifier.size(SidebarLeadingVisualSize),
            contentAlignment = Alignment.Center
        ) {
            ProfileAvatarCircle(
                name = profileName,
                colorHex = profileColorHex,
                size = SidebarLeadingVisualSize,
                avatarImageUrl = profileAvatarImageUrl,
                imageCrossfade = false
            )
        }
        Spacer(modifier = Modifier.width(SidebarProfileContentGap))
        AutoResizeText(
            text = profileName,
            color = colors.text.onOverlay,
            modifier = Modifier
                .weight(1f)
                .graphicsLayer { alpha = labelAlpha },
            style = androidx.tv.material3.MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.SemiBold,
                textDirection = profileName.contentTextDirection()
            )
        )
    }
    }
}

@Composable
private fun rememberRawSvgPainter(rawIconRes: Int): Painter {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val sizePx = with(density) { NuvioTheme.spacing.xl.roundToPx() }
    return rememberAsyncImagePainter(
        model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
            .data(rawIconRes)
            .size(sizePx)
            .build()
    )
}
