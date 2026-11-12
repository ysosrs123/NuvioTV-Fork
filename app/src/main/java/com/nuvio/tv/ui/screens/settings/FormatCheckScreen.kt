package com.nuvio.tv.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.focusProperties
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.components.GlassRole
import com.nuvio.tv.ui.v2.components.nuvioGlass
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import com.nuvio.tv.core.formatcheck.FormatCheckRetest
import com.nuvio.tv.core.formatcheck.FormatCheckStart
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.formatcheck.AudioRoute
import com.nuvio.tv.core.formatcheck.ClipResult
import com.nuvio.tv.core.formatcheck.DolbyVisionRoute
import com.nuvio.tv.core.formatcheck.FormatCheckAnswers
import com.nuvio.tv.core.formatcheck.FormatCheckCatalog
import com.nuvio.tv.core.formatcheck.FormatCheckMode
import com.nuvio.tv.core.formatcheck.FormatCheckPack
import com.nuvio.tv.core.formatcheck.FormatCheckRunState
import com.nuvio.tv.core.formatcheck.FormatCheckSession
import com.nuvio.tv.core.formatcheck.FormatCheckStore
import com.nuvio.tv.core.formatcheck.FormatCheckSuggestions
import com.nuvio.tv.core.formatcheck.FormatCheckVerdicts
import com.nuvio.tv.core.formatcheck.FormatClip
import com.nuvio.tv.core.formatcheck.FormatFamily
import com.nuvio.tv.core.formatcheck.FormatGroup
import com.nuvio.tv.core.formatcheck.FormatCheckSettingNotes
import com.nuvio.tv.core.formatcheck.FormatCheckSettings
import com.nuvio.tv.core.formatcheck.FormatSettingNote
import com.nuvio.tv.core.formatcheck.FormatSuggestion
import com.nuvio.tv.core.formatcheck.FormatVerdict
import com.nuvio.tv.core.formatcheck.LocalClipServer
import com.nuvio.tv.core.formatcheck.QuestionKind
import com.nuvio.tv.core.formatcheck.SavedFormatCheck
import com.nuvio.tv.core.formatcheck.StreamAction
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.screens.player.AudioOutputRouteDetector
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val FIRST_PICTURE_TIMEOUT_MS = 15_000L
private const val WATCH_MS = 3_000L
private const val BETWEEN_CLIPS_MS = 1_000L

private enum class FormatCheckPage { START, RESULTS, SUGGESTIONS }

private enum class SuggestionChoice { WARN, HIDE, APPLY, LEAVE }

@Composable
fun FormatCheckScreen(onBackPress: () -> Unit) {
    val context = LocalContext.current
    val saved by remember { FormatCheckStore.results(context) }.collectAsState(initial = null)
    val runState by FormatCheckSession.state.collectAsState()
    var page by remember { mutableStateOf<FormatCheckPage?>(null) }
    val shown = page ?: if (saved != null) FormatCheckPage.RESULTS else FormatCheckPage.START
    val running = runState is FormatCheckRunState.Preparing || runState is FormatCheckRunState.Playing
    BackHandler(enabled = !running && saved != null && shown != FormatCheckPage.RESULTS) { page = FormatCheckPage.RESULTS }
    LaunchedEffect(running) { if (running) page = null }

    Box(modifier = Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) {
        // Between clips this page shows behind the run panel; the last run's results would read as this one's.
        if (running) return@Box
        SettingsPageAtmosphere()
        when (shown) {
            FormatCheckPage.START -> StartPage(
                couldNotPrepare = runState == FormatCheckRunState.Failed,
                hasResults = saved != null,
                onResults = { page = FormatCheckPage.RESULTS }
            )
            FormatCheckPage.RESULTS -> saved?.let { result ->
                ResultsPage(
                    saved = result,
                    onSuggestions = { page = FormatCheckPage.SUGGESTIONS },
                    onRunAgain = { page = FormatCheckPage.START }
                )
            }
            FormatCheckPage.SUGGESTIONS -> saved?.let { result ->
                SuggestionsPage(saved = result, onDone = { page = FormatCheckPage.RESULTS })
            }
        }
    }
}

@Composable
private fun PageColumn(
    focusFirstRow: Boolean = true,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (focusFirstRow) runCatching { focus.requestFocus() } }
    LazyColumn(
        modifier = Modifier.fillMaxSize().focusRequester(focus).padding(horizontal = 48.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 32.dp),
        content = content
    )
}

@Composable
private fun Modifier.takesPageFocus(): Modifier {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    return focusRequester(focus)
}

@Composable
private fun PageTitle(text: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = text, style = MaterialTheme.typography.headlineMedium, color = NuvioTheme.colors.TextPrimary)
        if (subtitle != null) {
            Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
        }
    }
}

