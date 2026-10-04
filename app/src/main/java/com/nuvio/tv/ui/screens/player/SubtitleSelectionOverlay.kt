@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.SubtitleEdgeStyle
import com.nuvio.tv.ui.screens.settings.SubtitleFontDialog
import com.nuvio.tv.ui.screens.settings.SubtitleEdgeDialog
import com.nuvio.tv.ui.screens.settings.subtitleFontName
import com.nuvio.tv.ui.screens.settings.subtitleEdgeName
import com.nuvio.tv.ui.components.PanelEyebrow
import com.nuvio.tv.ui.components.PanelActionRow
import com.nuvio.tv.ui.components.PlayerPanelRow
import com.nuvio.tv.ui.v2.player.v2PlayerPanel
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.theme.NuvioTheme

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.res.stringResource
import kotlin.math.roundToInt
import androidx.tv.material3.Card
import androidx.tv.material3.Border
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.local.SUBTITLE_LANGUAGE_FORCED
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncedChip

private const val SubtitleOffLanguageKey = "__off__"
private const val SubtitleUnknownLanguageKey = "__unknown__"
private const val SubtitleFocusTag = "SubtitleFocus"



private const val RailFadeDurationMs = 120

@Composable
internal fun SubtitleSelectionOverlay(
    visible: Boolean,
    internalTracks: List<TrackInfo>,
    selectedInternalIndex: Int,
    addonSubtitles: List<Subtitle>,
    selectedAddonSubtitle: Subtitle?,
    subtitleStyle: SubtitleStyleSettings,
    subtitleDelayMs: Int,
    installedSubtitleAddonOrder: List<String>,
    isLoadingAddons: Boolean,
    useLibass: Boolean = false,
    isUsingMpv: Boolean = false,
    onInternalTrackSelected: (Int) -> Unit,
    onAddonSubtitleSelected: (Subtitle) -> Unit,
    onDisableSubtitles: () -> Unit,
    onEvent: (PlayerEvent) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val noneLabel = stringResource(R.string.subtitle_none)
    val unknownLabel = stringResource(R.string.subtitle_language_unknown)
    val builtInLabel = stringResource(R.string.subtitle_built_in)
    val forcedLabel = stringResource(R.string.sub_forced_lang)

    // Session snapshots: the open panel works from the state at open time,
    // so mid-session addon arrivals don't reshuffle focus.
    val sessionInternalTracks = remember(visible) { internalTracks.map(TrackInfo::copy) }
    // Live keys: addon subtitles arrive progressively (sidecar
    // pipeline), so the snapshot re-takes when the list grows; the selection id
    // and open-time scroll position stay frozen so focus does not reshuffle.
    val sessionAddonSubtitles = remember(visible, addonSubtitles) { addonSubtitles.map(Subtitle::copy) }
    val sessionInstalledOrder = remember(visible) { installedSubtitleAddonOrder.toList() }
    val sessionIsLoadingAddons = isLoadingAddons
    val sessionSelectedOptionId = remember(visible) {
        selectedSubtitleOptionId(
            internalTracks = sessionInternalTracks,
            selectedInternalIndex = selectedInternalIndex,
            selectedAddonSubtitle = selectedAddonSubtitle
        )
    }
    // Language first: the panel lists the languages (preferred ones first, showOnlyPreferredLanguages
    // honoured, alphabetical after), then the subtitles of the one picked. Recomputes when addon
    // subtitles arrive (sessionAddonSubtitles key).
    val openLanguageKey = remember(visible) {
        selectedSubtitleLanguageKey(
            internalTracks = sessionInternalTracks,
            selectedInternalIndex = selectedInternalIndex,
            selectedAddonSubtitle = selectedAddonSubtitle
        )
    }
    val languageItems = remember(visible, sessionSelectedOptionId, sessionAddonSubtitles) {
        buildSubtitleLanguageRailItems(
            internalTracks = sessionInternalTracks,
            addonSubtitles = sessionAddonSubtitles,
            preferredLanguage = subtitleStyle.preferredLanguage,
            secondaryPreferredLanguage = subtitleStyle.secondaryPreferredLanguage,
            showOnlyPreferredLanguages = subtitleStyle.showOnlyPreferredLanguages,
            currentLanguageKey = openLanguageKey,
            noneLabel = noneLabel,
            unknownLabel = unknownLabel
        ).filter { it.key != SubtitleOffLanguageKey }
    }
    val optionsByLanguage = remember(languageItems, sessionAddonSubtitles) {
        languageItems.associate { language ->
            language.key to buildSubtitleOptionRailItems(
                selectedLanguageKey = language.key,
                internalTracks = sessionInternalTracks,
                addonSubtitles = sessionAddonSubtitles,
                installedAddonOrder = sessionInstalledOrder,
                selectedOptionId = sessionSelectedOptionId,
                builtInLabel = builtInLabel,
                forcedLabel = forcedLabel
            )
        }
    }
    val flatOptions = remember(optionsByLanguage) { optionsByLanguage.values.flatten() }

    var editorOpen by remember(visible) { mutableStateOf(false) }
    var currentSelectedOptionId by remember(visible) { mutableStateOf(sessionSelectedOptionId) }
    // null = the language list is showing; otherwise the subtitles of that language.
    var shownLanguageKey by remember(visible) {
        mutableStateOf(openLanguageKey.takeUnless { it == SubtitleOffLanguageKey })
    }
    // The language row that takes focus when the language list comes up.
    var languageFocusKey by remember(visible) { mutableStateOf(shownLanguageKey) }
    val shownLanguage = languageItems.firstOrNull { it.key == shownLanguageKey }
    val shownOptions = shownLanguage?.let { optionsByLanguage[it.key] }.orEmpty()
    BackHandler(enabled = visible && !editorOpen && shownLanguage != null && languageItems.size > 1) {
        languageFocusKey = shownLanguageKey
        shownLanguageKey = null
    }
    fun select(item: SubtitleOptionRailItem) {
        when (item.kind) {
            SubtitleOptionKind.INTERNAL -> item.internalTrackIndex?.let { trackIndex ->
                currentSelectedOptionId = item.id
                onInternalTrackSelected(trackIndex)
            }
            SubtitleOptionKind.ADDON -> item.addonSubtitle?.let { subtitle ->
                currentSelectedOptionId = item.id
                onAddonSubtitleSelected(subtitle)
            }
        }
    }
    // Style controls are disabled while an ASS/SSA track is
    // selected under libass or MPV (they render the embedded styling themselves).
    val isStyleDisabledByLibass = remember(
        useLibass,
        isUsingMpv,
        flatOptions,
        currentSelectedOptionId,
        sessionInternalTracks,
        selectedInternalIndex,
        selectedAddonSubtitle
    ) {
        if (!useLibass && !isUsingMpv) return@remember false
        val selectedOption = flatOptions.firstOrNull { it.id == currentSelectedOptionId }
        val isAss = when (selectedOption?.kind) {
            SubtitleOptionKind.INTERNAL -> {
                val track = selectedOption.internalTrackIndex?.let { sessionInternalTracks.getOrNull(it) }
                val codec = track?.codec?.lowercase(java.util.Locale.US).orEmpty()
                codec.contains("ass") || codec.contains("ssa") || track?.name?.contains("ASS", ignoreCase = true) == true
            }
            SubtitleOptionKind.ADDON -> {
                val url = selectedOption.addonSubtitle?.url?.lowercase(java.util.Locale.US).orEmpty()
                url.contains(".ass") || url.contains(".ssa")
            }
            null -> {
                val currentInternalTrack = sessionInternalTracks.getOrNull(selectedInternalIndex)
                val internalCodec = currentInternalTrack?.codec?.lowercase(java.util.Locale.US).orEmpty()
                val addonUrl = selectedAddonSubtitle?.url?.lowercase(java.util.Locale.US).orEmpty()
                internalCodec.contains("ass") || internalCodec.contains("ssa") ||
                    currentInternalTrack?.name?.contains("ASS", ignoreCase = true) == true ||
                    addonUrl.contains(".ass") || addonUrl.contains(".ssa")
            }
        }
        isAss
    }
    val listFocusRequester = remember { FocusRequester() }
    // Row 0 is "None" on the language list and "Languages" on a language's list.
    val focusOptionId = shownOptions.firstOrNull { it.id == currentSelectedOptionId }?.id
        ?: shownOptions.firstOrNull()?.id
    val listState = remember(visible, shownLanguage?.key) {
        val index = if (shownLanguage == null) {
            languageItems.indexOfFirst { it.key == languageFocusKey } + 1
        } else {
            shownOptions.indexOfFirst { it.id == focusOptionId } + 1
        }
        LazyListState(firstVisibleItemIndex = (index - 1).coerceAtLeast(0))
    }
    val styleListState = remember(visible) { LazyListState() }
    val styleRequesters = rememberStyleFocusRequesters()

    LaunchedEffect(shownLanguage?.key) {
        if (!visible || editorOpen) return@LaunchedEffect
        kotlinx.coroutines.delay(80)
        runCatching { listFocusRequester.requestFocus() }
    }

    LaunchedEffect(visible, editorOpen) {
        if (!visible) return@LaunchedEffect
        kotlinx.coroutines.delay(120)
        if (!editorOpen) {
            runCatching { listFocusRequester.requestFocus() }
        } else {
            runCatching { styleRequesters[StyleFocusKey.DelaySet]?.requestFocus() }
        }
    }

    PlayerOverlayScaffold(
        visible = visible,
        onDismiss = onDismiss,
        modifier = modifier,
        captureKeys = false,
        contentPadding = PaddingValues(start = 44.dp, end = 44.dp, top = 28.dp, bottom = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .width(if (LocalV2Appearance.current != null) 400.dp else 320.dp)
                .align(Alignment.BottomEnd)
                .heightIn(max = 620.dp)
                .v2PlayerPanel()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            PanelEyebrow(text = stringResource(R.string.subtitle_dialog_title))

            PanelActionRow(
                label = if (editorOpen) {
                    stringResource(R.string.panel_audio_back_to_tracks)
                } else {
                    stringResource(R.string.subtitle_style_title)
                },
                onClick = { editorOpen = !editorOpen }
            )

            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))

            if (editorOpen) {
                val codec = sessionInternalTracks.getOrNull(selectedInternalIndex)?.codec.orEmpty().lowercase(java.util.Locale.US)
                val bitmapTrack = selectedAddonSubtitle == null && isBitmapSubtitleCodec(codec)
                if (bitmapTrack) {
                    Text(stringResource(if (isUsingMpv) R.string.subtitle_bitmap_mpv_note else R.string.subtitle_bitmap_style_note), style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = .7f), modifier = Modifier.padding(bottom = 8.dp))
                }
                SubtitleStyleRail(
                    subtitleStyle = subtitleStyle,
                    subtitleDelayMs = subtitleDelayMs,
                    listState = styleListState,
                    onMoveLeft = {},
                    focusRequesters = styleRequesters,
                    onStyleFocused = {},
                    onEvent = onEvent,
                    isStyleDisabledByLibass = isStyleDisabledByLibass,
                    bitmapTrack = bitmapTrack,
                    bitmapScaleAvailable = !isUsingMpv
                )
            } else if (flatOptions.isEmpty() && sessionIsLoadingAddons) {
                OverlayLoadingCard(text = stringResource(R.string.subtitle_loading_addon))
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(top = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.sm),
                    modifier = Modifier
                        .heightIn(max = 500.dp)
                        .fillMaxWidth()
                ) {
                    if (shownLanguage == null) {
                        val focusLanguage = languageItems.firstOrNull { it.key == languageFocusKey }?.key
                        item(key = "subtitle_off") {
                            PlayerPanelRow(
                                title = noneLabel,
                                selected = currentSelectedOptionId == null,
                                onClick = {
                                    currentSelectedOptionId = null
                                    onDisableSubtitles()
                                },
                                focusRequester = if (focusLanguage == null) listFocusRequester else null
                            )
                        }
                        items(items = languageItems, key = { "language:${it.key}" }) { language ->
                            val options = optionsByLanguage[language.key].orEmpty()
                            PlayerPanelRow(
                                title = language.label,
                                titleEnd = options.size.toString(),
                                selected = options.any { it.id == currentSelectedOptionId },
                                onClick = {
                                    // A language with one subtitle needs no second step.
                                    if (options.size == 1) {
                                        select(options.first())
                                    } else {
                                        shownLanguageKey = language.key
                                    }
                                },
                                focusRequester = if (language.key == focusLanguage) listFocusRequester else null
                            )
                        }
                    } else {
                        item(key = "subtitle_languages") {
                            PlayerPanelRow(
                                title = "\u2039 ${stringResource(R.string.subtitle_panel_languages)}",
                                titleEnd = "${shownLanguage.label} \u00b7 ${shownOptions.size}",
                                selected = false,
                                onClick = {
                                    languageFocusKey = shownLanguage.key
                                    shownLanguageKey = null
                                },
                                focusRequester = if (focusOptionId == null) listFocusRequester else null
                            )
                        }
                        items(items = shownOptions, key = { it.id }) { item ->
                            // The language is the heading; keep the track's own name only when it adds something.
                            val variant = item.title.takeIf { title ->
                                title.isNotBlank() &&
                                    !title.equals(shownLanguage.label, ignoreCase = true) &&
                                    !title.equals(shownLanguage.key, ignoreCase = true)
                            }
                            PlayerPanelRow(
                                title = if (variant != null) "$variant - ${item.sourceLabel}" else item.sourceLabel,
                                subtitle = item.meta,
                                selected = item.id == currentSelectedOptionId,
                                onClick = { select(item) },
                                onLongClick = {
                                    if (item.id == currentSelectedOptionId) {
                                        currentSelectedOptionId = null
                                        onDisableSubtitles()
                                    }
                                },
                                focusRequester = if (item.id == focusOptionId) listFocusRequester else null,
                                trailing = item.addonSubtitle?.let { subtitle ->
                                    @Composable { _: Boolean -> AutoSyncedChip(subtitle.url, item.id == currentSelectedOptionId) }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun SubtitleStyleRail(
    subtitleStyle: SubtitleStyleSettings,
    subtitleDelayMs: Int,
    listState: LazyListState,
    onMoveLeft: () -> Unit,
    focusRequesters: Map<String, FocusRequester>,
    onStyleFocused: (String) -> Unit,
    onEvent: (PlayerEvent) -> Unit,
    isStyleDisabledByLibass: Boolean = false,
    bitmapTrack: Boolean = false,
    bitmapScaleAvailable: Boolean = true
) {
    var showFontPicker by remember { mutableStateOf(false) }
    var showEdgePicker by remember { mutableStateOf(false) }
    val displayedSize = if (bitmapTrack) subtitleStyle.bitmapSize else subtitleStyle.size
    fun sizeEvent(value: Int): PlayerEvent = if (bitmapTrack) PlayerEvent.OnSetSubtitleBitmapSize(value) else PlayerEvent.OnSetSubtitleSize(value)
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val moveLeftKey = if (isRtl) android.view.KeyEvent.KEYCODE_DPAD_RIGHT else android.view.KeyEvent.KEYCODE_DPAD_LEFT
    // libass and MPV render ASS/SSA styling themselves, so the user style controls
    // are inert for those tracks: events are dropped and cards dimmed. Cards stay
    // focusable so D-pad can still leave the editor.
    val dispatchStyleEvent: (PlayerEvent) -> Unit = { event ->
        if (!isStyleDisabledByLibass) {
            onEvent(event)
        }
    }
    val styleCardModifier = if (isStyleDisabledByLibass) Modifier.alpha(0.35f) else Modifier
    Column(modifier = Modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
            modifier = Modifier
                .heightIn(max = 420.dp)
        ) {
            item {
                Card(
                    onClick = { dispatchStyleEvent(PlayerEvent.OnShowSubtitleDelayOverlay) },
                    colors = overlayCardColors(selected = false),
                    shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(styleCardModifier)
                        .focusRequester(requireNotNull(focusRequesters[StyleFocusKey.DelaySet]))
                        .onPreviewKeyEvent { event ->
                            when (event.nativeKeyEvent.keyCode) {
                                moveLeftKey -> {
                                    when (event.nativeKeyEvent.action) {
                                        android.view.KeyEvent.ACTION_DOWN -> {
                                            onMoveLeft()
                                            true
                                        }

                                        android.view.KeyEvent.ACTION_UP -> true
                                        else -> false
                                    }
                                }

                                else -> false
                            }
                        }
                        .onFocusChanged { if (it.isFocused) onStyleFocused(StyleFocusKey.DelaySet) },
                    scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = NuvioTheme.spacing.md, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.subtitle_tab_delay),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = formatSubtitleDelay(subtitleDelayMs),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
            item {
                OverlaySectionCard(
                    title = stringResource(if (bitmapTrack) R.string.subtitle_style_image_size else R.string.subtitle_style_font_size),
                    modifier = if (bitmapTrack && !bitmapScaleAvailable) Modifier.alpha(.35f) else styleCardModifier
                ) {
                    StepperRow(
                        value = "${displayedSize}%",
                        onDecrease = { if (!bitmapTrack || bitmapScaleAvailable) dispatchStyleEvent(sizeEvent(displayedSize - 10)) },
                        onIncrease = { if (!bitmapTrack || bitmapScaleAvailable) dispatchStyleEvent(sizeEvent(displayedSize + 10)) },
                        onMoveLeft = onMoveLeft,
                        decrementFocusRequester = focusRequesters[StyleFocusKey.FontSizeDecrease],
                        incrementFocusRequester = focusRequesters[StyleFocusKey.FontSizeIncrease],
                        decrementFocusKey = StyleFocusKey.FontSizeDecrease,
                        incrementFocusKey = StyleFocusKey.FontSizeIncrease,
                        onFocusChanged = onStyleFocused
                    )
                }
            }
            if (!bitmapTrack) {
                item {
                    OverlaySectionCard(title = stringResource(R.string.subtitle_font_family), modifier = styleCardModifier) {
                        SubtitleAppearanceButton(subtitleFontName(subtitleStyle.font), { showFontPicker = true },
                            Modifier.focusRequester(requireNotNull(focusRequesters[StyleFocusKey.FontFamily]))
                                .onFocusChanged { if (it.isFocused) onStyleFocused(StyleFocusKey.FontFamily) }
                                .onPreviewKeyEvent { event ->
                                    if (event.nativeKeyEvent.keyCode == moveLeftKey) {
                                        if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) onMoveLeft()
                                        true
                                    } else false
                                })
                    }
                }
                item {
                    OverlaySectionCard(
                        title = stringResource(R.string.subtitle_style_bold),
                        modifier = styleCardModifier
                    ) {
                        ToggleChip(
                            label = if (subtitleStyle.bold) stringResource(R.string.subtitle_style_on) else stringResource(R.string.subtitle_style_off),
                            isEnabled = subtitleStyle.bold,
                            onMoveLeft = onMoveLeft,
                            focusRequester = focusRequesters[StyleFocusKey.Bold],
                            focusKey = StyleFocusKey.Bold,
                            onFocused = onStyleFocused,
                            onClick = { dispatchStyleEvent(PlayerEvent.OnSetSubtitleBold(!subtitleStyle.bold)) }
                        )
                    }
                }
                item {
                    OverlaySectionCard(
                        title = stringResource(R.string.subtitle_style_text_color),
                        modifier = styleCardModifier
                    ) {
                        ColorChipRow(
                            colors = SubtitleTextColors,
                            selectedColor = subtitleStyle.textColor,
                            onMoveLeft = onMoveLeft,
                            focusRequesters = focusRequesters,
                            focusKeyPrefix = StyleFocusKey.TextColorPrefix,
                            onFocused = onStyleFocused,
                            onColorSelected = { color -> dispatchStyleEvent(PlayerEvent.OnSetSubtitleTextColor(color)) }
                        )
                    }
                }
                item {
                    OverlaySectionCard(
                        title = stringResource(R.string.subtitle_style_text_opacity),
                        modifier = styleCardModifier
                    ) {
                        val currentColor = Color(subtitleStyle.textColor)
                        val currentAlphaPercent = (currentColor.alpha * 100f).roundToInt().coerceIn(0, 100)
                        StepperRow(
                            value = "$currentAlphaPercent%",
                            onDecrease = {
                                val newAlpha = (currentAlphaPercent - 10).coerceAtLeast(0) / 100f
                                dispatchStyleEvent(PlayerEvent.OnSetSubtitleTextColor(currentColor.copy(alpha = newAlpha).toArgb()))
                            },
                            onIncrease = {
                                val newAlpha = (currentAlphaPercent + 10).coerceAtMost(100) / 100f
                                dispatchStyleEvent(PlayerEvent.OnSetSubtitleTextColor(currentColor.copy(alpha = newAlpha).toArgb()))
                            },
                            onMoveLeft = onMoveLeft,
                            decrementFocusRequester = focusRequesters[StyleFocusKey.OpacityDecrease],
                            incrementFocusRequester = focusRequesters[StyleFocusKey.OpacityIncrease],
                            decrementFocusKey = StyleFocusKey.OpacityDecrease,
                            incrementFocusKey = StyleFocusKey.OpacityIncrease,
                            onFocusChanged = onStyleFocused
                        )
                    }
                }
                item {
                    OverlaySectionCard(
                        title = stringResource(R.string.subtitle_edge_style),
                        modifier = styleCardModifier
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                            ToggleChip(
                                label = subtitleEdgeName(subtitleStyle.effectiveEdgeStyle),
                                isEnabled = subtitleStyle.effectiveEdgeStyle != SubtitleEdgeStyle.NONE,
                                onMoveLeft = onMoveLeft,
                                focusRequester = focusRequesters[StyleFocusKey.OutlineToggle],
                                focusKey = StyleFocusKey.OutlineToggle,
                                onFocused = onStyleFocused,
                                onClick = { showEdgePicker = true }
                            )
                            if (subtitleStyle.effectiveEdgeStyle == SubtitleEdgeStyle.OUTLINE) ColorChipRow(
                                colors = SubtitleOutlineColors,
                                selectedColor = subtitleStyle.outlineColor,
                                enabled = subtitleStyle.outlineEnabled,
                                onMoveLeft = onMoveLeft,
                                focusRequesters = focusRequesters,
                                focusKeyPrefix = StyleFocusKey.OutlineColorPrefix,
                                onFocused = onStyleFocused,
                                onColorSelected = { color ->
                                    if (!subtitleStyle.outlineEnabled) {
                                        dispatchStyleEvent(PlayerEvent.OnSetSubtitleOutlineEnabled(true))
                                    }
                                    dispatchStyleEvent(PlayerEvent.OnSetSubtitleOutlineColor(color))
                                }
                            )
                        }
                    }
                }
            }
            item {
                OverlaySectionCard(
                    title = stringResource(R.string.subtitle_style_bottom_offset),
                    modifier = styleCardModifier
                ) {
                    StepperRow(
                        value = subtitleStyle.verticalOffset.toString(),
                        onDecrease = { dispatchStyleEvent(PlayerEvent.OnSetSubtitleVerticalOffset(subtitleStyle.verticalOffset - 5)) },
                        onIncrease = { dispatchStyleEvent(PlayerEvent.OnSetSubtitleVerticalOffset(subtitleStyle.verticalOffset + 5)) },
                        onMoveLeft = onMoveLeft,
                        decrementFocusRequester = focusRequesters[StyleFocusKey.OffsetDecrease],
                        incrementFocusRequester = focusRequesters[StyleFocusKey.OffsetIncrease],
                        decrementFocusKey = StyleFocusKey.OffsetDecrease,
                        incrementFocusKey = StyleFocusKey.OffsetIncrease,
                        onFocusChanged = onStyleFocused
                    )
                }
            }
            item {
                Card(
                    onClick = { dispatchStyleEvent(PlayerEvent.OnResetSubtitleDefaults) },
                    colors = overlayCardColors(selected = false),
                    shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
                    modifier = Modifier
                        .then(styleCardModifier)
                        .focusRequester(requireNotNull(focusRequesters[StyleFocusKey.Reset]))
                        .onPreviewKeyEvent { event ->
                            when (event.nativeKeyEvent.keyCode) {
                                moveLeftKey -> {
                                    when (event.nativeKeyEvent.action) {
                                        android.view.KeyEvent.ACTION_DOWN -> {
                                            onMoveLeft()
                                            true
                                        }

                                        android.view.KeyEvent.ACTION_UP -> true
                                        else -> false
                                    }
                                }

                                else -> false
                            }
                        }
                        .onFocusChanged { if (it.isFocused) onStyleFocused(StyleFocusKey.Reset) },
                    scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
                ) {
                    Text(
                        text = stringResource(R.string.subtitle_reset_defaults),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md, vertical = 8.dp)
                    )
                }
            }
        }
    }
    if (showFontPicker && !bitmapTrack && !isStyleDisabledByLibass) SubtitleFontDialog(subtitleStyle.font,
        { dispatchStyleEvent(PlayerEvent.OnSetSubtitleFont(it)) }) { showFontPicker = false }
    if (showEdgePicker && !bitmapTrack && !isStyleDisabledByLibass) SubtitleEdgeDialog(subtitleStyle.effectiveEdgeStyle,
        { dispatchStyleEvent(PlayerEvent.OnSetSubtitleEdgeStyle(it)) }) { showEdgePicker = false }
}

@Composable
private fun OverlayLoadingCard(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            LoadingIndicator(modifier = Modifier.size(NuvioTheme.spacing.xl))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextTertiary
            )
        }
    }
}

