@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.v2.components.sidebarPageContent

import com.nuvio.tv.domain.model.AuthState

import com.nuvio.tv.ui.components.NuvioNoticePill
import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.activity.compose.BackHandler
import androidx.annotation.RawRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.core.build.IssueReports
import com.nuvio.tv.domain.model.ExperienceMode
import com.nuvio.tv.domain.model.SettingsUiStyle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

internal enum class IntegrationSettingsSection {
    Hub,
    Debrid,
    Tmdb,
    MdbList,
    AnimeSkip
}

/** A visit to the Quality rules page from Playback's Quality rules row, and the way back to it. */
private enum class QualityRulesTrip {
    None,
    Out,
    Back
}

internal enum class SettingsSectionDestination {
    Inline,
    External
}

internal data class SettingsSectionSpec(
    val category: SettingsCategory,
    val title: String,
    val icon: ImageVector? = null,
    @param:RawRes val rawIconRes: Int? = null,
    val destination: SettingsSectionDestination
)

private const val SETTINGS_DETAIL_FOCUS_DELAY_MS = 120L
private const val SETTINGS_JUMP_TIMEOUT_MS = 3_000L
private const val SETTINGS_JUMP_NOTICE_MS = 5_000L
private const val SETTINGS_JUMP_GLOW_MS = 1_600L
// The rail gets the same treatment as the options pane: bring the item into view, then keep
// asking for a short while as it settles.
private const val SETTINGS_RAIL_FOCUS_RETRY_WINDOW_MS = 200L

/**
 * Stands for "no attempt handed focus to the rail container". Attempts are numbered from one, so
 * this has to sit outside that range: at zero it matches the number the counter starts on, and the
 * first time the rail takes focus the mark reads as set and the restoration is skipped.
 */
private const val NO_RAIL_FALLBACK = -1L
private const val SETTINGS_DETAIL_ANIM_IN_DURATION_MS = 200
private const val SETTINGS_DETAIL_ANIM_OUT_DURATION_MS = 180
internal const val SETTINGS_PANE_SETTLE_MS = 160L

/**
 * How long the options next to the menu wait before showing [target]. Moving along the menu shows a
 * category's options once focus rests on it, so passing through does not build every page on the way.
 */
internal fun settingsPaneSettleMs(shown: SettingsCategory, target: SettingsCategory, movingAlongMenu: Boolean): Long =
    if (shown != target && movingAlongMenu) SETTINGS_PANE_SETTLE_MS else 0L

// The focus window has two parts: SETTINGS_DETAIL_FOCUS_DELAY_MS before the first request, then
// this long retrying once per frame, so a category has both added together before focus falls
// back to a directional move. This part is taken from the enter animation so the two cannot
// drift apart, and is timed rather than counted in frames since a frame count assumes a
// refresh rate. The worst case wait before the fallback is SETTINGS_DETAIL_FOCUS_DELAY_MS plus
// this window, not this window alone.
private const val SETTINGS_DETAIL_FOCUS_RETRY_WINDOW_MS = SETTINGS_DETAIL_ANIM_IN_DURATION_MS
private const val PANE_HANDOVER_FOCUS_WINDOW_MS = 2_000

private sealed interface ExperienceModeLoadState {
    data object Loading : ExperienceModeLoadState
    data class Loaded(val mode: ExperienceMode?) : ExperienceModeLoadState
}

@Composable
private fun settingsSectionSpec(category: SettingsCategory): SettingsSectionSpec = when (category) {
    SettingsCategory.SEARCH -> SettingsSectionSpec(category, stringResource(R.string.settings_search_rail), Icons.Default.Search, destination = SettingsSectionDestination.Inline)
    SettingsCategory.ACCOUNT -> SettingsSectionSpec(category, stringResource(R.string.settings_account), Icons.Default.Person, destination = SettingsSectionDestination.Inline)
    SettingsCategory.PROFILES -> SettingsSectionSpec(category, stringResource(R.string.settings_profiles), Icons.Default.People, destination = SettingsSectionDestination.Inline)
    SettingsCategory.APPEARANCE -> SettingsSectionSpec(category, stringResource(R.string.appearance_title), Icons.Default.Palette, destination = SettingsSectionDestination.Inline)
    SettingsCategory.LAYOUT -> SettingsSectionSpec(category, stringResource(R.string.settings_layout), Icons.Default.GridView, destination = SettingsSectionDestination.Inline)
    SettingsCategory.CONTENT_DISCOVERY -> SettingsSectionSpec(category, stringResource(R.string.settings_content_discovery), Icons.Default.Explore, destination = SettingsSectionDestination.Inline)
    SettingsCategory.PLAYBACK -> SettingsSectionSpec(category, stringResource(R.string.settings_playback), Icons.Rounded.PlayArrow, destination = SettingsSectionDestination.Inline)
    SettingsCategory.SCOUT -> SettingsSectionSpec(category, stringResource(R.string.scout_title), rawIconRes = R.raw.sidebar_scout, destination = SettingsSectionDestination.Inline)
    SettingsCategory.INTEGRATION -> SettingsSectionSpec(category, stringResource(R.string.settings_integration), Icons.Default.Link, destination = SettingsSectionDestination.Inline)
    SettingsCategory.TRACKING -> SettingsSectionSpec(category, stringResource(R.string.settings_tracking_title), Icons.Default.Sync, destination = SettingsSectionDestination.Inline)
    SettingsCategory.ADVANCED -> SettingsSectionSpec(category, stringResource(R.string.settings_advanced), Icons.Default.Build, destination = SettingsSectionDestination.Inline)
    SettingsCategory.BACKUP -> SettingsSectionSpec(category, stringResource(R.string.backup_title), Icons.Default.SettingsBackupRestore, destination = SettingsSectionDestination.Inline)
    SettingsCategory.REPORT -> SettingsSectionSpec(category, stringResource(R.string.report_panel_title), Icons.Default.BugReport, destination = SettingsSectionDestination.Inline)
    SettingsCategory.ABOUT -> SettingsSectionSpec(category, stringResource(R.string.about_title), Icons.Default.Info, destination = SettingsSectionDestination.Inline)
    SettingsCategory.DEBUG -> SettingsSectionSpec(category, stringResource(R.string.settings_debug), Icons.Default.BugReport, destination = SettingsSectionDestination.Inline)
    SettingsCategory.EXPERIENCE -> SettingsSectionSpec(category, stringResource(R.string.settings_experience), Icons.Default.Tune, destination = SettingsSectionDestination.Inline)
}