@Composable
private fun StartPage(couldNotPrepare: Boolean, hasResults: Boolean, onResults: () -> Unit) {
    PageColumn(focusFirstRow = false) {
        item { PageTitle(stringResource(R.string.format_check_title), stringResource(R.string.format_check_intro)) }
        item {
            SettingsGroupCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsActionRow(
                        title = stringResource(R.string.format_check_quick_title),
                        subtitle = stringResource(R.string.format_check_quick_subtitle),
                        modifier = Modifier.weight(1f).takesPageFocus(),
                        onClick = { FormatCheckSession.requestStart(FormatCheckMode.QUICK) }
                    )
                    SettingsActionRow(
                        title = stringResource(R.string.format_check_full_title),
                        subtitle = stringResource(R.string.format_check_full_subtitle),
                        modifier = Modifier.weight(1f),
                        onClick = { FormatCheckSession.requestStart(FormatCheckMode.FULL) }
                    )
                }
                if (hasResults) {
                    SettingsActionRow(
                        title = stringResource(R.string.format_check_see_results),
                        subtitle = null,
                        onClick = onResults
                    )
                }
            }
        }
        item {
            SettingsGroupCard(title = stringResource(R.string.format_check_expect_title)) {
                listOf(
                    R.string.format_check_expect_nothing,
                    R.string.format_check_expect_mode,
                    R.string.format_check_expect_sound,
                    R.string.format_check_expect_stop
                ).forEach { line ->
                    Text(
                        text = stringResource(line),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.padding(horizontal = SettingsRowContentInset, vertical = 3.dp)
                    )
                }
            }
        }
        item {
            Text(
                text = stringResource(
                    if (couldNotPrepare) R.string.format_check_prepare_failed else R.string.format_check_note
                ),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary
            )
        }
    }
}

@Composable
private fun verdictColor(verdict: FormatVerdict?) = when {
    verdict == null || verdict == FormatVerdict.NOT_TESTED -> NuvioTheme.colors.TextSecondary
    FormatCheckVerdicts.isFine(verdict) -> NuvioTheme.colors.Success
    verdict == FormatVerdict.FAILED -> NuvioTheme.colors.Error
    else -> NuvioTheme.colors.Warning
}

@Composable
private fun verdictLabel(result: ClipResult?): String = stringResource(
    when (result?.verdict) {
        FormatVerdict.PLAYED -> R.string.format_check_played
        FormatVerdict.PLAYED_SLOW -> R.string.format_check_played_slow
        FormatVerdict.PLAYED_MPV -> R.string.format_check_played_mpv
        FormatVerdict.CONFIRMED -> R.string.format_check_confirmed
        FormatVerdict.NOT_AS_EXPECTED -> R.string.format_check_not_as_expected
        FormatVerdict.FAILED -> R.string.format_check_failed
        FormatVerdict.NOT_TESTED, null -> R.string.format_check_not_tested
    }
)

private fun isSoftwareDecoder(name: String) = name.startsWith("c2.android.") || name.startsWith("c2.google.") ||
    name.startsWith("OMX.google.") || name.contains("ffmpeg", ignoreCase = true) || name.contains("dav1d", ignoreCase = true)

@Composable
private fun routeLabel(clip: FormatClip, result: ClipResult): String? {
    if (!clip.dolbyVision) return null
    if (result.verdict == FormatVerdict.FAILED || result.verdict == FormatVerdict.NOT_TESTED) return null
    val decoder = result.decoder.orEmpty()
    return when {
        result.dolbyVisionRoute == DolbyVisionRoute.NATIVE_FEL -> stringResource(R.string.format_check_route_native_fel)
        result.dolbyVisionRoute == DolbyVisionRoute.CONVERTED -> stringResource(R.string.format_check_route_converted)
        result.dolbyVisionRoute == DolbyVisionRoute.STRIPPED -> stringResource(R.string.format_check_route_stripped)
        decoder.contains("dolby-vision") -> stringResource(R.string.format_check_route_dv_decoder)
        decoder.isNotEmpty() -> stringResource(R.string.format_check_route_no_dv)
        else -> null
    }
}

@Composable
private fun settingNoteLabel(note: FormatSettingNote): String = stringResource(
    when (note) {
        FormatSettingNote.TUNNELING -> R.string.format_check_note_tunneling
        FormatSettingNote.MAIN_PLAYER_USED -> R.string.format_check_note_main_player
        FormatSettingNote.AUDIO_OFF -> R.string.format_check_note_audio_off
        FormatSettingNote.NO_PASSTHROUGH -> R.string.format_check_note_no_passthrough
        FormatSettingNote.FORCE_AC3 -> R.string.format_check_note_force_ac3
        FormatSettingNote.DV_MODE -> R.string.format_check_note_dv_mode
        FormatSettingNote.DV5_CONVERT -> R.string.format_check_note_dv5
        FormatSettingNote.HDR10_PLUS_STRIPPED -> R.string.format_check_note_hdr10_plus
        FormatSettingNote.CLOSED_APP_LAST_RUN -> R.string.format_check_note_closed_app
    }
)