@Composable
private fun OverlayEmptyCard(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 10.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = NuvioTheme.colors.TextTertiary
        )
    }
}

@Composable
private fun OverlaySectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White
        )
        content()
    }
}

@Composable
private fun StepperRow(
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    valueWidth: Dp = 84.dp,
    onMoveLeft: (() -> Unit)? = null,
    decrementFocusRequester: FocusRequester? = null,
    incrementFocusRequester: FocusRequester? = null,
    decrementFocusKey: String? = null,
    incrementFocusKey: String? = null,
    onFocusChanged: ((String) -> Unit)? = null
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StepperButton(
            icon = Icons.Default.Remove,
            onClick = onDecrease,
            onMoveLeft = onMoveLeft,
            focusRequester = decrementFocusRequester,
            focusKey = decrementFocusKey,
            onFocused = onFocusChanged
        )
        Box(
            modifier = Modifier
                .width(valueWidth)
                .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(NuvioTheme.radii.md))
                .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White
            )
        }
        StepperButton(
            icon = Icons.Default.Add,
            onClick = onIncrease,
            focusRequester = incrementFocusRequester,
            focusKey = incrementFocusKey,
            onFocused = onFocusChanged
        )
    }
}

@Composable
private fun StepperButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    onMoveLeft: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    focusKey: String? = null,
    onFocused: ((String) -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val moveLeftKey = if (isRtl) android.view.KeyEvent.KEYCODE_DPAD_RIGHT else android.view.KeyEvent.KEYCODE_DPAD_LEFT

    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(32.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onPreviewKeyEvent { event ->
                when (event.nativeKeyEvent.keyCode) {
                    moveLeftKey -> {
                        val moveLeft = onMoveLeft ?: return@onPreviewKeyEvent false
                        when (event.nativeKeyEvent.action) {
                            android.view.KeyEvent.ACTION_DOWN -> {
                                moveLeft()
                                true
                            }

                            android.view.KeyEvent.ACTION_UP -> true
                            else -> false
                        }
                    }

                    else -> false
                }
            }
            .then(
                if (isFocused) {
                    Modifier.border(NuvioTheme.spacing.xxs, Color.White, RoundedCornerShape(NuvioTheme.radii.md))
                } else {
                    Modifier
                }
            )
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused && focusKey != null) {
                    onFocused?.invoke(focusKey)
                }
            },
        colors = IconButtonDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.08f),
            focusedContainerColor = Color.White.copy(alpha = 0.14f),
            contentColor = Color.White,
            focusedContentColor = Color.White
        ),
        shape = IconButtonDefaults.shape(shape = RoundedCornerShape(NuvioTheme.radii.md)),
        scale = IconButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Icon(imageVector = icon, contentDescription = null)
    }
}

