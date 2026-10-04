@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.Key
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.core.streams.StreamBadgePlacement
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.components.SourceChipItem
import com.nuvio.tv.ui.components.PlayerPanelRow
import com.nuvio.tv.ui.components.SourceChipStatus
import com.nuvio.tv.ui.components.SourceStatusFilterChip
import com.nuvio.tv.domain.model.DebridStreamPreferences
import com.nuvio.tv.domain.model.DebridStreamResolution
import com.nuvio.tv.domain.model.DebridStreamQuality
import com.nuvio.tv.domain.model.DebridStreamVisualTag
import com.nuvio.tv.domain.model.DebridStreamAudioTag
import com.nuvio.tv.domain.model.DebridStreamAudioChannel
import com.nuvio.tv.domain.model.DebridStreamEncode
import com.nuvio.tv.core.debrid.DirectDebridStreamFilter
import com.nuvio.tv.ui.theme.NuvioTheme
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch as coroutineLaunch
import com.nuvio.tv.ui.components.RefreshFilterChip
import com.nuvio.tv.R

@Composable
internal fun StreamItem(
    stream: Stream,
    focusRequester: FocusRequester? = null,
    requestInitialFocus: Boolean = true,
    isCurrentStream: Boolean = false,
    isDeadSource: Boolean = false,
    showFileSizeBadges: Boolean = true,
    showAddonLogo: Boolean = true,
    badgePlacement: StreamBadgePlacement = StreamBadgePlacement.BOTTOM,
    onClick: () -> Unit,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    onUpKey: (() -> Unit)? = null
) {
    // Title: "addon - release group" (release group dropped when underivable, worst
    // case addon name alone). Everything else reads off a single facts line derived
    // from the classifier (factsFor), which regexes the raw name and so stays populated
    // even when a debrid source's structured resolver parse is empty.
    val releaseGroup = remember(stream) {
        DirectDebridStreamFilter.releaseGroupOf(stream)
    }
    val rowTitle = listOfNotNull(
        stream.addonName,
        releaseGroup.takeIf { it.isNotBlank() }
    ).joinToString(" - ")

    // Classify once per stream. factsFor's classification fields are
    // preference-independent (only its *Rank fields read preferences), so default
    // preferences give correct facts. Cheap enough to do on the composition thread for
    // the bounded set of visible rows; remember caches per stream.
    val facts = remember(stream) {
        DirectDebridStreamFilter.factsFor(stream, DebridStreamPreferences())
    }
    val factsLine = remember(facts) { streamFactsLine(facts) }
    val sizeLabel = if (showFileSizeBadges) remember(facts.size) {
        facts.size?.let { bytes ->
            if (bytes >= 1_073_741_824L) "%.1f GB".format(bytes / 1_073_741_824.0)
            else "%.0f MB".format(bytes / 1_048_576.0)
        }
    } else null

    PlayerPanelRow(
        title = rowTitle,
        titleEnd = sizeLabel,
        subtitle = factsLine.ifBlank { null },
        selected = isCurrentStream,
        onClick = onClick,
        focusRequester = if (requestInitialFocus) focusRequester else null,
        modifier = Modifier
            .then(if (isDeadSource) Modifier.alpha(0.45f) else Modifier)
            .onFocusChanged {
                onFocusChanged?.invoke(it.isFocused)
            }
            .then(if (onUpKey != null) Modifier.onKeyEvent { event ->
                if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN &&
                    event.key == Key.DirectionUp) {
                    onUpKey(); true
                } else false
            } else Modifier),
        trailing = null
    )
}

/**
 * Builds the single facts line shown beneath a stream's title, e.g.
 * "4K · BluRay REMUX · DV · HDR10 · HEVC · 10-bit · TrueHD · Atmos · 7.1".
 * The classifier's tag lists are additive and redundant (a DV+HDR10 file emits
 * HDR+DV, HDR10, DV, HDR, 10-bit), so this collapses them: DV once, the single
 * most-specific HDR flavour, and a lossless-first primary audio codec plus the
 * object-audio flag. Empty/unknown fields are dropped, so sparse sources degrade
 * cleanly rather than showing "Unknown".
 */