@Composable
fun SettingsScreen(
    showBuiltInHeader: Boolean = true,
    onNavigateToAddons: () -> Unit = {},
    onNavigateToPlugins: () -> Unit = {},
    onNavigateToScoutTaste: () -> Unit = {},
    onNavigateToScoutRoute: (String) -> Unit = {},
    onNavigateToWatchParty: () -> Unit = {},
    onNavigateToAuthQrSignIn: () -> Unit = {},
    onNavigateToManageProfiles: () -> Unit = {},
    onNavigateToSupportersContributors: () -> Unit = {},
    onNavigateToLicensesAttributions: () -> Unit = {},
    profileViewModel: ProfileSettingsViewModel = hiltViewModel(),
    experienceModeViewModel: ExperienceModeSettingsViewModel = hiltViewModel()
) {
    val isPrimaryProfileActive by profileViewModel.isPrimaryProfileActive.collectAsStateWithLifecycle()
    val experienceModeState by remember(experienceModeViewModel) {
        experienceModeViewModel.mode.map<ExperienceMode?, ExperienceModeLoadState> {
            ExperienceModeLoadState.Loaded(it)
        }
    }.collectAsStateWithLifecycle(initialValue = ExperienceModeLoadState.Loading)
    val loadedExperienceMode = (experienceModeState as? ExperienceModeLoadState.Loaded)?.mode
    val experienceModeLoaded = experienceModeState is ExperienceModeLoadState.Loaded

    if (!experienceModeLoaded) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(NuvioTheme.colors.Background)
        )
        return
    }

    val isEssentialMode = loadedExperienceMode == ExperienceMode.ESSENTIAL

    val visibleCategories = remember(isPrimaryProfileActive, isEssentialMode) {
        visibleSettingsCategories(
            isPrimaryProfile = isPrimaryProfileActive,
            isEssentialMode = isEssentialMode,
            isDebugBuild = BuildConfig.IS_DEBUG_BUILD,
            reportsAvailable = IssueReports.available
        )
    }
    val visibleSections = visibleCategories.map { category -> settingsSectionSpec(category) }

    val isRtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val isHorizonStyle = NuvioTheme.settingsUiStyle == SettingsUiStyle.HORIZON
    var selectedCategory by rememberSaveable {
        mutableStateOf(
            visibleSections.firstOrNull { it.category != SettingsCategory.SEARCH }?.category ?: SettingsCategory.APPEARANCE
        )
    }
    val settingsJump = remember { SettingsJumpState() }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchReturnKey by remember { mutableStateOf<Int?>(null) }
    var returnToSearch by remember { mutableStateOf(false) }
    val searchListState = rememberLazyListState()
    val railFocusRequesters = remember(visibleSections) {
        visibleSections.associate { it.category to FocusRequester() }
    }
    val contentFocusRequesters = remember {
        mapOf(
            SettingsCategory.SEARCH to FocusRequester(),
            SettingsCategory.APPEARANCE to FocusRequester(),
            SettingsCategory.EXPERIENCE to FocusRequester(),
            SettingsCategory.PROFILES to FocusRequester(),
            SettingsCategory.LAYOUT to FocusRequester(),
            SettingsCategory.CONTENT_DISCOVERY to FocusRequester(),
            SettingsCategory.INTEGRATION to FocusRequester(),
            SettingsCategory.PLAYBACK to FocusRequester(),
            SettingsCategory.SCOUT to FocusRequester(),
            SettingsCategory.TRACKING to FocusRequester(),
            SettingsCategory.ADVANCED to FocusRequester(),
            SettingsCategory.BACKUP to FocusRequester(),
            SettingsCategory.REPORT to FocusRequester(),
            SettingsCategory.ABOUT to FocusRequester(),
            SettingsCategory.ACCOUNT to FocusRequester()
        )
    }
    val railContainerFocusRequester = remember { FocusRequester() }
    val integrationHubFocusRequester = remember { FocusRequester() }
    val integrationDebridFocusRequester = remember { FocusRequester() }
    val integrationTmdbFocusRequester = remember { FocusRequester() }
    val integrationMdbListFocusRequester = remember { FocusRequester() }
    val integrationAnimeSkipFocusRequester = remember { FocusRequester() }
    var integrationSection by remember { mutableStateOf(IntegrationSettingsSection.Hub) }
    var qualityRulesTrip by remember { mutableStateOf(QualityRulesTrip.None) }
    var integrationHubReturnSection by remember { mutableStateOf(IntegrationSettingsSection.Hub) }
    var pendingContentFocusCategory by remember { mutableStateOf<SettingsCategory?>(null) }
    var pendingContentFocusRequestId by remember { mutableLongStateOf(0L) }
    // A page opened from inside the pane replaces the row that had focus, and the rail then takes focus
    // on its own. Until the user presses a key, that is not a move to the rail.
    var paneHandover by remember { mutableStateOf(false) }
    var keyDownCount by remember { mutableIntStateOf(0) }
    var handoverKeyCount by remember { mutableIntStateOf(-1) }
    // Saveable so it survives a trip out to one of the screens a category opens. The pane bounces
    // focus back to the rail whenever it gains focus while this is false, so a plain remember made
    // every return from those screens land on the rail rather than where the user had been.
    var allowDetailAutofocus by rememberSaveable { mutableStateOf(false) }
    var detailHasFocus by remember { mutableStateOf(false) }
    // Keep the last rail item across sidebar/child-screen trips. Focus also selects its
    // inline pane, while explicit entry into that pane remains a separate action.
    var railFocusCategoryName by rememberSaveable { mutableStateOf<String?>(null) }
    val railFocusCategory = railFocusCategoryName
        ?.let { name -> SettingsCategory.entries.firstOrNull { it.name == name } }
        // The name surviving is not enough. Which categories the rail shows depends on the profile
        // and on essential mode, so the one that was left can be gone by the time it is returned
        // to. Restoring to it would spend the whole retry window on an item that is not there.
        ?.takeIf { category -> visibleSections.any { it.category == category } }
        ?: selectedCategory
    val railListState = rememberLazyListState()
    val railScope = rememberCoroutineScope()

    // The rail is a lazy list, so an item that has scrolled out of view has no attached requester
    // and cannot take focus. Bring it back into view first, then ask, retrying while it composes.
    // Without this, restoring to a scrolled out item silently failed and focus was left to the
    // geometric search, which is how coming back from the sidebar landed on the wrong item.
    var railHadFocus by remember { mutableStateOf(false) }
    var railFocusJob by remember { mutableStateOf<Job?>(null) }
    // The attempt whose fallback handed focus to the rail container, or 0. The container gaining
    // focus is what triggers a restoration, so without this the fallback asks for the same
    // unavailable item again instead of ending the attempt. Numbered rather than a flag so a
    // newer attempt cannot inherit an older one's mark.
    var railFallbackAttempt by remember { mutableLongStateOf(NO_RAIL_FALLBACK) }
    // The category an attempt is currently working towards, so that item can end the attempt when
    // it takes focus by any route. Only that item: cancelling on any other is how an earlier
    // version let the rail's own choice of child kill a restoration and drift away from the user.
    var railRestoringCategory by remember { mutableStateOf<SettingsCategory?>(null) }
    // Identifies an attempt. Cancellation is cooperative, so a stopped attempt can still run as
    // far as its next suspension point, and without this its cleanup would clear state belonging
    // to the attempt that replaced it.
    var railFocusAttempt by remember { mutableLongStateOf(0L) }
    val focusRailCategory: (SettingsCategory) -> Unit = { category ->
        railFocusJob?.cancel()
        railFocusJob = null
        // Cleared here, not only by the attempt that set it. A cancelled attempt is forbidden from
        // clearing it once the number has moved on, and an attempt that lands straight away never
        // sets one, so without this a target could outlive every attempt behind it.
        railRestoringCategory = null
        railFocusAttempt += 1L
        val attempt = railFocusAttempt
        // Try straight away first, without a coroutine: a suspension point before the request
        // leaves a gap for the rail to settle on a child of its own, and that child then records
        // itself as the item to come back to.
        //
        // The rail's own focusRestorer covers the ordinary case, where the item being returned to
        // is still composed and is the one the rail last had focus on. This is for what it cannot
        // do: a target that is off screen and so has no node to restore to, and a target that is
        // not the child the rail last held, such as the category a Back press names. Only those
        // reach the scroll and retry below.
        val landed = railFocusRequesters[category]
            ?.let { runCatching { it.requestFocus() }.getOrDefault(false) } ?: false
        if (!landed) {
            // Set before launching, so the attempt advertises its target from the start.
            railRestoringCategory = category
            railFocusJob = railScope.launch {
                try {
                    val index = visibleSections.indexOfFirst { it.category == category }
                    // Only when it is not on screen at all. An item that is even partly on screen
                    // can usually take focus as it is, and scrollToItem would drag it to the start
                    // of the viewport and move the whole rail under the user for nothing.
                    // Visibility is not the same as having an attached requester, which is what
                    // the retry below is for.
                    if (index >= 0 && !railListState.isItemVisible(index)) {
                        runCatching { railListState.scrollToItem(index) }
                    }
                    val deadline =
                        System.nanoTime() + SETTINGS_RAIL_FOCUS_RETRY_WINDOW_MS * 1_000_000L
                    var focused = false
                    while (!focused && attempt == railFocusAttempt && System.nanoTime() < deadline) {
                        focused = railFocusRequesters[category]
                            ?.let { runCatching { it.requestFocus() }.getOrDefault(false) } ?: false
                        if (!focused) withFrameNanos { }
                    }
                    // Only when the rail does not already hold focus. If it does, focus is
                    // somewhere sensible in it already, and asking the container again would
                    // neither move anything nor produce the transition that clears the mark.
                    if (!focused && !railHadFocus && attempt == railFocusAttempt) {
                        val handedOver = runCatching {
                            railContainerFocusRequester.requestFocus()
                        }.getOrDefault(false)
                        if (handedOver) railFallbackAttempt = attempt
                    }
                } finally {
                    // Also on cancellation, so a stopped attempt does not leave its target or its
                    // job behind, and only if this is still the current attempt.
                    if (attempt == railFocusAttempt) {
                        railRestoringCategory = null
                        railFocusJob = null
                    }
                }
            }
        }
    }

    // Back inside the options pane returns to the category rail before it leaves settings, the
    // way back inside a row returns to the start of that row. Anything the pane declares itself,
    // such as the integrations sub sections, composes later and so is asked first.
    BackHandler(enabled = detailHasFocus) {
        // Cleared up front rather than after the request. The rail reports its own focus a frame
        // later, and until it does a second quick press would be consumed here again instead of
        // leaving settings. The result of the request is deliberately not read: either way this
        // press was the step back, and with the flag already cleared the next one carries on out.
        detailHasFocus = false
        focusRailCategory(railFocusCategory)
    }

    val focusManager = LocalFocusManager.current

    LaunchedEffect(visibleSections) {
        if (visibleSections.none { it.category == selectedCategory }) {
            selectedCategory = visibleSections.firstOrNull { it.category != SettingsCategory.SEARCH }?.category
                ?: SettingsCategory.APPEARANCE
        }
    }

    LaunchedEffect(Unit) {
        // Categories such as plugins and addons open a destination of their own, so Settings leaves
        // composition and comes back with nothing asking for the options pane: the rail is simply
        // the first thing able to take focus. Landing there loses the user's place for a trip they
        // did not take through the rail. The saved flag says the pane was where they were, so aim
        // for it and leave the rail to the case where it really was the last thing focused.
        if (allowDetailAutofocus) {
            pendingContentFocusCategory = selectedCategory
            pendingContentFocusRequestId += 1L
        } else {
            runCatching { railContainerFocusRequester.requestFocus() }
        }
    }

    LaunchedEffect(pendingContentFocusRequestId) {
        // A hand-over that a menu move or a key press ended leaves nothing behind for the next request.
        if (pendingContentFocusCategory == null || keyDownCount != handoverKeyCount) paneHandover = false
        val category = pendingContentFocusCategory ?: return@LaunchedEffect
        // A search result: the row takes focus itself once its page has built it. Only a row that never
        // came falls through to the page's usual first row.
        if (settingsJump.target?.entry?.category == category) {
            snapshotFlow { settingsJump.target }.first { it == null }
            if (settingsJump.landedTitle != null) {
                pendingContentFocusCategory = null
                paneHandover = false
                return@LaunchedEffect
            }
        }
        delay(SETTINGS_DETAIL_FOCUS_DELAY_MS)
        // Asked once per frame until the pane takes focus or the window closes, rather than
        // betting on one fixed delay. The pane is not always laid out when the delay expires,
        // and the fallback below moves by direction, which lands wherever is nearest the rail
        // row the user came from, usually the middle of the options.
        var requested = false
        // A page opened from inside the pane is built on arrival, which a busy box does not finish
        // inside the usual window; moving by direction from nothing then lands on the rail.
        val window = if (paneHandover) PANE_HANDOVER_FOCUS_WINDOW_MS else SETTINGS_DETAIL_FOCUS_RETRY_WINDOW_MS
        val deadline = System.nanoTime() + window * 1_000_000L
        while (!requested && System.nanoTime() < deadline) {
            val requester = if (category == SettingsCategory.INTEGRATION) {
                when (integrationSection) {
                    IntegrationSettingsSection.Hub -> when (integrationHubReturnSection) {
                        IntegrationSettingsSection.Hub -> contentFocusRequesters[category]
                        IntegrationSettingsSection.Debrid -> integrationDebridFocusRequester
                        IntegrationSettingsSection.Tmdb -> integrationTmdbFocusRequester
                        IntegrationSettingsSection.MdbList -> integrationMdbListFocusRequester
                        IntegrationSettingsSection.AnimeSkip -> integrationAnimeSkipFocusRequester
                    }
                    IntegrationSettingsSection.Debrid -> integrationDebridFocusRequester
                    IntegrationSettingsSection.Tmdb -> integrationTmdbFocusRequester
                    IntegrationSettingsSection.MdbList -> integrationMdbListFocusRequester
                    IntegrationSettingsSection.AnimeSkip -> integrationAnimeSkipFocusRequester
                }
            } else {
                contentFocusRequesters[category]
            }
            requested = requester
                ?.let { runCatching { it.requestFocus() }.getOrDefault(false) } ?: false
            if (!requested) withFrameNanos { }
        }
        if (!requested) {
            focusManager.moveFocus(if (isHorizonStyle) FocusDirection.Down else FocusDirection.Right)
        }
        pendingContentFocusCategory = null
        paneHandover = false
    }

    val handFocusToPane: (SettingsCategory) -> Unit = { category ->
        allowDetailAutofocus = true
        paneHandover = true
        handoverKeyCount = keyDownCount
        pendingContentFocusCategory = category
        pendingContentFocusRequestId += 1L
    }

    // Quality rules opens as a page of Playback, so the menu stays on Playback. The row that opens it goes
    // away with the Playback options, so focus is carried over the same way a rail click does.
    val moveQualityRulesTrip: (QualityRulesTrip) -> Unit = { trip ->
        qualityRulesTrip = trip
        railFocusCategoryName = SettingsCategory.PLAYBACK.name
        selectedCategory = SettingsCategory.PLAYBACK
        handFocusToPane(SettingsCategory.PLAYBACK)
    }
    val openQualityRules: () -> Unit = { moveQualityRulesTrip(QualityRulesTrip.Out) }
    val closeQualityRules: () -> Unit = { moveQualityRulesTrip(QualityRulesTrip.Back) }

    val onIntegrationSectionBack: () -> Unit = {
        integrationSection = IntegrationSettingsSection.Hub
        handFocusToPane(SettingsCategory.INTEGRATION)
    }
    val onSelectIntegrationSection: (IntegrationSettingsSection) -> Unit = { section ->
        if (section != IntegrationSettingsSection.Hub) integrationHubReturnSection = section
        integrationSection = section
        handFocusToPane(SettingsCategory.INTEGRATION)
    }
    // Into the Quality rules page from the menu, focus goes to its first row rather than the row
    // level with the menu entry.
    val enterQualityRulesFromRail: () -> Boolean = {
        if (qualityRulesTrip == QualityRulesTrip.Out) {
            handFocusToPane(SettingsCategory.PLAYBACK)
            true
        } else {
            false
        }
    }
    val openSearchResult: (SettingsSearchEntry, String, Int) -> Unit = { entry, title, key ->
        searchReturnKey = key
        returnToSearch = true
        qualityRulesTrip = if (entry.qualityRules) QualityRulesTrip.Out else QualityRulesTrip.None
        if (entry.category == SettingsCategory.INTEGRATION) {
            integrationSection = entry.integration ?: IntegrationSettingsSection.Hub
            integrationHubReturnSection = entry.integration ?: IntegrationSettingsSection.Hub
        }
        railFocusCategoryName = entry.category.name
        selectedCategory = entry.category
        settingsJump.start(entry, title)
        handFocusToPane(entry.category)
    }
    BackHandler(enabled = returnToSearch && detailHasFocus) {
        returnToSearch = false
        qualityRulesTrip = QualityRulesTrip.None
        integrationSection = IntegrationSettingsSection.Hub
        integrationHubReturnSection = IntegrationSettingsSection.Hub
        railFocusCategoryName = SettingsCategory.SEARCH.name
        selectedCategory = SettingsCategory.SEARCH
        handFocusToPane(SettingsCategory.SEARCH)
    }
    var jumpUnavailableVisible by remember { mutableStateOf(false) }
    LaunchedEffect(settingsJump.target?.id) {
        val id = settingsJump.target?.id ?: return@LaunchedEffect
        delay(SETTINGS_JUMP_TIMEOUT_MS)
        if (settingsJump.target?.id == id) {
            settingsJump.giveUp()
            jumpUnavailableVisible = true
        }
    }
    LaunchedEffect(jumpUnavailableVisible) {
        if (jumpUnavailableVisible) {
            delay(SETTINGS_JUMP_NOTICE_MS)
            jumpUnavailableVisible = false
        }
    }
    LaunchedEffect(settingsJump.landedTitle) {
        if (settingsJump.landedTitle == null) return@LaunchedEffect
        delay(SETTINGS_JUMP_GLOW_MS)
        settingsJump.clearLanded()
    }
    val searchPane: @Composable (FocusRequester?) -> Unit = { focusRequester ->
        SettingsSearchContent(
            query = searchQuery,
            onQueryChange = {
                searchQuery = it
                searchReturnKey = null
            },
            visibleCategories = visibleCategories,
            isEssentialMode = isEssentialMode,
            listState = searchListState,
            returnKey = searchReturnKey,
            initialFocusRequester = focusRequester,
            onOpen = openSearchResult
        )
    }
    var paneCategory by remember { mutableStateOf(selectedCategory) }
    LaunchedEffect(selectedCategory, allowDetailAutofocus, pendingContentFocusRequestId) {
        val wait = settingsPaneSettleMs(
            shown = paneCategory,
            target = selectedCategory,
            movingAlongMenu = !isHorizonStyle && railHadFocus && !allowDetailAutofocus &&
                pendingContentFocusCategory == null
        )
        if (wait > 0) delay(wait)
        paneCategory = selectedCategory
    }
    // Into the options before they have caught up with the menu: show them now and focus their first row,
    // rather than moving into the options of the category passed on the way.
    val enterPendingPane: () -> Boolean = {
        if (paneCategory != selectedCategory) {
            paneCategory = selectedCategory
            handFocusToPane(selectedCategory)
            true
        } else {
            false
        }
    }

    CompositionLocalProvider(LocalSettingsJump provides settingsJump) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) keyDownCount += 1
                false
            }
    ) {
        com.nuvio.tv.ui.v2.appearance.LocalV2Appearance.current?.let { appearance ->
            com.nuvio.tv.ui.v2.appearance.V2Atmosphere(
                rich = appearance.settingsPresentation == com.nuvio.tv.domain.model.SettingsPresentation.GLASS,
                background = appearance.settingsBackground)
        }
        SettingsWorkspaceSurface(
            modifier = Modifier
                .sidebarPageContent()
                .fillMaxSize()
            .padding(
                start = if (isV2Settings()) 16.dp else NuvioTheme.spacing.xxl,
                end = NuvioTheme.spacing.xxl,
                top = when {
                    com.nuvio.tv.ui.v2.navigation.LocalV2TopChrome.current -> 16.dp
                    showBuiltInHeader -> NuvioTheme.spacing.xl
                    else -> 68.dp
                },
                bottom = NuvioTheme.spacing.xl
            )
        ) {

            val onSectionFocused: (SettingsSectionSpec) -> Unit = { section ->
                val restoringTo = railRestoringCategory
                // Ignore temporary lazy-list focus while restoring an off-screen category.
                val handingOver = paneHandover && pendingContentFocusCategory != null &&
                    keyDownCount == handoverKeyCount
                if (!handingOver && (restoringTo == null || restoringTo == section.category)) {
                    railRestoringCategory = null
                    returnToSearch = false
                    settingsJump.giveUp()
                    // Sitting on Playback in the menu keeps the Quality rules page, as a sub-page of
                    // Integrations stays while its row is focused; Right goes back into it.
                    if (section.category != SettingsCategory.PLAYBACK) qualityRulesTrip = QualityRulesTrip.None
                    railFocusCategoryName = section.category.name
                    allowDetailAutofocus = false
                    // A previous click must not pull focus into a pane after the user moves on.
                    if (pendingContentFocusCategory != null) {
                        pendingContentFocusCategory = null
                        pendingContentFocusRequestId += 1L
                    }
                    if (section.destination == SettingsSectionDestination.Inline &&
                        selectedCategory != section.category) {
                        if (section.category == SettingsCategory.INTEGRATION) {
                            integrationSection = IntegrationSettingsSection.Hub
                            integrationHubReturnSection = IntegrationSettingsSection.Hub
                        }
                        selectedCategory = section.category
                    }
                }
            }

            val onSectionClick: (SettingsSectionSpec) -> Unit = { section ->
                qualityRulesTrip = QualityRulesTrip.None
                if (section.destination == SettingsSectionDestination.External) {
                    // These have no options of their own to come back to: the rail row is the
                    // whole category, and it is the rail the user is leaving from. Without this
                    // the flag keeps whatever an earlier visit to the pane left in it, and the
                    // return aims at the options of a category that was never opened.
                    allowDetailAutofocus = false
                    when (section.category) {
                        SettingsCategory.ACCOUNT -> onNavigateToAuthQrSignIn()
                        else -> Unit
                    }
                } else {
                    if (section.category == SettingsCategory.INTEGRATION) {
                        integrationSection = IntegrationSettingsSection.Hub
                        integrationHubReturnSection = IntegrationSettingsSection.Hub
                    }
                    allowDetailAutofocus = true
                    selectedCategory = section.category
                    pendingContentFocusCategory = section.category
                    pendingContentFocusRequestId += 1L
                }
            }

            if (isHorizonStyle) {
                var topBarCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
                var focusedTabBounds by remember { mutableStateOf<Rect?>(null) }
                val density = LocalDensity.current

                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { topBarCoordinates = it }
                    ) {
                        focusedTabBounds?.let { bounds ->
                            SettingsHorizonFocusPill(
                                bounds = bounds,
                                focused = railHadFocus,
                                modifier = Modifier.align(AbsoluteAlignment.TopLeft)
                            )
                        }
                        LazyRow(
                            state = railListState,
                            modifier = Modifier
                                .focusRequester(railContainerFocusRequester)
                                // Lets the rail resolve its own focus enter to the item that was on it when
                                // it last lost focus. Without this the enter is resolved by stepping on from
                                // whatever holds focus, which lands an item further along and drags the rail
                                // with it. It agrees with focusRailCategory rather than competing with it:
                                // both name the item the rail was left on. It cannot restore an item that is
                                // no longer composed, which is what the retry there is for.
                                .focusRestorer()
                                .fillMaxWidth()
                                .onFocusChanged { state ->
                                    val justGainedFocus = !railHadFocus && state.hasFocus
                                    railHadFocus = state.hasFocus
                                    if (justGainedFocus) {
                                        if (railFallbackAttempt == railFocusAttempt) {
                                            railFallbackAttempt = NO_RAIL_FALLBACK
                                        } else {
                                            focusRailCategory(railFocusCategory)
                                        }
                                    }
                                }
                                .onPreviewKeyEvent { event ->
                                    // The user moving is the one signal that is unambiguously
                                    // theirs, unlike a focus change, which the rail also produces
                                    // on its own. Stop a restoration still retrying.
                                    if (event.type == KeyEventType.KeyDown && event.key.isDirection()) {
                                        // Retired by number as well as cancelled. The loops check the
                                        // attempt number rather than isActive, so bumping it is what
                                        // actually stops them; the cancel is just the quicker of the
                                        // two.
                                        railFocusAttempt += 1L
                                        railFocusJob?.cancel()
                                        railFocusJob = null
                                        railRestoringCategory = null
                                    }
                                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                                        allowDetailAutofocus = true
                                        enterQualityRulesFromRail()
                                    } else {
                                        false
                                    }
                                },
                            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm, Alignment.CenterHorizontally),
                            contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.xs)
                        ) {
                            itemsIndexed(
                                items = visibleSections,
                                key = { _, section -> section.category }
                            ) { index, section ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (visibleCategories.startsNewGroup(index)) {
                                        SettingsTopBarDivider()
                                    }
                                    SettingsTopBarTab(
                                        title = section.title,
                                        icon = section.icon,
                                        rawIconRes = section.rawIconRes,
                                        isSelected = selectedCategory == section.category,
                                        focusRequester = railFocusRequesters[section.category],
                                        onClick = { onSectionClick(section) },
                                        onFocused = { onSectionFocused(section) },
                                        onFocusedTabPositioned = { tabCoordinates ->
                                            topBarCoordinates?.let { container ->
                                                focusedTabBounds = container.localBoundingBoxOf(tabCoordinates, clipBounds = false)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                        SettingsHorizontalScrollIndicators(state = railListState)
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .onFocusChanged { state ->
                                detailHasFocus = state.hasFocus
                                if (state.hasFocus && !allowDetailAutofocus) {
                                    focusRailCategory(railFocusCategory)
                                }
                            }
                    ) {
                        AnimatedContent(
                            targetState = selectedCategory,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxHeight()
                                .widthIn(max = 880.dp)
                                .fillMaxWidth(),
                            transitionSpec = {
                                val order = visibleSections.map { it.category }
                                val forward = order.indexOf(targetState) >= order.indexOf(initialState)
                                val toStart = forward != isRtl
                                (slideInHorizontally(
                                    animationSpec = tween(SETTINGS_DETAIL_ANIM_IN_DURATION_MS, easing = FastOutSlowInEasing)
                                ) { fullWidth -> if (toStart) fullWidth / 4 else -fullWidth / 4 } +
                                    fadeIn(tween(SETTINGS_DETAIL_ANIM_IN_DURATION_MS)))
                                    .togetherWith(
                                        slideOutHorizontally(
                                            animationSpec = tween(SETTINGS_DETAIL_ANIM_OUT_DURATION_MS, easing = FastOutSlowInEasing)
                                        ) { fullWidth -> if (toStart) -fullWidth / 4 else fullWidth / 4 } +
                                            fadeOut(tween(SETTINGS_DETAIL_ANIM_OUT_DURATION_MS))
                                    )
                            },
                            label = "settingsDetailTransition"
                        ) { animatedCategory ->
                            SettingsDetailPane(
                                selectedCategory = animatedCategory,
                                isCurrent = animatedCategory == selectedCategory,
                                isEssentialMode = isEssentialMode,
                                allowDetailAutofocus = allowDetailAutofocus,
                                contentFocusRequesters = contentFocusRequesters,
                                experienceModeViewModel = experienceModeViewModel,
                                integrationSection = integrationSection,
                                onSelectIntegrationSection = onSelectIntegrationSection,
                                onIntegrationSectionBack = onIntegrationSectionBack,
                                qualityRulesTrip = qualityRulesTrip,
                                onOpenConnectedServices = openQualityRules,
                                onCloseQualityRules = closeQualityRules,
                                integrationHubReturnSection = integrationHubReturnSection,
                                integrationHubFocusRequester = integrationHubFocusRequester,
                                integrationDebridFocusRequester = integrationDebridFocusRequester,
                                integrationTmdbFocusRequester = integrationTmdbFocusRequester,
                                integrationMdbListFocusRequester = integrationMdbListFocusRequester,
                                integrationAnimeSkipFocusRequester = integrationAnimeSkipFocusRequester,
                                onNavigateToManageProfiles = onNavigateToManageProfiles,
                                onNavigateToAddons = onNavigateToAddons,
                                onNavigateToPlugins = onNavigateToPlugins,
                                onNavigateToScoutTaste = onNavigateToScoutTaste,
                                onNavigateToScoutRoute = onNavigateToScoutRoute,
                                onNavigateToWatchParty = onNavigateToWatchParty,
                                onNavigateToAuthQrSignIn = onNavigateToAuthQrSignIn,
                                onNavigateToSupportersContributors = onNavigateToSupportersContributors,
                                onNavigateToLicensesAttributions = onNavigateToLicensesAttributions,
                                returnToSearch = returnToSearch,
                                searchPane = searchPane
                            )
                        }
                    }
                }
            } else {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
            ) {
                Box(
                    modifier = Modifier
                        .width(if (com.nuvio.tv.ui.v2.appearance.LocalV2Appearance.current != null) 244.dp else 220.dp)
                        .fillMaxHeight()
                ) {
                    LazyColumn(
                        state = railListState,
                        modifier = Modifier
                            .focusRequester(railContainerFocusRequester)
                            // Lets the rail resolve its own focus enter to the item that was on it when
                            // it last lost focus. Without this the enter is resolved by stepping on from
                            // whatever holds focus, which lands an item further along and drags the rail
                            // with it. It agrees with focusRailCategory rather than competing with it:
                            // both name the item the rail was left on. It cannot restore an item that is
                            // no longer composed, which is what the retry there is for.
                            .focusRestorer()
                            .fillMaxSize()
                            .onFocusChanged { state ->
                                val justGainedFocus = !railHadFocus && state.hasFocus
                                railHadFocus = state.hasFocus
                                if (justGainedFocus) {
                                    if (railFallbackAttempt == railFocusAttempt) {
                                        railFallbackAttempt = NO_RAIL_FALLBACK
                                    } else {
                                        focusRailCategory(railFocusCategory)
                                    }
                                }
                            }
                            .onPreviewKeyEvent { event ->
                                // The user moving is the one signal that is unambiguously theirs,
                                // unlike a focus change, which the rail also produces on its own.
                                // Stop a restoration still retrying.
                                if (event.type == KeyEventType.KeyDown && event.key.isDirection()) {
                                    // Retired by number as well as cancelled. The loops check the
                                    // attempt number rather than isActive, so bumping it is what
                                    // actually stops them; the cancel is just the quicker of the
                                    // two.
                                    railFocusAttempt += 1L
                                    railFocusJob?.cancel()
                                    railFocusJob = null
                                    railRestoringCategory = null
                                }
                                val toDetailKey = if (isRtl) Key.DirectionLeft else Key.DirectionRight
                                if (event.type == KeyEventType.KeyDown && event.key == toDetailKey) {
                                    allowDetailAutofocus = true
                                    enterPendingPane() || enterQualityRulesFromRail()
                                } else {
                                    false
                                }
                            },
                        verticalArrangement = if (com.nuvio.tv.ui.v2.appearance.LocalV2Appearance.current != null)
                            Arrangement.spacedBy(2.dp, Alignment.Top)
                        else Arrangement.spacedBy(10.dp, Alignment.CenterVertically)
                    ) {
                        itemsIndexed(
                            items = visibleSections,
                            key = { _, section -> section.category }
                        ) { index, section ->
                            Column {
                                if (visibleCategories.startsNewGroup(index)) {
                                    SettingsRailDivider()
                                }
                                SettingsRailButton(
                                    onFocused = { onSectionFocused(section) },
                                    title = section.title,
                                    icon = section.icon,
                                    rawIconRes = section.rawIconRes,
                                    isSelected = selectedCategory == section.category,
                                    focusRequester = railFocusRequesters[section.category],
                                    onClick = { onSectionClick(section) }
                                )
                            }
                        }
                    }
                    SettingsVerticalScrollIndicators(state = railListState)
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .onKeyEvent { event ->
                            val toRailKey = if (isRtl) Key.DirectionRight else Key.DirectionLeft
                            if (event.type == KeyEventType.KeyDown && event.key == toRailKey) {
                                val movedLeft = focusManager.moveFocus(if (isRtl) FocusDirection.Right else FocusDirection.Left)
                                if (!movedLeft) {
                                    allowDetailAutofocus = false
                                    focusRailCategory(railFocusCategory)
                                }
                                true
                            } else {
                                false
                            }
                        }
                        .onFocusChanged { state ->
                            detailHasFocus = state.hasFocus
                            if (state.hasFocus && !allowDetailAutofocus) {
                                focusRailCategory(railFocusCategory)
                            }
                        }
                ) {
                    SettingsDetailPane(
                        selectedCategory = paneCategory,
                        isCurrent = paneCategory == selectedCategory,
                        isEssentialMode = isEssentialMode,
                        allowDetailAutofocus = allowDetailAutofocus,
                        contentFocusRequesters = contentFocusRequesters,
                        experienceModeViewModel = experienceModeViewModel,
                        integrationSection = integrationSection,
                        onSelectIntegrationSection = onSelectIntegrationSection,
                        onIntegrationSectionBack = onIntegrationSectionBack,
                        qualityRulesTrip = qualityRulesTrip,
                        onOpenConnectedServices = openQualityRules,
                        onCloseQualityRules = closeQualityRules,
                        integrationHubReturnSection = integrationHubReturnSection,
                        integrationHubFocusRequester = integrationHubFocusRequester,
                        integrationDebridFocusRequester = integrationDebridFocusRequester,
                        integrationTmdbFocusRequester = integrationTmdbFocusRequester,
                        integrationMdbListFocusRequester = integrationMdbListFocusRequester,
                        integrationAnimeSkipFocusRequester = integrationAnimeSkipFocusRequester,
                        onNavigateToManageProfiles = onNavigateToManageProfiles,
                        onNavigateToAddons = onNavigateToAddons,
                        onNavigateToPlugins = onNavigateToPlugins,
                        onNavigateToScoutTaste = onNavigateToScoutTaste,
                        onNavigateToScoutRoute = onNavigateToScoutRoute,
                        onNavigateToWatchParty = onNavigateToWatchParty,
                        onNavigateToAuthQrSignIn = onNavigateToAuthQrSignIn,
                        onNavigateToSupportersContributors = onNavigateToSupportersContributors,
                        onNavigateToLicensesAttributions = onNavigateToLicensesAttributions,
                        returnToSearch = returnToSearch,
                        searchPane = searchPane
                    )
                }
            }
            }
        }
        if (jumpUnavailableVisible) {
            NuvioNoticePill(
                text = stringResource(R.string.settings_search_unavailable),
                maxLines = 2,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(
                        start = NuvioTheme.spacing.xl,
                        end = NuvioTheme.spacing.xl,
                        top = NuvioTheme.spacing.xl,
                    )
            )
        }
    }
    }
}