@Composable
private fun ToggleChip(
    label: String,
    isEnabled: Boolean,
    onMoveLeft: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    focusKey: String? = null,
    onFocused: ((String) -> Unit)? = null,
    onClick: () -> Unit
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val moveLeftKey = if (isRtl) android.view.KeyEvent.KEYCODE_DPAD_RIGHT else android.view.KeyEvent.KEYCODE_DPAD_LEFT
    Card(
        onClick = onClick,
        modifier = if (focusRequester != null) {
            Modifier
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    when (event.nativeKeyEvent.keyCode) {
                        moveLeftKey -> {
                            val moveLeft = onMoveLeft ?: return@onPreviewKeyEvent false
                            when (event.nativeKeyEvent.action) {
                                android.view.KeyEvent.ACTION_DOWN -> {
                                    moveLeft()
                                    true
                                }

                                android.view.KeyEvent.ACTION_UP -> true
                                else -> false
                            }
                        }

                        else -> false
                    }
                }
                .onFocusChanged {
                    if (it.isFocused && focusKey != null) onFocused?.invoke(focusKey)
                }
        } else {
            Modifier
                .onPreviewKeyEvent { event ->
                    when (event.nativeKeyEvent.keyCode) {
                        moveLeftKey -> {
                            val moveLeft = onMoveLeft ?: return@onPreviewKeyEvent false
                            when (event.nativeKeyEvent.action) {
                                android.view.KeyEvent.ACTION_DOWN -> {
                                    moveLeft()
                                    true
                                }

                                android.view.KeyEvent.ACTION_UP -> true
                                else -> false
                            }
                        }

                        else -> false
                    }
                }
                .onFocusChanged {
                    if (it.isFocused && focusKey != null) onFocused?.invoke(focusKey)
                }
        },
        colors = overlayCardColors(selected = isEnabled),
        shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isEnabled) Color.White else Color.White,
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.xs)
        )
    }
}