@Composable
private fun rowDetail(clip: FormatClip, result: ClipResult?): String = buildList {
    if (result?.settingNotes?.contains(FormatSettingNote.CLOSED_APP_LAST_RUN) != true) add(verdictReason(clip, result))
    result?.let { r ->
        val infoOnly = clip.question?.let(FormatCheckVerdicts::answerIsInfoOnly) == true
        if (infoOnly && r.answer != null && r.verdict != FormatVerdict.CONFIRMED && r.verdict != FormatVerdict.NOT_AS_EXPECTED) {
            add(stringResource(R.string.format_check_why_showed, answerLabel(r.answer)))
        }
        routeLabel(clip, r)?.let { add(it) }
        r.settingNotes.forEach { add(settingNoteLabel(it)) }
    }
}.joinToString(" ")

@Composable
private fun verdictReason(clip: FormatClip, result: ClipResult?): String {
    val decoder = result?.decoder
    return when {
        result == null || result.verdict == FormatVerdict.NOT_TESTED -> stringResource(R.string.format_check_why_not_tested)
        result.verdict == FormatVerdict.FAILED -> stringResource(R.string.format_check_why_failed)
        result.verdict == FormatVerdict.PLAYED_MPV -> stringResource(R.string.format_check_why_mpv)
        result.verdict == FormatVerdict.PLAYED_SLOW ->
            stringResource(R.string.format_check_why_slow, result.droppedFrames)
        result.verdict == FormatVerdict.NOT_AS_EXPECTED && result.answer != null ->
            stringResource(R.string.format_check_why_showed, answerLabel(result.answer))
        result.verdict == FormatVerdict.CONFIRMED -> stringResource(R.string.format_check_why_confirmed)
        clip.group == FormatGroup.AUDIO && result.passthrough == true -> stringResource(R.string.format_check_why_sent)
        clip.group == FormatGroup.AUDIO && result.passthrough == false -> stringResource(R.string.format_check_why_decoded)
        decoder != null && isSoftwareDecoder(decoder) -> stringResource(R.string.format_check_why_software)
        else -> stringResource(R.string.format_check_why_hardware)
    }
}