@Composable
private fun SettingsDetailPane(
    selectedCategory: SettingsCategory,
    // False while this page is still on screen after the menu has moved on; its own Back handling then stays out.
    isCurrent: Boolean,
    isEssentialMode: Boolean,
    allowDetailAutofocus: Boolean,
    contentFocusRequesters: Map<SettingsCategory, FocusRequester>,
    experienceModeViewModel: ExperienceModeSettingsViewModel,
    integrationSection: IntegrationSettingsSection,
    onSelectIntegrationSection: (IntegrationSettingsSection) -> Unit,
    onIntegrationSectionBack: () -> Unit,
    qualityRulesTrip: QualityRulesTrip,
    onOpenConnectedServices: (() -> Unit)? = null,
    onCloseQualityRules: () -> Unit = {},
    integrationHubReturnSection: IntegrationSettingsSection,
    integrationHubFocusRequester: FocusRequester,
    integrationDebridFocusRequester: FocusRequester,
    integrationTmdbFocusRequester: FocusRequester,
    integrationMdbListFocusRequester: FocusRequester,
    integrationAnimeSkipFocusRequester: FocusRequester,
    onNavigateToManageProfiles: () -> Unit,
    onNavigateToAddons: () -> Unit,
    onNavigateToPlugins: () -> Unit,
    onNavigateToScoutTaste: () -> Unit,
    onNavigateToScoutRoute: (String) -> Unit,
    onNavigateToWatchParty: () -> Unit,
    onNavigateToAuthQrSignIn: () -> Unit,
    onNavigateToSupportersContributors: () -> Unit,
    onNavigateToLicensesAttributions: () -> Unit,
    returnToSearch: Boolean,
    searchPane: @Composable (FocusRequester?) -> Unit
) {
    when (selectedCategory) {
        SettingsCategory.SEARCH -> searchPane(
            if (allowDetailAutofocus) contentFocusRequesters[SettingsCategory.SEARCH] else null
        )
        SettingsCategory.EXPERIENCE -> EssentialAdvancedSettingsContent(
            experienceModeViewModel = experienceModeViewModel,
            initialFocusRequester = if (allowDetailAutofocus) {
                contentFocusRequesters[SettingsCategory.EXPERIENCE]
            } else {
                null
            }
        )
        SettingsCategory.PROFILES -> ProfileSettingsContent(
            onManageProfiles = onNavigateToManageProfiles,
            initialFocusRequester = if (allowDetailAutofocus) {
                contentFocusRequesters[SettingsCategory.PROFILES]
            } else {
                null
            }
        )
        SettingsCategory.APPEARANCE -> ThemeSettingsContent(
            initialFocusRequester = if (allowDetailAutofocus) {
                contentFocusRequesters[SettingsCategory.APPEARANCE]
            } else {
                null
            },
            onNavigateToSignIn = onNavigateToAuthQrSignIn
        )
        SettingsCategory.LAYOUT -> LayoutSettingsContent(
            initialFocusRequester = if (allowDetailAutofocus) {
                contentFocusRequesters[SettingsCategory.LAYOUT]
            } else {
                null
            },
            essentialMode = isEssentialMode
        )
        SettingsCategory.PLAYBACK -> if (isEssentialMode) {
            EssentialPlaybackSettingsContent(
                initialFocusRequester = if (allowDetailAutofocus) {
                    contentFocusRequesters[SettingsCategory.PLAYBACK]
                } else {
                    null
                }
            )
        } else if (qualityRulesTrip == QualityRulesTrip.Out) {
            BackHandler(enabled = isCurrent && !returnToSearch) { onCloseQualityRules() }
            DebridSettingsContent(
                initialFocusRequester = if (allowDetailAutofocus) {
                    contentFocusRequesters[SettingsCategory.PLAYBACK]
                } else {
                    null
                },
                qualityRulesPage = true
            )
        } else {
            PlaybackSettingsContent(
                onOpenConnectedServices = onOpenConnectedServices,
                returnToQualityRules = qualityRulesTrip == QualityRulesTrip.Back,
                initialFocusRequester = if (allowDetailAutofocus) {
                    contentFocusRequesters[SettingsCategory.PLAYBACK]
                } else {
                    null
                }
            )
        }
        SettingsCategory.ADVANCED -> if (isEssentialMode) {
            EssentialAdvancedSettingsContent(
                experienceModeViewModel = experienceModeViewModel,
                initialFocusRequester = if (allowDetailAutofocus) {
                    contentFocusRequesters[SettingsCategory.ADVANCED]
                } else {
                    null
                }
            )
        } else {
            AdvancedSettingsContent(
                initialFocusRequester = if (allowDetailAutofocus) {
                    contentFocusRequesters[SettingsCategory.ADVANCED]
                } else {
                    null
                },
                experienceModeViewModel = experienceModeViewModel
            )
        }
        SettingsCategory.INTEGRATION -> IntegrationSettingsContent(
            selectedSection = integrationSection,
            onSelectSection = onSelectIntegrationSection,
            onBackFromSection = onIntegrationSectionBack,
            hubReturnSection = integrationHubReturnSection,
            initialFocusRequester = if (allowDetailAutofocus) {
                contentFocusRequesters[SettingsCategory.INTEGRATION]
            } else {
                null
            },
            hubFocusRequester = integrationHubFocusRequester,
            debridFocusRequester = integrationDebridFocusRequester,
            tmdbFocusRequester = integrationTmdbFocusRequester,
            mdbListFocusRequester = integrationMdbListFocusRequester,
            animeSkipFocusRequester = integrationAnimeSkipFocusRequester,
            autoFocusEnabled = allowDetailAutofocus,
            backEnabled = isCurrent && !returnToSearch
        )
        SettingsCategory.BACKUP -> BackupSettingsContent(
            initialFocusRequester = if (allowDetailAutofocus) {
                contentFocusRequesters[SettingsCategory.BACKUP]
            } else {
                null
            }
        )
        SettingsCategory.REPORT -> ReportProblemSettingsContent(
            initialFocusRequester = if (allowDetailAutofocus) {
                contentFocusRequesters[SettingsCategory.REPORT]
            } else {
                null
            }
        )
        SettingsCategory.ABOUT -> AboutSettingsContent(
            onNavigateToSupportersContributors = onNavigateToSupportersContributors,
            onNavigateToLicensesAttributions = onNavigateToLicensesAttributions,
            initialFocusRequester = if (allowDetailAutofocus) {
                contentFocusRequesters[SettingsCategory.ABOUT]
            } else {
                null
            }
        )
        SettingsCategory.SCOUT -> ScoutSettingsContent(
            initialFocusRequester = if (allowDetailAutofocus) {
                contentFocusRequesters[SettingsCategory.SCOUT]
            } else {
                null
            },
            onOpenTaste = onNavigateToScoutTaste,
            onOpenRoute = onNavigateToScoutRoute
        )
        SettingsCategory.CONTENT_DISCOVERY -> ContentDiscoverySettingsContent(
            onNavigateToAddons = onNavigateToAddons,
            onNavigateToPlugins = onNavigateToPlugins,
            onNavigateToWatchParty = onNavigateToWatchParty,
            showPlugins = AppFeaturePolicy.pluginsEnabled && !isEssentialMode,
            initialFocusRequester = if (allowDetailAutofocus) {
                contentFocusRequesters[SettingsCategory.CONTENT_DISCOVERY]
            } else {
                null
            }
        )
        SettingsCategory.ACCOUNT -> AccountSettingsInline(
            onNavigateToAuthQrSignIn = onNavigateToAuthQrSignIn,
            initialFocusRequester = if (allowDetailAutofocus) {
                contentFocusRequesters[SettingsCategory.ACCOUNT]
            } else {
                null
            }
        )
        SettingsCategory.DEBUG -> DebugSettingsContent()
        SettingsCategory.TRACKING -> TrackingSettingsContent(
            initialFocusRequester = if (allowDetailAutofocus) {
                contentFocusRequesters[SettingsCategory.TRACKING]
            } else {
                null
            }
        )
    }
}