@Composable
private fun ColorChipRow(
    colors: List<Color>,
    selectedColor: Int,
    enabled: Boolean = true,
    onMoveLeft: (() -> Unit)? = null,
    focusRequesters: Map<String, FocusRequester>,
    focusKeyPrefix: String,
    onFocused: ((String) -> Unit)? = null,
    onColorSelected: (Int) -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(colors) { color ->
            val focusKey = "$focusKeyPrefix:${color.toArgb()}"
            ColorChip(
                color = if (enabled) color else color.copy(alpha = 0.35f),
                isSelected = color.toArgb() == selectedColor,
                enabled = enabled,
                onMoveLeft = if (color == colors.firstOrNull()) onMoveLeft else null,
                focusRequester = focusRequesters[focusKey],
                focusKey = focusKey,
                onFocused = onFocused,
                onClick = { onColorSelected(color.toArgb()) }
            )
        }
    }
}

@Composable
private fun ColorChip(
    color: Color,
    isSelected: Boolean,
    enabled: Boolean,
    onMoveLeft: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    focusKey: String? = null,
    onFocused: ((String) -> Unit)? = null,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val moveLeftKey = if (isRtl) android.view.KeyEvent.KEYCODE_DPAD_RIGHT else android.view.KeyEvent.KEYCODE_DPAD_LEFT

    Card(
        onClick = { if (enabled) onClick() },
        colors = CardDefaults.colors(
            containerColor = color,
            focusedContainerColor = color
        ),
        modifier = Modifier
            .size(NuvioTheme.spacing.xl)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onPreviewKeyEvent { event ->
                when (event.nativeKeyEvent.keyCode) {
                    moveLeftKey -> {
                        val moveLeft = onMoveLeft ?: return@onPreviewKeyEvent false
                        when (event.nativeKeyEvent.action) {
                            android.view.KeyEvent.ACTION_DOWN -> {
                                moveLeft()
                                true
                            }

                            android.view.KeyEvent.ACTION_UP -> true
                            else -> false
                        }
                    }

                    else -> false
                }
            }
            .then(
                when {
                    isSelected -> Modifier.border(NuvioTheme.spacing.xxs, Color.White, CircleShape)
                    isFocused -> Modifier.border(NuvioTheme.spacing.xxs, Color.White, CircleShape)
                    else -> Modifier
                }
            )
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused && focusKey != null) {
                    onFocused?.invoke(focusKey)
                }
            },
        shape = CardDefaults.shape(CircleShape)
        ,
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {}
}