@Composable
private fun ResultsPage(saved: SavedFormatCheck, onSuggestions: () -> Unit, onRunAgain: () -> Unit) {
    val byId = remember(saved) { saved.results.associateBy { it.clipId } }
    val verdicts = saved.results.map { it.verdict }
    val fine = verdicts.count(FormatCheckVerdicts::isFine)
    val catches = verdicts.count(FormatCheckVerdicts::isCatch)
    val failed = verdicts.count { it == FormatVerdict.FAILED }
    val suggestions = remember(saved) { FormatCheckSuggestions.from(saved.results) }
    val retest = remember(saved) { FormatCheckRetest.clipsToRetest(saved.results) }
    val retestMode = if (saved.withTvAnswers) FormatCheckMode.FULL else FormatCheckMode.QUICK
    val tested = remember(saved.atMs) {
        java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(saved.atMs))
    }
    PageColumn(focusFirstRow = false) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(Modifier.weight(1f)) {
                    PageTitle(
                        stringResource(R.string.format_check_results_title),
                        stringResource(R.string.format_check_tested_on, tested)
                    )
                }
                CountChip(R.string.format_check_chip_fine, fine, NuvioTheme.colors.Success)
                CountChip(R.string.format_check_chip_catch, catches, NuvioTheme.colors.Warning)
                CountChip(R.string.format_check_chip_failed, failed, NuvioTheme.colors.Error)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (suggestions.isNotEmpty()) {
                    SettingsActionRow(
                        title = stringResource(R.string.format_check_see_suggestions),
                        subtitle = null,
                        modifier = Modifier.weight(1f).takesPageFocus(),
                        onClick = onSuggestions
                    )
                }
                if (retest.isNotEmpty()) {
                    SettingsActionRow(
                        title = stringResource(R.string.format_check_test_again_count, retest.size),
                        subtitle = null,
                        trailingIcon = null,
                        modifier = Modifier.weight(1f).then(
                            if (suggestions.isEmpty()) Modifier.takesPageFocus() else Modifier
                        ),
                        onClick = { FormatCheckSession.requestStart(retestMode, retest) }
                    )
                }
                SettingsActionRow(
                    title = stringResource(R.string.format_check_run_again),
                    subtitle = null,
                    modifier = Modifier.weight(1f).then(
                        if (suggestions.isEmpty() && retest.isEmpty()) Modifier.takesPageFocus() else Modifier
                    ),
                    onClick = onRunAgain
                )
            }
        }
        FormatGroup.entries.forEach { group ->
            item(key = group.name) {
                SettingsGroupCard(
                    title = stringResource(
                        when (group) {
                            FormatGroup.VIDEO -> R.string.format_check_group_video
                            FormatGroup.HDR -> R.string.format_check_group_hdr
                            FormatGroup.AUDIO -> R.string.format_check_group_audio
                        }
                    )
                ) {
                    FormatCheckCatalog.clips.filter { it.group == group }.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { clip ->
                                val result = byId[clip.id]
                                ResultTile(
                                    clip = clip,
                                    result = result,
                                    canTestAgain = clip.id in retest,
                                    modifier = Modifier.weight(1f),
                                    onTestAgain = { FormatCheckSession.requestStart(retestMode, setOf(clip.id)) }
                                )
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun countText(@androidx.annotation.StringRes res: Int, count: Int, color: Color): AnnotatedString {
    val text = stringResource(res, count)
    val number = count.toString()
    val start = text.indexOf(number)
    return buildAnnotatedString {
        append(text)
        if (start >= 0) addStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold), start, start + number.length)
    }
}

@Composable
private fun CountChip(@androidx.annotation.StringRes res: Int, count: Int, color: Color) {
    Text(
        text = countText(res, count, color),
        style = MaterialTheme.typography.bodyMedium,
        color = NuvioTheme.colors.TextPrimary,
        modifier = Modifier
            .clip(RoundedCornerShape(NuvioTheme.radii.sm))
            .background(NuvioTheme.colors.BackgroundCard)
            .border(1.dp, NuvioTheme.colors.Border, RoundedCornerShape(NuvioTheme.radii.sm))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

@Composable
private fun ResultTile(
    clip: FormatClip,
    result: ClipResult?,
    canTestAgain: Boolean,
    modifier: Modifier,
    onTestAgain: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val color = verdictColor(result?.verdict)
    Card(
        onClick = { if (canTestAgain) onTestAgain() },
        modifier = modifier
            .padding(vertical = NuvioTheme.spacing.xxs)
            .settingsItemFocus(focused)
            .onFocusChanged { focused = it.isFocused },
        colors = settingsRowColors(),
        border = settingsRowBorder(1f, settingsRowShape()),
        shape = CardDefaults.shape(settingsRowShape()),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                Text(
                    text = clip.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    text = verdictLabel(result),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = color,
                    maxLines = 1
                )
                Spacer(Modifier.weight(1f))
                if (canTestAgain && focused) {
                    Text(
                        text = stringResource(R.string.format_check_test_again_hint),
                        style = MaterialTheme.typography.labelMedium,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(NuvioTheme.radii.sm))
                            .background(NuvioTheme.colors.FocusBackground)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            Text(
                text = rowDetail(clip, result),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 20.dp)
            )
        }
    }
}

@Composable
private fun suggestionName(suggestion: FormatSuggestion): String = when (suggestion) {
    is FormatSuggestion.AudioDecode -> stringResource(
        R.string.format_check_suggest_audio,
        when (suggestion.route) {
            AudioRoute.AC3 -> "AC-3"
            AudioRoute.EAC3 -> "E-AC3"
            AudioRoute.TRUEHD -> "TrueHD"
            AudioRoute.DTS -> "DTS"
            AudioRoute.DTS_HD -> "DTS-HD"
        }
    )
    is FormatSuggestion.Streams -> stringResource(
        R.string.format_check_suggest_streams,
        when (suggestion.family) {
            FormatFamily.DOLBY_VISION -> "Dolby Vision"
            FormatFamily.AV1 -> "AV1"
            FormatFamily.VP9 -> "VP9"
            FormatFamily.HEVC -> "HEVC"
            FormatFamily.HI10P -> "H.264 10-bit"
            FormatFamily.VC1 -> "VC-1"
            FormatFamily.MPEG2 -> "MPEG-2"
            FormatFamily.XVID -> "XviD"
            FormatFamily.DIVX -> "DivX"
            FormatFamily.AVI -> "AVI"
            FormatFamily.OTHER -> ""
        }
    )
}

private fun suggestionKey(suggestion: FormatSuggestion) = when (suggestion) {
    is FormatSuggestion.AudioDecode -> "audio:${suggestion.route.name}"
    is FormatSuggestion.Streams -> "streams:${suggestion.family.name}"
}

@Composable
private fun SuggestionsPage(saved: SavedFormatCheck, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val suggestions = remember(saved) { FormatCheckSuggestions.from(saved.results) }
    val choices = remember(saved) {
        mutableStateMapOf<String, SuggestionChoice>().apply {
            suggestions.forEach { s ->
                put(suggestionKey(s), if (s is FormatSuggestion.Streams) SuggestionChoice.WARN else SuggestionChoice.APPLY)
            }
        }
    }
    val rules by remember { FormatCheckStore.rules(context) }.collectAsState(initial = emptyMap())
    val appliedAudio by remember { FormatCheckStore.appliedAudio(context) }.collectAsState(initial = emptyList())
    val canUndo = rules.isNotEmpty() || appliedAudio.isNotEmpty()

    PageColumn {
        item {
            PageTitle(stringResource(R.string.format_check_suggest_title), stringResource(R.string.format_check_suggest_intro))
        }
        item {
            SettingsGroupCard {
                suggestions.forEach { suggestion ->
                    val key = suggestionKey(suggestion)
                    val choice = choices[key] ?: SuggestionChoice.LEAVE
                    SettingsActionRow(
                        title = suggestionName(suggestion),
                        subtitle = stringResource(
                            if (suggestion is FormatSuggestion.AudioDecode) R.string.format_check_suggest_audio_why
                            else R.string.format_check_suggest_streams_why
                        ),
                        value = stringResource(
                            when (choice) {
                                SuggestionChoice.WARN -> R.string.format_check_choice_warn
                                SuggestionChoice.HIDE -> R.string.format_check_choice_hide
                                SuggestionChoice.APPLY -> R.string.format_check_choice_decode
                                SuggestionChoice.LEAVE -> R.string.format_check_choice_leave
                            }
                        ),
                        trailingIcon = null,
                        onClick = {
                            choices[key] = if (suggestion is FormatSuggestion.Streams) {
                                when (choice) {
                                    SuggestionChoice.WARN -> SuggestionChoice.HIDE
                                    SuggestionChoice.HIDE -> SuggestionChoice.LEAVE
                                    else -> SuggestionChoice.WARN
                                }
                            } else {
                                if (choice == SuggestionChoice.APPLY) SuggestionChoice.LEAVE else SuggestionChoice.APPLY
                            }
                        }
                    )
                }
            }
        }
        item {
            Text(
                text = stringResource(R.string.format_check_suggest_note),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary
            )
        }
        item {
            SettingsGroupCard {
                SettingsActionRow(
                    title = stringResource(R.string.format_check_apply),
                    subtitle = null,
                    onClick = {
                        scope.launch {
                            applySuggestions(context, saved, suggestions, choices.toMap(), appliedAudio)
                            onDone()
                        }
                    }
                )
                if (canUndo) {
                    SettingsActionRow(
                        title = stringResource(R.string.format_check_undo),
                        subtitle = null,
                        onClick = {
                            scope.launch {
                                undoSuggestions(context, appliedAudio)
                                onDone()
                            }
                        }
                    )
                }
                SettingsActionRow(
                    title = stringResource(R.string.format_check_not_now),
                    subtitle = null,
                    onClick = onDone
                )
            }
        }
    }
}

private suspend fun undoSuggestions(context: Context, appliedAudio: List<String>) {
    val store = assessmentDataStore(context)
    appliedAudio.forEach { store.clearAudioRejection(it) }
    FormatCheckStore.saveRules(context, emptyMap(), emptyList())
}

private suspend fun applySuggestions(
    context: Context,
    saved: SavedFormatCheck,
    suggestions: List<FormatSuggestion>,
    choices: Map<String, SuggestionChoice>,
    earlierAudio: List<String>
) {
    val store = assessmentDataStore(context)
    earlierAudio.forEach { store.clearAudioRejection(it) }
    val currentRoute = AudioOutputRouteDetector.detect(context)?.key
    val audio = mutableListOf<String>()
    val rules = mutableMapOf<FormatFamily, StreamAction>()
    suggestions.forEach { suggestion ->
        val choice = choices[suggestionKey(suggestion)]
        when (suggestion) {
            is FormatSuggestion.AudioDecode ->
                // Audio results belong to the connection they were tested on.
                if (choice == SuggestionChoice.APPLY && currentRoute != null && currentRoute == saved.audioRouteKey) {
                    store.recordAudioRejection(currentRoute, suggestion.route.name)
                    audio += "$currentRoute::${suggestion.route.name}"
                }
            is FormatSuggestion.Streams -> when (choice) {
                SuggestionChoice.WARN -> rules[suggestion.family] = StreamAction.WARN
                SuggestionChoice.HIDE -> rules[suggestion.family] = StreamAction.HIDE
                else -> Unit
            }
        }
    }
    FormatCheckStore.saveRules(context, rules, audio)
}

@Composable
private fun answerLabel(answer: String): String = stringResource(
    when (answer) {
        FormatCheckAnswers.DOLBY_VISION -> R.string.format_check_answer_dolby_vision
        FormatCheckAnswers.HDR10_PLUS -> R.string.format_check_answer_hdr10_plus
        FormatCheckAnswers.HDR10 -> R.string.format_check_answer_hdr10
        FormatCheckAnswers.HLG -> R.string.format_check_answer_hlg
        FormatCheckAnswers.NOTHING_SPECIAL -> R.string.format_check_answer_nothing
        FormatCheckAnswers.PICTURE_WRONG -> R.string.format_check_answer_picture_wrong
        FormatCheckAnswers.CANT_TELL -> R.string.format_check_answer_cant_tell
        FormatCheckAnswers.ATMOS -> R.string.format_check_answer_atmos
        FormatCheckAnswers.TRUEHD -> R.string.format_check_answer_truehd
        FormatCheckAnswers.EAC3 -> R.string.format_check_answer_eac3
        FormatCheckAnswers.DTS_X -> R.string.format_check_answer_dtsx
        FormatCheckAnswers.DTS_HD -> R.string.format_check_answer_dtshd
        FormatCheckAnswers.DTS_OTHER -> R.string.format_check_answer_dts
        FormatCheckAnswers.PCM -> R.string.format_check_answer_pcm
        FormatCheckAnswers.SOMETHING_ELSE -> R.string.format_check_answer_something_else
        FormatCheckAnswers.NO_PLAY -> R.string.format_check_answer_no_play
        else -> R.string.format_check_answer_no_display
    }
)

/**
 * Sits above all screens so a run survives moving between the player and the settings. Each clip plays in the real
 * player screen: that way the result covers the same decoder choice, Dolby Vision handling, audio route and MPV
 * switch a film gets.
 */
@Composable
fun BoxScope.FormatCheckHost(navController: NavController) {
    val context = LocalContext.current
    val state by FormatCheckSession.state.collectAsState()
    LaunchedEffect(Unit) {
        launch { FormatCheckSession.openRequests.collect { navController.navigate(Screen.FormatCheck.route) } }
        FormatCheckSession.startRequests.collect { start -> runFormatCheck(context, navController, start) }
    }
    when (val current = state) {
        FormatCheckRunState.Preparing -> ProgressPill(stringResource(R.string.format_check_preparing))
        is FormatCheckRunState.Playing -> if (current.question != null) {
            QuestionPanel(current)
        } else {
            LiveResultsPanel(current)
        }
        else -> Unit
    }
}

@Composable
private fun BoxScope.ProgressPill(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = NuvioTheme.colors.TextPrimary,
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(24.dp)
            .background(NuvioTheme.colors.BackgroundElevated, RoundedCornerShape(NuvioTheme.radii.sm))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

private val PanelWidth = 360.dp
private val LiveRowHeight = 26.dp

@Composable
private fun BoxScope.RunPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val surface = if (LocalV2Appearance.current != null) {
        Modifier.nuvioGlass(GlassRole.PANEL, shape = RoundedCornerShape(NuvioTheme.radii.xxl), neutralBackdrop = true)
    } else {
        val shape = RoundedCornerShape(NuvioTheme.radii.xl)
        Modifier
            .clip(shape)
            .background(NuvioTheme.colors.BackgroundElevated)
            .border(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border, shape)
    }
    Column(
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .padding(NuvioTheme.spacing.xl)
            .width(PanelWidth)
            .then(surface)
            .then(modifier)
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup()
            .padding(NuvioTheme.spacing.md),
        content = content
    )
}

@Composable
private fun BoxScope.LiveResultsPanel(state: FormatCheckRunState.Playing) {
    val live by FormatCheckSession.liveResults.collectAsState()
    val clips by FormatCheckSession.runClips.collectAsState()
    RunPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.format_check_title),
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextPrimary
            )
            Text(
                text = stringResource(R.string.format_check_count, state.index + 1, state.total),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(NuvioTheme.colors.Border)
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(((state.index + 1).toFloat() / state.total.coerceAtLeast(1)).coerceIn(0f, 1f))
                    .clip(CircleShape)
                    .background(NuvioTheme.colors.FocusRing)
            )
        }
        Spacer(Modifier.height(8.dp))
        val verdicts = live.values
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(
                Triple(R.string.format_check_chip_fine, verdicts.count(FormatCheckVerdicts::isFine), NuvioTheme.colors.Success),
                Triple(R.string.format_check_chip_catch, verdicts.count(FormatCheckVerdicts::isCatch), NuvioTheme.colors.Warning),
                Triple(R.string.format_check_chip_failed, verdicts.count { it == FormatVerdict.FAILED }, NuvioTheme.colors.Error)
            ).forEach { (res, count, color) ->
                Text(
                    text = countText(res, count, color),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val rows = (maxHeight / LiveRowHeight).toInt().coerceAtLeast(1)
            val position = clips.indexOfFirst { it.id == state.clip.id }.coerceAtLeast(0)
            val first = (position - rows * 2 / 3).coerceIn(0, (clips.size - rows).coerceAtLeast(0))
            Column {
                clips.drop(first).take(rows).forEach { clip ->
                    val verdict = live[clip.id]
                    val testing = clip.id == state.clip.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(LiveRowHeight)
                            .then(
                                if (testing) Modifier.background(NuvioTheme.colors.FocusBackground, RoundedCornerShape(NuvioTheme.radii.sm))
                                else Modifier
                            )
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = clip.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = when {
                                testing -> stringResource(R.string.format_check_testing)
                                verdict != null -> verdictLabel(ClipResult(clip.id, verdict))
                                else -> stringResource(R.string.format_check_waiting)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (testing) NuvioTheme.colors.TextPrimary else verdictColor(verdict),
                            maxLines = 1
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.format_check_back_stop),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary
        )
    }
}

@Composable
private fun QuestionOption(
    title: String,
    hint: String? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .settingsItemFocus(focused)
            .onFocusChanged { focused = it.isFocused },
        colors = settingsRowColors(),
        border = settingsRowBorder(1f, settingsRowShape()),
        shape = CardDefaults.shape(settingsRowShape()),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun BoxScope.QuestionPanel(state: FormatCheckRunState.Playing) {
    val question = state.question ?: return
    val focus = remember(state.clip.id, state) { FocusRequester() }
    var panelHasFocus by remember(state.clip.id, state) { mutableStateOf(false) }
    LaunchedEffect(state.clip.id, state) {
        // The player takes focus as it starts, so check more than once.
        repeat(4) {
            delay(300)
            if (!panelHasFocus) runCatching { focus.requestFocus() }
        }
    }
    RunPanel(modifier = Modifier.onFocusChanged { panelHasFocus = it.hasFocus }) {
        Text(
            text = stringResource(R.string.format_check_count_clip, state.index + 1, state.total, state.clip.label),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(
                if (question.kind == QuestionKind.PICTURE) R.string.format_check_question_picture
                else R.string.format_check_question_sound
            ),
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextPrimary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(
                if (question.kind == QuestionKind.PICTURE) R.string.format_check_question_picture_hint
                else R.string.format_check_question_sound_hint
            ),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary
        )
        Spacer(Modifier.height(12.dp))
        question.options.forEachIndexed { index, option ->
            QuestionOption(
                title = answerLabel(option),
                modifier = if (index == 0) Modifier.focusRequester(focus) else Modifier,
                onClick = { FormatCheckSession.answer(option) }
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(NuvioTheme.colors.Border))
        Spacer(Modifier.height(6.dp))
        QuestionOption(
            title = stringResource(R.string.format_check_show_again),
            hint = stringResource(R.string.format_check_show_again_hint),
            onClick = { FormatCheckSession.answer(FormatCheckAnswers.SHOW_AGAIN) }
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = stringResource(R.string.format_check_back_stop),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary
        )
    }
}

private suspend fun currentCheckSettings(context: Context): FormatCheckSettings {
    val s = assessmentDataStore(context).playerSettings.first()
    val direct = com.nuvio.tv.ui.screens.player.AudioChainProbe.probeDirectSupport(context)
    return FormatCheckSettings(
        tunneling = s.tunnelingEnabled,
        mpvChosen = s.internalPlayerEngine == com.nuvio.tv.data.local.InternalPlayerEngine.MVP_PLAYER,
        forceAc3 = s.forceOpticalPassthrough,
        dolbyVisionNotAuto = s.dv7HandlingMode != com.nuvio.tv.data.local.Dv7HandlingMode.AUTO,
        dv5Convert = s.dv5ToDv81Enabled && s.dv7HandlingMode == com.nuvio.tv.data.local.Dv7HandlingMode.DV81_LIBDOVI,
        stripHdr10Plus = s.stripHdr10PlusSei,
        softwareDecoders = s.decoderPriority != 0 || s.forceOpticalPassthrough,
        manualAudioOff = if (s.surroundFormatMode == com.nuvio.tv.data.local.SurroundFormatMode.MANUAL) {
            buildSet {
                if (!s.allowAc3Passthrough) add(AudioRoute.AC3)
                if (!s.allowEac3Passthrough) add(AudioRoute.EAC3)
                if (!s.allowTruehdPassthrough) add(AudioRoute.TRUEHD)
                if (!s.allowDtsPassthrough) add(AudioRoute.DTS)
                if (!s.allowDtshdPassthrough) add(AudioRoute.DTS_HD)
            }
        } else {
            emptySet()
        },
        boxDenies = direct?.let {
            buildSet {
                if (!it.ac3) add(AudioRoute.AC3)
                if (!it.eac3) add(AudioRoute.EAC3)
                if (!it.trueHd) add(AudioRoute.TRUEHD)
                if (!it.dts) add(AudioRoute.DTS)
                if (!it.dtsHd) add(AudioRoute.DTS_HD)
            }
        }.orEmpty()
    )
}

private suspend fun runFormatCheck(context: Context, nav: NavController, start: FormatCheckStart) {
    val mode = start.mode
    val clips = FormatCheckCatalog.clips.filter { start.only == null || it.id in start.only }
    if (clips.isEmpty()) return
    FormatCheckSession.clearLiveResults()
    FormatCheckSession.setRunClips(clips)
    FormatCheckSession.setActive(true)
    FormatCheckSession.setState(FormatCheckRunState.Preparing)
    val folder = runCatching { FormatCheckPack.unpack(context) }.getOrNull()
    if (folder == null) {
        FormatCheckPack.delete(context)
        FormatCheckSession.setActive(false)
        FormatCheckSession.setState(FormatCheckRunState.Failed)
        return
    }
    val server = LocalClipServer(folder)
    server.start()
    val results = mutableListOf<ClipResult>()
    val settings = currentCheckSettings(context)
    val closedAppLastRun = runCatching { FormatCheckStore.runningClip(context) }.getOrNull()
    try {
        for ((index, clip) in clips.withIndex()) {
            if (clip.id == closedAppLastRun) {
                results += FormatCheckVerdicts.skippedAfterAppClosed(clip.id)
                FormatCheckSession.noteResult(clip.id, FormatVerdict.FAILED)
                continue
            }
            runCatching { FormatCheckStore.setRunningClip(context, clip.id) }
            var result = playClip(nav, server, mode, clip, index, clips.size, settings) ?: break
            // A format has to fail twice before it counts as failed.
            if (result.verdict == FormatVerdict.FAILED) {
                result = playClip(nav, server, mode, clip, index, clips.size, settings) ?: break
            }
            results += result.copy(settingNotes = FormatCheckSettingNotes.forClip(clip, settings))
            FormatCheckSession.noteResult(clip.id, result.verdict)
        }
    } finally {
        FormatCheckSession.setState(FormatCheckRunState.Idle)
        FormatCheckSession.setActive(false)
        server.stop()
        FormatCheckPack.delete(context)
        nav.popBackStack(Screen.FormatCheck.route, inclusive = false)
        withContext(NonCancellable) { runCatching { FormatCheckStore.setRunningClip(context, null) } }
    }
    val earlier = FormatCheckStore.results(context).first()
    if (start.only != null && earlier != null) {
        if (results.isEmpty()) return
        FormatCheckStore.saveResults(
            context,
            earlier.copy(atMs = System.currentTimeMillis(), results = FormatCheckRetest.merge(earlier.results, results))
        )
        return
    }
    if (results.size < clips.size && earlier != null) return
    val tested = results.map { it.clipId }.toSet()
    val all = results + FormatCheckCatalog.clips.filter { it.id !in tested }.map { ClipResult(it.id, FormatVerdict.NOT_TESTED) }
    FormatCheckStore.saveResults(
        context,
        SavedFormatCheck(
            atMs = System.currentTimeMillis(),
            withTvAnswers = mode == FormatCheckMode.FULL,
            audioRouteKey = AudioOutputRouteDetector.detect(context)?.key,
            results = all
        )
    )
}

/** Null means the person left the player, which stops the run. */
private suspend fun playClip(
    nav: NavController,
    server: LocalClipServer,
    mode: FormatCheckMode,
    clip: FormatClip,
    index: Int,
    total: Int,
    settings: FormatCheckSettings
): ClipResult? {
    while (true) {
        FormatCheckSession.beginClip()
        FormatCheckSession.setState(FormatCheckRunState.Playing(index, total, clip, null))
        nav.navigate(
            Screen.Player.createRoute(
                streamUrl = server.url(clip.file),
                title = clip.label,
                filename = clip.file,
                startFromBeginning = true
            )
        )
        val route = nav.currentBackStackEntry?.destination?.route
        fun leftPlayer() = nav.currentBackStackEntry?.destination?.route != route

        var waited = 0L
        var extended = false
        while (waited < FIRST_PICTURE_TIMEOUT_MS) {
            if (leftPlayer()) return null
            val seen = FormatCheckSession.observation.value
            if (seen.firstFrame || seen.error != null) break
            if (seen.movedToMpv && !extended) {
                extended = true
                waited = 0L
            }
            delay(200)
            waited += 200
        }
        var answer: String? = null
        val question = clip.questionFor(settings).takeIf { mode == FormatCheckMode.FULL }
        val played = FormatCheckSession.observation.value.let { it.firstFrame && it.error == null }
        if (played && question != null) {
            val pending = FormatCheckSession.askQuestion()
            FormatCheckSession.setState(FormatCheckRunState.Playing(index, total, clip, question))
            while (!pending.isCompleted) {
                if (leftPlayer()) return null
                delay(200)
            }
            answer = pending.await()
            FormatCheckSession.setState(FormatCheckRunState.Playing(index, total, clip, null))
            if (answer == FormatCheckAnswers.SHOW_AGAIN) {
                nav.popBackStack(Screen.FormatCheck.route, inclusive = false)
                delay(BETWEEN_CLIPS_MS)
                continue
            }
        } else if (played) {
            var watched = 0L
            while (watched < WATCH_MS) {
                if (leftPlayer()) return null
                delay(200)
                watched += 200
            }
        }
        val seen = FormatCheckSession.observation.value
        var verdict = FormatCheckVerdicts.of(seen, audioOnly = clip.group == FormatGroup.AUDIO, dolbyVision = clip.dolbyVision)
        if (question != null) verdict = FormatCheckVerdicts.withAnswer(verdict, question, answer)
        nav.popBackStack(Screen.FormatCheck.route, inclusive = false)
        delay(BETWEEN_CLIPS_MS)
        return ClipResult(clip.id, verdict, seen.decoder, seen.droppedFrames, seen.passthrough, answer, seen.dolbyVisionRoute)
    }
}