@Composable
private fun EssentialAdvancedSettingsContent(
    experienceModeViewModel: ExperienceModeSettingsViewModel,
    initialFocusRequester: FocusRequester?
) {
    var showConfirmation by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.settings_advanced),
            subtitle = stringResource(R.string.experience_mode_switch_to_advanced_header_subtitle)
        )
        SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
            SettingsActionRow(
                title = stringResource(R.string.experience_mode_switch_to_advanced),
                subtitle = stringResource(R.string.experience_mode_switch_to_advanced_subtitle),
                value = stringResource(R.string.experience_mode_essential),
                onClick = { showConfirmation = true },
                modifier = if (initialFocusRequester != null) {
                    Modifier.focusRequester(initialFocusRequester)
                } else {
                    Modifier
                }
            )
        }
    }

    if (showConfirmation) {
        ExperienceModeConfirmationDialog(
            targetMode = ExperienceMode.ADVANCED,
            onConfirm = { experienceModeViewModel.setMode(ExperienceMode.ADVANCED) },
            onDismiss = { showConfirmation = false }
        )
    }
}

@Composable
private fun AccountSettingsInline(
    onNavigateToAuthQrSignIn: () -> Unit,
    initialFocusRequester: FocusRequester?
) {
    val accountViewModel: com.nuvio.tv.ui.screens.account.AccountViewModel = hiltViewModel()
    val accountUiState by accountViewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.settings_account),
            subtitle = stringResource(R.string.settings_account_section_subtitle)
        )
        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (isV2Settings()) Modifier else Modifier.weight(1f))
        ) {
            com.nuvio.tv.ui.screens.account.AccountSettingsContent(
                uiState = accountUiState,
                viewModel = accountViewModel,
                onNavigateToAuthQrSignIn = onNavigateToAuthQrSignIn,
                initialFocusRequester = initialFocusRequester
            )
        }
    }
}