@Composable
private fun overlayCardColors(selected: Boolean) = CardDefaults.colors(
    containerColor = if (selected) Color.White.copy(alpha = 0.16f) else Color.Transparent,
    focusedContainerColor = if (selected) Color.White.copy(alpha = 0.16f) else Color.Transparent
)

@Composable
private fun overlayCardBorder() = CardDefaults.border(
    border = Border(
        border = BorderStroke(NuvioTheme.spacing.xxs, Color.Transparent),
        shape = RoundedCornerShape(NuvioTheme.radii.md)
    ),
    focusedBorder = Border(
        border = BorderStroke(NuvioTheme.spacing.xxs, Color.White),
        shape = RoundedCornerShape(NuvioTheme.radii.md)
    )
)

private object StyleFocusKey {
    const val FontSizeDecrease = "font_size_decrease"
    const val FontSizeIncrease = "font_size_increase"
    const val Bold = "bold"
    const val FontFamily = "font_family"
    const val OutlineToggle = "outline_toggle"
    const val OffsetDecrease = "offset_decrease"
    const val OffsetIncrease = "offset_increase"
    const val DelaySet = "delay_set"
    const val Reset = "reset"
    const val TextColorPrefix = "text_color"
    const val OpacityDecrease = "opacity_decrease"
    const val OpacityIncrease = "opacity_increase"
    const val OutlineColorPrefix = "outline_color"
}