private fun streamFactsLine(facts: DirectDebridStreamFilter.StreamFacts): String {
    val parts = mutableListOf<String>()

    if (facts.resolution != DebridStreamResolution.UNKNOWN) {
        parts += if (facts.resolution == DebridStreamResolution.P2160) "4K" else facts.resolution.label
    }
    if (facts.quality != DebridStreamQuality.UNKNOWN) parts += facts.quality.label

    val vt = facts.visualTags
    if (vt.contains(DebridStreamVisualTag.DV)) parts += "DV"
    val hdrFlavour = when {
        vt.contains(DebridStreamVisualTag.HDR10_PLUS) -> "HDR10+"
        vt.contains(DebridStreamVisualTag.HDR10) -> "HDR10"
        vt.contains(DebridStreamVisualTag.HLG) -> "HLG"
        vt.contains(DebridStreamVisualTag.HDR) -> "HDR"
        else -> null
    }
    if (hdrFlavour != null) parts += hdrFlavour
    if (vt.contains(DebridStreamVisualTag.IMAX)) parts += "IMAX"

    if (facts.encode != DebridStreamEncode.UNKNOWN) parts += facts.encode.label
    if (vt.contains(DebridStreamVisualTag.TEN_BIT)) parts += "10-bit"

    val at = facts.audioTags
    val atmos = at.contains(DebridStreamAudioTag.ATMOS)
    val dtsx = at.contains(DebridStreamAudioTag.DTS_X)
    val dtsFamily = setOf(
        DebridStreamAudioTag.DTS_HD_MA, DebridStreamAudioTag.DTS_HD,
        DebridStreamAudioTag.DTS_ES, DebridStreamAudioTag.DTS
    )
    // Lossless-first so a TrueHD+DD compat pairing shows TrueHD, not DD.
    val basePriority = listOf(
        DebridStreamAudioTag.TRUEHD, DebridStreamAudioTag.DTS_HD_MA, DebridStreamAudioTag.DTS_HD,
        DebridStreamAudioTag.DTS_ES, DebridStreamAudioTag.DTS, DebridStreamAudioTag.FLAC,
        DebridStreamAudioTag.DD_PLUS, DebridStreamAudioTag.DD, DebridStreamAudioTag.OPUS,
        DebridStreamAudioTag.AAC
    )
    val base = basePriority.firstOrNull { at.contains(it) }
    if (dtsx) {
        parts += "DTS:X"
        if (base != null && base !in dtsFamily) parts += base.label
    } else if (base != null) {
        parts += base.label
    }
    if (atmos) parts += "Atmos"

    facts.audioChannels.firstOrNull { it != DebridStreamAudioChannel.UNKNOWN }?.let {
        parts += it.label
    }

    return parts.joinToString(" · ")
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun AddonFilterChips(
    addons: List<String>,
    sourceChips: List<SourceChipItem> = emptyList(),
    selectedAddon: String?,
    isStillFetching: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    onAddonSelected: (String?) -> Unit,
    externalFocusRequesters: List<FocusRequester>? = null,
    externalOrderedNames: List<String>? = null,
    onUpKey: (() -> Unit)? = null,
    debugTag: String = "AddonFilterChips"
) {
    val isRtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val chipMap = sourceChips.associateBy { it.name }
    val orderedNames = externalOrderedNames ?: remember(addons, sourceChips) {
        buildList {
            addAll(addons)
            sourceChips.forEach { chip -> if (chip.name !in this) add(chip.name) }
        }
    }
    val hasRefresh = onRefresh != null
    val refreshFocusRequester = remember { FocusRequester() }
    val allFocusRequester = remember { FocusRequester() }
    val addonFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val focusRequesters = externalFocusRequesters ?: remember(orderedNames) {
        buildList {
            if (hasRefresh) add(refreshFocusRequester)
            add(allFocusRequester)
            orderedNames.forEach { addon ->
                add(addonFocusRequesters.getOrPut(addon) { FocusRequester() })
            }
        }
    }

    var chipRowHasFocus by remember { mutableStateOf(false) }
    var refreshHasFocus by remember { mutableStateOf(false) }
    var focusedChipIndex by remember { mutableStateOf(
        if (hasRefresh) {
            if (selectedAddon == null) 1 else (orderedNames.indexOf(selectedAddon) + 2).coerceAtLeast(1)
        } else {
            if (selectedAddon == null) 0 else (orderedNames.indexOf(selectedAddon) + 1).coerceAtLeast(0)
        }
    ) }
    LaunchedEffect(selectedAddon, orderedNames) {
        if (refreshHasFocus || focusedChipIndex == 0) return@LaunchedEffect
        val maxIndex = if (hasRefresh) orderedNames.size + 1 else orderedNames.size
        if (focusedChipIndex > maxIndex) {
            focusedChipIndex = maxIndex.coerceAtLeast(if (hasRefresh) 1 else 0)
        }
        val currentAddonAtFocus = if (hasRefresh) {
            if (focusedChipIndex == 1) null else orderedNames.getOrNull(focusedChipIndex - 2)
        } else {
            if (focusedChipIndex == 0) null else orderedNames.getOrNull(focusedChipIndex - 1)
        }
        if (currentAddonAtFocus == selectedAddon) return@LaunchedEffect
        val idx = if (hasRefresh) {
            if (selectedAddon == null) 1 else (orderedNames.indexOf(selectedAddon) + 2).coerceAtLeast(1)
        } else {
            if (selectedAddon == null) 0 else (orderedNames.indexOf(selectedAddon) + 1).coerceAtLeast(0)
        }
        focusedChipIndex = idx.coerceIn(0, maxIndex)
        // When orderedNames changed (new addon arrived) and chip row has focus,
        // move actual focus to the correct chip so highlight doesn't stick on the wrong one.
        if (chipRowHasFocus) {
            withFrameNanos {}
            if (idx in focusRequesters.indices) {
                runCatching { focusRequesters[idx].requestFocus() }
            }
        }
    }
    val scope = rememberCoroutineScope()
    val lastKeyRepeatDispatchRef = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    fun moveFocusTo(targetIndex: Int) {
        focusedChipIndex = targetIndex
        if (hasRefresh && targetIndex == 0) {
            refreshHasFocus = true
            scope.coroutineLaunch {
                withFrameNanos {}
                try { focusRequesters[0].requestFocus() } catch (_: Exception) {}
            }
            return
        }

        refreshHasFocus = false
        val selectedFilter = if (hasRefresh) {
            if (targetIndex == 1) null else orderedNames.getOrNull(targetIndex - 2)
        } else {
            if (targetIndex == 0) null else orderedNames.getOrNull(targetIndex - 1)
        }
        onAddonSelected(selectedFilter)
        scope.coroutineLaunch {
            withFrameNanos {}
            if (targetIndex in focusRequesters.indices) {
                try { focusRequesters[targetIndex].requestFocus() } catch (_: Exception) {}
            }
        }
    }

    val chipListState = androidx.compose.foundation.lazy.rememberLazyListState()

    LaunchedEffect(focusedChipIndex) {
        if (focusedChipIndex >= 0 && !chipRowHasFocus) {
            chipListState.animateScrollToItem(focusedChipIndex)
        }
    }

    // When the selected addon is removed, switch filter to the last available addon
    LaunchedEffect(selectedAddon, orderedNames) {
        if (selectedAddon != null && selectedAddon !in orderedNames) {
            val lastIndex = if (hasRefresh) orderedNames.size + 1 else orderedNames.size
            moveFocusTo(lastIndex.coerceAtLeast(if (hasRefresh) 1 else 0))
        }
    }

    LazyRow(
        state = chipListState,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg),
        contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xs),
        modifier = Modifier
            .onFocusChanged { focusState ->
                val hasFocus = focusState.hasFocus
                if (hasFocus && !chipRowHasFocus && isRtl) {
                    scope.coroutineLaunch {
                        withFrameNanos {}
                        focusRequesters.getOrNull(focusedChipIndex)?.requestFocus()
                    }
                }
                chipRowHasFocus = hasFocus
            }
            .onKeyEvent { event ->
                if (event.nativeKeyEvent.action != android.view.KeyEvent.ACTION_DOWN) return@onKeyEvent false

                // Throttle rapid key repeats (long-press)
                if (event.nativeKeyEvent.repeatCount > 0) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastKeyRepeatDispatchRef.get() < 112L) return@onKeyEvent true
                    lastKeyRepeatDispatchRef.set(now)
                }

                if (event.key == androidx.compose.ui.input.key.Key.DirectionUp && onUpKey != null) {
                    onUpKey()
                    return@onKeyEvent true
                }

                val lastIndex = if (hasRefresh) orderedNames.size + 1 else orderedNames.size
                val currentIdx = focusedChipIndex.coerceIn(0, lastIndex)
                when (event.key) {
                    androidx.compose.ui.input.key.Key.DirectionLeft -> {
                        if (isRtl) {
                            if (currentIdx < lastIndex) { moveFocusTo(currentIdx + 1); true } else true
                        } else {
                            if (currentIdx > 0) { moveFocusTo(currentIdx - 1); true } else true
                        }
                    }
                    androidx.compose.ui.input.key.Key.DirectionRight -> {
                        if (isRtl) {
                            if (currentIdx > 0) { moveFocusTo(currentIdx - 1); true } else true
                        } else {
                            if (currentIdx < lastIndex) { moveFocusTo(currentIdx + 1); true } else true
                        }
                    }
                    else -> false
                }
            }
    ) {
        if (onRefresh != null) {
            item {
                RefreshFilterChip(
                    onClick = onRefresh,
                    isLoading = isStillFetching,
                    onFocusChanged = { isFocused ->
                        refreshHasFocus = isFocused
                        if (isFocused) focusedChipIndex = 0
                    },
                    modifier = Modifier
                        .focusRequester(focusRequesters[0])
                        .focusProperties { canFocus = focusedChipIndex == 0 }
                )
            }
        }

        item {
            val isAllSelected = selectedAddon == null && !refreshHasFocus
            val allChipIndex = if (hasRefresh) 1 else 0
            SourceStatusFilterChip(
                name = stringResource(R.string.stream_filter_all),
                isSelected = isAllSelected,
                status = SourceChipStatus.SUCCESS,
                isSelectable = true,
                onClick = { onAddonSelected(null) },
                modifier = Modifier
                    .focusRequester(focusRequesters[allChipIndex])
                    .onFocusChanged {
                        if (it.isFocused) {
                            focusedChipIndex = allChipIndex
                            refreshHasFocus = false
                        }
                    }
            )
        }

        items(orderedNames.size) { i ->
            val addon = orderedNames[i]
            val chipStatus = chipMap[addon]?.status ?: SourceChipStatus.SUCCESS
            val isSelectable = addon in addons && chipStatus == SourceChipStatus.SUCCESS
            val requesterIdx = if (hasRefresh) i + 2 else i + 1
            SourceStatusFilterChip(
                name = addon,
                isSelected = selectedAddon == addon,
                status = chipStatus,
                isSelectable = isSelectable,
                onClick = { if (isSelectable) onAddonSelected(addon) },
                modifier = Modifier
                    .focusRequester(focusRequesters[requesterIdx])
                    .onFocusChanged {
                        if (it.isFocused) {
                            focusedChipIndex = requesterIdx
                            refreshHasFocus = false
                        }
                    }
            )
        }
    }
}