@Composable
private fun IntegrationSettingsContent(
    selectedSection: IntegrationSettingsSection,
    onSelectSection: (IntegrationSettingsSection) -> Unit,
    onBackFromSection: () -> Unit,
    hubReturnSection: IntegrationSettingsSection,
    initialFocusRequester: FocusRequester?,
    hubFocusRequester: FocusRequester,
    debridFocusRequester: FocusRequester,
    tmdbFocusRequester: FocusRequester,
    mdbListFocusRequester: FocusRequester,
    animeSkipFocusRequester: FocusRequester,
    autoFocusEnabled: Boolean,
    backEnabled: Boolean = true
) {
    BackHandler(enabled = backEnabled && selectedSection != IntegrationSettingsSection.Hub) {
        onBackFromSection()
    }
    val hubEntryFocusRequester = when (hubReturnSection) {
        IntegrationSettingsSection.Hub ->
            initialFocusRequester ?: hubFocusRequester
        IntegrationSettingsSection.Debrid -> debridFocusRequester
        IntegrationSettingsSection.Tmdb -> tmdbFocusRequester
        IntegrationSettingsSection.MdbList -> mdbListFocusRequester
        IntegrationSettingsSection.AnimeSkip -> animeSkipFocusRequester
    }
    val hubEntryTargetSection = hubReturnSection.takeIf {
        it != IntegrationSettingsSection.Hub
    } ?: IntegrationSettingsSection.Debrid

    val jumping = LocalSettingsJump.current?.target != null
    LaunchedEffect(selectedSection, autoFocusEnabled) {
        if (!autoFocusEnabled || jumping) return@LaunchedEffect
        val requester = when (selectedSection) {
            IntegrationSettingsSection.Hub -> hubEntryFocusRequester
            IntegrationSettingsSection.Debrid -> debridFocusRequester
            IntegrationSettingsSection.Tmdb -> tmdbFocusRequester
            IntegrationSettingsSection.MdbList -> mdbListFocusRequester
            IntegrationSettingsSection.AnimeSkip -> animeSkipFocusRequester
        }
        val deadline = System.nanoTime() + SETTINGS_DETAIL_FOCUS_RETRY_WINDOW_MS * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (runCatching { requester.requestFocus() }.getOrDefault(false)) break
            withFrameNanos { }
        }
    }

    when (selectedSection) {
        IntegrationSettingsSection.Hub -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SettingsDetailHeader(
                    title = stringResource(R.string.settings_integrations_section),
                    subtitle = stringResource(R.string.settings_integrations_section_subtitle)
                )

                SettingsGroupCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = !isV2Settings())
                ) {
                    val integrationHubState = rememberLazyListState()
                    Box(modifier = if (isV2Settings()) Modifier.fillMaxWidth() else Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = integrationHubState,
                            verticalArrangement = Arrangement.spacedBy(if (isV2Settings()) 4.dp else 10.dp)
                        ) {
                            item(key = "integration_hub_debrid") {
                                SettingsActionRow(
                                    title = stringResource(R.string.debrid_title),
                                    subtitle = stringResource(R.string.settings_debrid_subtitle),
                                    leadingIcon = Icons.Default.CloudDownload,
                                    onClick = { onSelectSection(IntegrationSettingsSection.Debrid) },
                                    modifier = if (hubEntryTargetSection == IntegrationSettingsSection.Debrid) {
                                        Modifier.focusRequester(hubEntryFocusRequester)
                                    } else {
                                        Modifier
                                    }
                                )
                            }
                            item(key = "integration_hub_tmdb") {
                                SettingsActionRow(
                                    title = "TMDB",
                                    subtitle = stringResource(R.string.settings_tmdb_subtitle),
                                    leadingRawIconRes = R.raw.mdblist_tmdb,
                                    onClick = { onSelectSection(IntegrationSettingsSection.Tmdb) },
                                    modifier = if (hubEntryTargetSection == IntegrationSettingsSection.Tmdb) {
                                        Modifier.focusRequester(hubEntryFocusRequester)
                                    } else {
                                        Modifier
                                    }
                                )
                            }
                            item(key = "integration_hub_mdblist") {
                                SettingsActionRow(
                                    title = stringResource(R.string.mdblist_title),
                                    subtitle = stringResource(R.string.settings_mdblist_subtitle),
                                    leadingRawIconRes = R.raw.mdblist_logo,
                                    onClick = { onSelectSection(IntegrationSettingsSection.MdbList) },
                                    modifier = if (hubEntryTargetSection == IntegrationSettingsSection.MdbList) {
                                        Modifier.focusRequester(hubEntryFocusRequester)
                                    } else {
                                        Modifier
                                    }
                                )
                            }
                            item(key = "integration_hub_animeskip") {
                                SettingsActionRow(
                                    title = "Anime-Skip",
                                    subtitle = stringResource(R.string.settings_animeskip_subtitle),
                                    leadingIcon = Icons.Default.FastForward,
                                    onClick = { onSelectSection(IntegrationSettingsSection.AnimeSkip) },
                                    modifier = if (hubEntryTargetSection == IntegrationSettingsSection.AnimeSkip) {
                                        Modifier.focusRequester(hubEntryFocusRequester)
                                    } else {
                                        Modifier
                                    }
                                )
                            }
                        }
                        SettingsVerticalScrollIndicators(state = integrationHubState)
                    }
                }
            }
        }

        IntegrationSettingsSection.Debrid -> {
            DebridSettingsContent(
                initialFocusRequester = debridFocusRequester
            )
        }

        IntegrationSettingsSection.Tmdb -> {
            TmdbSettingsContent(
                initialFocusRequester = tmdbFocusRequester
            )
        }

        IntegrationSettingsSection.MdbList -> {
            MDBListSettingsContent(
                initialFocusRequester = mdbListFocusRequester
            )
        }
        IntegrationSettingsSection.AnimeSkip -> {
            AnimeSkipSettingsContent(
                initialFocusRequester = animeSkipFocusRequester
            )
        }
    }
}