private enum class OverlayFocusRail {
    LANGUAGE,
    OPTION,
    STYLE
}

private fun styleListIndexForFocusKey(focusKey: String): Int {
    return when {
        focusKey == StyleFocusKey.DelaySet -> 0
        focusKey == StyleFocusKey.FontSizeDecrease || focusKey == StyleFocusKey.FontSizeIncrease -> 1
        focusKey == StyleFocusKey.FontFamily -> 2
        focusKey == StyleFocusKey.Bold -> 3
        focusKey.startsWith("${StyleFocusKey.TextColorPrefix}:") -> 4
        focusKey == StyleFocusKey.OpacityDecrease || focusKey == StyleFocusKey.OpacityIncrease -> 5
        focusKey == StyleFocusKey.OutlineToggle || focusKey.startsWith("${StyleFocusKey.OutlineColorPrefix}:") -> 6
        focusKey == StyleFocusKey.OffsetDecrease || focusKey == StyleFocusKey.OffsetIncrease -> 7
        focusKey == StyleFocusKey.Reset -> 8
        else -> 0
    }
}

@Composable
private fun rememberFocusRequesterMap(keys: List<String>): Map<String, FocusRequester> {
    return remember(keys) { keys.associateWith { FocusRequester() } }
}

@Composable
private fun rememberStyleFocusRequesters(): Map<String, FocusRequester> {
    return remember {
        listOf(
            StyleFocusKey.FontSizeDecrease,
            StyleFocusKey.FontSizeIncrease,
            StyleFocusKey.Bold,
            StyleFocusKey.FontFamily,
            StyleFocusKey.OpacityDecrease,
            StyleFocusKey.OpacityIncrease,
            StyleFocusKey.OutlineToggle,
            StyleFocusKey.OffsetDecrease,
            StyleFocusKey.OffsetIncrease,
            StyleFocusKey.DelaySet,
            StyleFocusKey.Reset
        ).associateWith { FocusRequester() } +
            SubtitleTextColors.associate { color ->
                "${StyleFocusKey.TextColorPrefix}:${color.toArgb()}" to FocusRequester()
            } +
            SubtitleOutlineColors.associate { color ->
                "${StyleFocusKey.OutlineColorPrefix}:${color.toArgb()}" to FocusRequester()
            }
    }
}

private suspend fun androidx.compose.foundation.lazy.LazyListState.scrollItemIntoView(
    targetIndex: Int,
    contextItemsBefore: Int = 1
) {
    if (layoutInfo.visibleItemsInfo.any { it.index == targetIndex }) return
    scrollToItem((targetIndex - contextItemsBefore).coerceAtLeast(0))
}

private fun preferredVisibleStartIndex(targetIndex: Int): Int {
    if (targetIndex < 0) return 0
    return (targetIndex - 1).coerceAtLeast(0)
}

private data class SubtitleLanguageRailItem(
    val key: String,
    val label: String,
    val count: Int
)

private enum class SubtitleOptionKind {
    INTERNAL,
    ADDON
}

private data class SubtitleOptionRailItem(
    val id: String,
    val kind: SubtitleOptionKind,
    val title: String,
    val sourceLabel: String,
    val meta: String?,
    val isSelected: Boolean,
    val internalTrackIndex: Int? = null,
    val addonSubtitle: Subtitle? = null
)

private fun buildSubtitleLanguageRailItems(
    internalTracks: List<TrackInfo>,
    addonSubtitles: List<Subtitle>,
    preferredLanguage: String,
    secondaryPreferredLanguage: String?,
    showOnlyPreferredLanguages: Boolean,
    currentLanguageKey: String,
    noneLabel: String,
    unknownLabel: String
): List<SubtitleLanguageRailItem> {
    val counts = linkedMapOf<String, Int>()
    internalTracks.forEach { track ->
        val key = normalizeOverlayLanguageKeyForTrack(track)
        counts[key] = (counts[key] ?: 0) + 1
    }
    addonSubtitles.forEach { subtitle ->
        val key = normalizeOverlayLanguageKey(subtitle.lang)
        counts[key] = (counts[key] ?: 0) + 1
    }

    val preferredOrder = preferredOverlayLanguageOrder(
        preferredLanguage = preferredLanguage,
        secondaryPreferredLanguage = secondaryPreferredLanguage
    )

    val languageEntries = if (showOnlyPreferredLanguages) {
        val preferredKeys = preferredOrder.toSet()
        counts.entries.filter { entry ->
            entry.key in preferredKeys || entry.key == currentLanguageKey
        }
    } else {
        counts.entries
    }

    val sortedItems = languageEntries
        .sortedWith(
            compareBy<Map.Entry<String, Int>>(
                { entry ->
                    val preferredIndex = preferredOrder.indexOf(entry.key)
                    if (preferredIndex >= 0) preferredIndex else Int.MAX_VALUE
                },
                { entry -> subtitleLanguageSortLabel(entry.key) }
            )
        )
        .map { (key, count) ->
            SubtitleLanguageRailItem(
                key = key,
                label = subtitleLanguageLabel(key, unknownLabel),
                count = count
            )
        }

    return listOf(
        SubtitleLanguageRailItem(
            key = SubtitleOffLanguageKey,
            label = noneLabel,
            count = 0
        )
    ) + sortedItems
}

private fun preferredOverlayLanguageOrder(
    preferredLanguage: String,
    secondaryPreferredLanguage: String?
): List<String> {
    fun toOverlayLanguageKey(language: String?): String? {
        if (language.isNullOrBlank()) return null
        val normalized = PlayerSubtitleUtils.normalizeLanguageCode(language)
        if (normalized == "none" || normalized == SUBTITLE_LANGUAGE_FORCED) return null
        return normalizeOverlayLanguageKey(language)
            .takeUnless { it == SubtitleUnknownLanguageKey }
    }

    return listOfNotNull(
        toOverlayLanguageKey(preferredLanguage),
        toOverlayLanguageKey(secondaryPreferredLanguage)
    ).distinct()
}