/**
 * Whether the item at [index] is on screen at all. Used to decide whether the rail has to be
 * scrolled before an item can be reached, not whether the item can take focus: being listed here
 * does not promise an attached requester, which is what the retry after the scroll is for.
 */
private fun LazyListState.isItemVisible(index: Int): Boolean =
    layoutInfo.visibleItemsInfo.any { it.index == index }

/** Whether [this] is one of the directional keys, so a press of it means the user is moving. */
private fun Key.isDirection(): Boolean =
    this == Key.DirectionUp ||
        this == Key.DirectionDown ||
        this == Key.DirectionLeft ||
        this == Key.DirectionRight


/** Keep animated pill dimensions in their own scope instead of recomposing the Settings page. */
@Composable
private fun SettingsHorizonFocusPill(bounds: Rect, focused: Boolean, modifier: Modifier) {
    val density = LocalDensity.current
    val glideSpec = tween<Float>(durationMillis = 250, easing = FastOutSlowInEasing)
    val pillLeft by animateFloatAsState(bounds.left, glideSpec, label = "pillLeft")
    val pillTop by animateFloatAsState(bounds.top, glideSpec, label = "pillTop")
    val pillWidth by animateFloatAsState(bounds.width, glideSpec, label = "pillWidth")
    val pillHeight by animateFloatAsState(bounds.height, glideSpec, label = "pillHeight")
    val pillAlpha by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "pillAlpha"
    )
    Box(
        modifier = modifier
            .absoluteOffset { IntOffset(pillLeft.roundToInt(), pillTop.roundToInt()) }
            .size(
                width = with(density) { pillWidth.toDp() },
                height = with(density) { pillHeight.toDp() }
            )
            .graphicsLayer { alpha = pillAlpha }
            .clip(RoundedCornerShape(SettingsPillRadius))
            .background(NuvioTheme.colors.FocusBackground)
    )
}