private fun buildSubtitleOptionRailItems(
    selectedLanguageKey: String,
    internalTracks: List<TrackInfo>,
    addonSubtitles: List<Subtitle>,
    installedAddonOrder: List<String>,
    selectedOptionId: String?,
    builtInLabel: String,
    forcedLabel: String
): List<SubtitleOptionRailItem> {
    if (selectedLanguageKey == SubtitleOffLanguageKey) return emptyList()

    val addonOrderMap = installedAddonOrder.withIndex().associate { (index, name) -> name to index }
    fun toAddonItem(subtitle: Subtitle): SubtitleOptionRailItem {
        val optionId = addonSubtitleOptionId(subtitle)
        return SubtitleOptionRailItem(
            id = optionId,
            kind = SubtitleOptionKind.ADDON,
            title = if (subtitle.isStreamProvided) {
                streamProvidedSubtitleTitle(subtitle)
            } else {
                Subtitle.languageCodeToName(PlayerSubtitleUtils.normalizeLanguageCode(subtitle.lang))
            },
            sourceLabel = if (subtitle.isStreamProvided) builtInLabel else subtitle.addonName,
            meta = subtitle.id.takeIf { it.isNotBlank() && it != subtitle.lang && it != subtitle.url },
            isSelected = optionId == selectedOptionId,
            addonSubtitle = subtitle
        )
    }

    val matchingAddonSubtitles = addonSubtitles
        .filter { normalizeOverlayLanguageKey(it.lang) == selectedLanguageKey }
        .distinctBy { addonSubtitleOptionId(it) }

    val streamProvidedItems = matchingAddonSubtitles
        .filter { it.isStreamProvided }
        .map(::toAddonItem)

    val internalItems = internalTracks
        .filter { normalizeOverlayLanguageKeyForTrack(it) == selectedLanguageKey }
        .map { track ->
            SubtitleOptionRailItem(
                id = "internal:${track.index}",
                kind = SubtitleOptionKind.INTERNAL,
                title = track.name,
                sourceLabel = builtInLabel,
                meta = listOfNotNull(
                    track.codec,
                    if (track.isForced) forcedLabel else null
                ).joinToString(" • ").ifBlank { null },
                isSelected = "internal:${track.index}" == selectedOptionId,
                internalTrackIndex = track.index
            )
        }

    val addonFetchedItems = matchingAddonSubtitles
        .filter { !it.isStreamProvided }
        .withIndex()
        .sortedWith(
            compareBy(
                { (_, subtitle) -> addonOrderMap[subtitle.addonName] ?: Int.MAX_VALUE },
                { (index, _) -> index }
            )
        )
        .map { (_, subtitle) -> toAddonItem(subtitle) }

    return internalItems + streamProvidedItems + addonFetchedItems
}

private fun selectedSubtitleLanguageKey(
    internalTracks: List<TrackInfo>,
    selectedInternalIndex: Int,
    selectedAddonSubtitle: Subtitle?
): String {
    val selectedAddonKey = selectedAddonSubtitle?.let { normalizeOverlayLanguageKey(it.lang) }
    if (selectedAddonKey != null) return selectedAddonKey

    val selectedInternalKey = internalTracks
        .firstOrNull { it.index == selectedInternalIndex }
        ?.let { normalizeOverlayLanguageKeyForTrack(it) }
        ?: internalTracks.firstOrNull { it.isSelected }
            ?.let { normalizeOverlayLanguageKeyForTrack(it) }
    if (selectedInternalKey != null) return selectedInternalKey

    return SubtitleOffLanguageKey
}

private fun selectedSubtitleOptionId(
    internalTracks: List<TrackInfo>,
    selectedInternalIndex: Int,
    selectedAddonSubtitle: Subtitle?
): String? {
    selectedAddonSubtitle?.let { subtitle ->
        return addonSubtitleOptionId(subtitle)
    }

    internalTracks
        .firstOrNull { it.index == selectedInternalIndex }
        ?.let { track ->
            return "internal:${track.index}"
        }

    internalTracks
        .firstOrNull { it.isSelected }
        ?.let { track ->
            return "internal:${track.index}"
        }

    return null
}

private fun addonSubtitleOptionId(subtitle: Subtitle): String {
    return "addon:${subtitle.addonName}:${subtitle.id}:${subtitle.url}"
}

private fun streamProvidedSubtitleTitle(subtitle: Subtitle): String {
    val name = subtitle.addonName.trim()
    if (name.isNotBlank() && !name.equals("Plugin", ignoreCase = true)) {
        return name
    }
    return subtitle.lang
}

private fun normalizeOverlayLanguageKey(language: String?): String {
    if (language.isNullOrBlank()) return SubtitleUnknownLanguageKey
    val normalized = PlayerSubtitleUtils.normalizeLanguageCode(language)
    return when (normalized) {
        "pt-br", "es-419" -> normalized
        else -> normalized
            .substringBefore('-')
            .substringBefore('_')
            .ifBlank { SubtitleUnknownLanguageKey }
    }
}

/**
 * Variant-aware language key for embedded tracks. Inspects name/label/trackId
 * to detect regional accents (e.g. Brazilian Portuguese, Latin American Spanish)
 * even when the language field is generic ("por", "spa").
 */
private fun normalizeOverlayLanguageKeyForTrack(track: TrackInfo): String {
    val variant = PlayerSubtitleUtils.detectTrackLanguageVariant(
        language = track.language,
        name = track.name,
        trackId = track.trackId
    )
    return when (variant) {
        "pt-br", "es-419" -> variant
        else -> variant
            .substringBefore('-')
            .substringBefore('_')
            .ifBlank { SubtitleUnknownLanguageKey }
    }
}

private fun subtitleLanguageLabel(key: String, unknownLabel: String): String {
    return when (key) {
        SubtitleOffLanguageKey -> Subtitle.languageCodeToName("none")
        SubtitleUnknownLanguageKey -> unknownLabel
        else -> Subtitle.languageCodeToName(key)
    }
}

private fun subtitleLanguageSortLabel(key: String): String = when (key) {
    SubtitleUnknownLanguageKey -> "\uFFFF"
    SubtitleOffLanguageKey -> Subtitle.languageCodeToName("none").lowercase()
    else -> Subtitle.languageCodeToName(key).lowercase()
}

private fun formatSubtitleDelay(delayMs: Int): String {
    return when {
        delayMs > 0 -> "+${delayMs}ms"
        delayMs < 0 -> "${delayMs}ms"
        else -> "0ms"
    }
}
