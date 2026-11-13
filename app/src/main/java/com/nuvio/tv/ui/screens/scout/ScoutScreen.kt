@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)

package com.nuvio.tv.ui.screens.scout

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import com.nuvio.tv.domain.model.ScoutRating
import com.nuvio.tv.domain.model.ScoutTitle
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.NuvioScrollDefaults
import com.nuvio.tv.ui.components.posteroptions.PosterOptionsController
import com.nuvio.tv.ui.scout.ScoutRateRow
import com.nuvio.tv.ui.scout.ScoutRatingIcon
import com.nuvio.tv.ui.scout.ScoutRatingViewModel
import com.nuvio.tv.core.scout.ask.AskFailure
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.screens.scout.ask.AskInputBar
import com.nuvio.tv.ui.screens.scout.ask.failureText
import com.nuvio.tv.ui.screens.settings.ScoutThumbsPage
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.core.image.sizedHomeArtworkUrl
import com.nuvio.tv.core.scout.scoutMoodArt
import com.nuvio.tv.core.scout.SCOUT_PICKS_SHOWN
import com.nuvio.tv.core.scout.ScoutAnswers
import com.nuvio.tv.core.scout.ScoutMood
import com.nuvio.tv.core.scout.ScoutPickDetail
import com.nuvio.tv.core.scout.ScoutTime
import com.nuvio.tv.core.scout.ScoutTune
import com.nuvio.tv.core.scout.ScoutWatch
import com.nuvio.tv.core.scout.ScoutWhy
import com.nuvio.tv.core.scout.scoutWhy
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.ScoutPick
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.ui.components.NuvioCard
import com.nuvio.tv.ui.components.posteroptions.PosterOptionsHost
import com.nuvio.tv.ui.components.posteroptions.PosterOptionsViewModel
import com.nuvio.tv.ui.screens.home.MODERN_LANDSCAPE_LOGO_GRADIENT
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.contentTextDirection
import com.nuvio.tv.ui.util.rememberRawSvgPainter
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.components.AvatarModel
import com.nuvio.tv.ui.v2.components.GlassRole
import com.nuvio.tv.ui.v2.components.NuvioActionPill
import com.nuvio.tv.ui.v2.components.NuvioAvatar
import com.nuvio.tv.ui.v2.components.nuvioGlass
import com.nuvio.tv.ui.v2.components.nuvioV2Focus
import com.nuvio.tv.ui.v2.components.sidebarPageContent
import java.util.Locale

private const val BULLET = " • "
private const val TAB_TASTE = "tab_taste"
private const val TAB_MOOD = "mood_"
private const val TAB_TUNE = "tune_"
private const val TAB_SURPRISE = "tab_surprise"
private const val TAB_FILTERS = "tab_filters"
private const val ASK_INPUT = "ask_input"
private const val CHOOSING_LENGTH = "length"
private const val CHOOSING_WHO = "who"
private const val AVATARS_SHOWN = 3
private const val FOCUS_TRIES = 8
private const val WORD_MIXES = "word_mixes"
private val COMPACT_PAGE = 680.dp
private const val PICK_TILES = 3
private const val TASTE_TILES = 4
private const val TASTE_ACROSS = 10
private const val HERO_BLOCK_BIAS = -.3f
private const val TASTE_BLOCK_BIAS = -.2f
private val LOGO_TALL = 80.dp
private val LOGO_SMALL = 56.dp
private val MOOD_LINE_HEIGHT = 44.dp

/** Room for the focus edge. Goes inside a scroll modifier, which clips, and comes off the padding outside it. */
internal val ScoutFocusRoom = 20.dp
private val FOCUS_ROOM = ScoutFocusRoom

/** Sentences to read at the small size, spaced like the episode card descriptions. */
@Composable
@ReadOnlyComposable
internal fun scoutReadingStyle() = MaterialTheme.typography.bodySmall.let { it.copy(lineHeight = it.fontSize * 1.5f) }

/** The page's boxes: glass in Nuvio V2, the card colour in Original. */
@Composable
internal fun Modifier.scoutPanel(shape: Shape): Modifier =
    if (LocalV2Appearance.current != null) nuvioGlass(GlassRole.PANEL, shape = shape)
    else background(NuvioTheme.colors.BackgroundCard, shape)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ScoutScreen(
    onOpenTitle: (id: String, type: String) -> Unit,
    onPlayTitle: (id: String, type: String, backdrop: String?, logo: String?) -> Unit,
    onOpenPicker: () -> Unit,
    onOpenPerson: (id: Int, name: String, crew: Boolean) -> Unit,
    onOpenSlice: (sliceId: String) -> Unit,
    onAsk: (() -> Unit)?,
    startOnTaste: Boolean = false,
    startOnMixes: Boolean = false,
    onPlayMix: (id: String) -> Unit = {},
    onOpenMix: (id: String) -> Unit = {},
    onMixOwn: (recipe: String?) -> Unit = {},
    viewModel: ScoutViewModel = hiltViewModel(),
    posterOptions: PosterOptionsViewModel = hiltViewModel(),
    ratings: ScoutRatingViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val saved by viewModel.savedRows.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeId by viewModel.activeProfileId.collectAsStateWithLifecycle()
    val pictures by viewModel.profilePictures.collectAsStateWithLifecycle()
    val scoutProfile by ratings.profile.collectAsStateWithLifecycle()
    val requesters = remember { HashMap<String, FocusRequester>() }
    var choosing by rememberSaveable { mutableStateOf<String?>(null) }
    var showThumbs by rememberSaveable { mutableStateOf(false) }
    var chosenGenre by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmFresh by rememberSaveable { mutableStateOf(false) }
    var rating by remember { mutableStateOf<ScoutPick?>(null) }
    var mixesOpen by rememberSaveable { mutableStateOf(startOnMixes) }
    var mixesOpened by remember { mutableStateOf(false) }
    if (mixesOpen) mixesOpened = true
    val readyModel = if (mixesOpened) hiltViewModel<ScoutReadyMixesViewModel>() else null
    val readyState = readyModel?.state?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(readyModel, state.answers.family) { readyModel?.setFamily(state.answers.family) }
    BackHandler(enabled = mixesOpen && !startOnMixes) { mixesOpen = false }
    fun focus(key: String): Modifier = Modifier
        .focusRequester(requesters.getOrPut(key) { FocusRequester() })
        .onFocusChanged { if (it.isFocused && viewModel.focusPending == null) viewModel.focusKey = key }

    BackHandler(enabled = showThumbs) { showThumbs = false }
    BackHandler(enabled = !showThumbs && (state.surprise || state.moreLike != null || state.asking)) { viewModel.backToMoods() }
    LaunchedEffect(state.askFailure) { if (state.askFailure == AskFailure.NO_KEY) onAsk?.invoke() }

    LaunchedEffect(Unit) {
        if (startOnTaste) {
            viewModel.focusKey = TAB_TASTE
            viewModel.focusTab(ScoutTab.Taste)
        }
    }
    BackHandler(enabled = !showThumbs && !startOnTaste && state.tab == ScoutTab.Taste && !state.surprise && state.moreLike == null && !state.asking) { viewModel.leaveTaste() }
    LaunchedEffect(state.surprise, state.moreLike != null, state.asking, state.pinFor, showThumbs, state.tab == ScoutTab.Taste, mixesOpen) {
        if (state.pinFor != null || showThumbs || state.asking || mixesOpen) return@LaunchedEffect
        val first = if (state.surprise) SCOUT_FOCUS_THREE_MORE else SCOUT_FOCUS_FOR_YOU
        repeat(FOCUS_TRIES) {
            val target = requesters[viewModel.focusPending ?: viewModel.focusKey] ?: requesters[first]
            if (target?.requestFocusAfterFrames() == true) {
                viewModel.focusPending = null
                return@LaunchedEffect
            }
            withFrameNanos { }
        }
        viewModel.focusPending = null
    }

    var tasteOpened by remember { mutableStateOf(false) }
    if (state.tab == ScoutTab.Taste) tasteOpened = true
    val tasteModel = if (tasteOpened) hiltViewModel<ScoutTasteViewModel>() else null
    val taste = tasteModel?.state?.collectAsStateWithLifecycle()?.value

    if (showThumbs && taste != null && tasteModel != null) {
        LaunchedEffect(taste.thumbs) { if (taste.thumbs == 0) showThumbs = false }
        Box(
            Modifier
                .fillMaxSize()
                .background(NuvioTheme.colors.Background)
                .padding(horizontal = NuvioTheme.spacing.screen.overscanHorizontal, vertical = NuvioTheme.spacing.screen.overscanVertical)
        ) {
            ScoutThumbsPage(
                entries = taste.profile.ratings.values.sortedByDescending { it.ratedAt },
                onRate = { entry, chosen -> tasteModel.setRating(entry.title, chosen) }
            )
        }
        return
    }

    val onTaste = !mixesOpen && state.tab == ScoutTab.Taste && !state.surprise && state.moreLike == null
    if (state.pinFor == null) BoxWithConstraints(Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) {
        val compact = maxHeight < COMPACT_PAGE
        val tallest = maxHeight * .31f
        ScoutBackdrop(
            picture = if (onTaste) taste?.summary?.backdrops?.firstOrNull() else state.hero?.pick?.backdrop,
            wide = onTaste
        )
        if (mixesOpen) Box(Modifier.matchParentSize().background(NuvioTheme.colors.Background)) {
            ScoutBackdrop(readyState?.focusedTile?.art)
        }
        BoxWithConstraints(
            modifier = Modifier
                .sidebarPageContent()
                .fillMaxSize()
                .padding(
                    start = NuvioTheme.spacing.screen.overscanHorizontal - FOCUS_ROOM,
                    end = NuvioTheme.spacing.screen.overscanHorizontal - FOCUS_ROOM,
                    top = NuvioTheme.spacing.md
                )
        ) {
        val tileHeight = scoutTileHeight(maxWidth, PICK_TILES).coerceAtMost(tallest)
        val tasteTileHeight = scoutTileHeight(maxWidth, TASTE_TILES).coerceAtMost(tallest)
        val tasteSlot = (maxWidth - FOCUS_ROOM * 2 - NuvioTheme.spacing.lg * (TASTE_ACROSS - 1)) / TASTE_ACROSS
        Column(Modifier.fillMaxSize()) {
            ScoutTopWords(
                answers = state.answers,
                others = state.others,
                profiles = profiles,
                pictures = pictures,
                activeId = activeId,
                focus = ::focus,
                onWatch = {
                    mixesOpen = false
                    viewModel.pressWatch(it)
                },
                onLength = { choosing = CHOOSING_LENGTH },
                onWho = { choosing = CHOOSING_WHO },
                onTaste = onTaste,
                onOpenTaste = {
                    mixesOpen = false
                    if (onTaste) viewModel.leaveTaste() else viewModel.focusTab(ScoutTab.Taste)
                },
                mixesOn = mixesOpen,
                onMixes = { mixesOpen = true }
            )
            if (mixesOpen && readyState != null && readyModel != null) {
                val entry = { key: String -> requesters.getOrPut(key) { FocusRequester() } }
                val play = { id: String -> readyModel.prepare(id) { onPlayMix(id) } }
                val open = { id: String -> readyModel.prepare(id) { onOpenMix(id) } }
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = FOCUS_ROOM),
                    contentAlignment = BiasAlignment(-1f, HERO_BLOCK_BIAS)
                ) {
                    ScoutReadyHero(readyState, ::focus, onPlay = play, onSave = readyModel::save, onAdjust = { onMixOwn(it) })
                }
                ScoutReadyRows(
                    state = readyState,
                    tileHeight = tileHeight,
                    focus = ::focus,
                    entry = entry,
                    onTab = readyModel::focusTab,
                    onFocusMix = readyModel::focusMix,
                    onOpen = open,
                    onOwn = { onMixOwn(null) }
                )
                ScoutReadyFirstFocus(readyState, entry)
            } else {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = FOCUS_ROOM),
                contentAlignment = BiasAlignment(-1f, if (onTaste) TASTE_BLOCK_BIAS else HERO_BLOCK_BIAS)
            ) {
                when {
                    onTaste && taste != null -> ScoutTasteHero(taste, ::focus) { chosenGenre = it }
                    state.askBusy -> Text(stringResource(R.string.scout_question_thinking, stringResource(R.string.scout_title)), style = MaterialTheme.typography.titleLarge, color = NuvioTheme.colors.TextPrimary)
                    state.askFailure != null -> Text(stringResource(failureText(state.askFailure!!)), style = MaterialTheme.typography.titleLarge, color = NuvioTheme.colors.TextPrimary)
                    state.nothing -> Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)) {
                        Text(stringResource(R.string.scout_nothing_fits), style = MaterialTheme.typography.titleLarge, color = NuvioTheme.colors.TextPrimary)
                        Text(stringResource(R.string.scout_nothing_fits_sub), style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
                    }
                    else -> state.hero?.let { hero ->
                        ScoutHeroBlock(
                            shown = hero,
                            logo = state.heroLogo,
                            compact = compact,
                            ratingsOn = scoutProfile.settings.ratingsEnabled,
                            rating = scoutProfile.rating("tmdb:${hero.pick.tmdbId}"),
                            focus = ::focus,
                            controller = posterOptions.controller,
                            onPlay = { onPlayTitle("tmdb:${hero.pick.tmdbId}", if (hero.pick.tv) "series" else "movie", hero.pick.backdrop, state.heroLogo) },
                            onDetails = { onOpenTitle("tmdb:${hero.pick.tmdbId}", if (hero.pick.tv) "series" else "movie") },
                            onRate = { rating = hero.pick }
                        )
                    }
                }
            }
            if (state.asking) {
                Box(Modifier.fillMaxWidth().padding(horizontal = FOCUS_ROOM)) {
                    AskInputBar(
                        onAsk = viewModel::ask,
                        focusIn = requesters.getOrPut(ASK_INPUT) { FocusRequester() },
                        down = requesters.getOrPut(SCOUT_FOCUS_FOR_YOU) { FocusRequester() }
                    )
                }
            } else if (!onTaste) {
                ScoutMoodLine(state, onTaste, onAsk?.let { { viewModel.openAsk() } }, ::focus)
            }
            val tabs = scoutTabItems(state)
            if (!onTaste) key(state.surprise, state.asking) {
                ScoutTabs(
                    items = tabs,
                    selected = tabs.firstOrNull { it.selected }?.let { requesters.getOrPut(it.key) { FocusRequester() } } ?: FocusRequester.Default,
                    entry = { requesters.getOrPut(it.key) { FocusRequester() } },
                    focus = ::focus,
                    onFocused = { item ->
                        if (!state.surprise && !state.asking) when {
                            item.key == SCOUT_FOCUS_FOR_YOU -> viewModel.focusTab(ScoutTab.ForYou)
                            item.key.startsWith(TAB_MOOD) -> moodOf(item.key)?.let { viewModel.focusTab(ScoutTab.Feeling(it)) }
                        }
                    },
                    onClick = { item ->
                        when {
                            state.asking -> viewModel.backToMoods()
                            item.key == TAB_SURPRISE -> viewModel.surprise()
                            item.key == TAB_FILTERS -> onOpenPicker()
                            item.key == SCOUT_FOCUS_THREE_MORE -> viewModel.threeMore()
                            item.key.startsWith(TAB_TUNE) -> ScoutTune.entries.firstOrNull { it.code == item.key.removePrefix(TAB_TUNE) }?.let(viewModel::pressTune)
                        }
                    }
                )
            }
            if (onTaste && taste != null && tasteModel != null) {
                Box(Modifier.height(tileHeight + NuvioTheme.spacing.md)) {
                    ScoutTasteBottom(
                        state = taste,
                        viewModel = tasteModel,
                        height = tasteTileHeight,
                        slot = tasteSlot,
                        focus = ::focus,
                        onOpenTitle = onOpenTitle,
                        onOpenPerson = onOpenPerson,
                        onThumbs = { showThumbs = true },
                        onStartFresh = { confirmFresh = true }
                    )
                }
            } else {
                ScoutPickRow(state, saved, tileHeight, ::focus, viewModel, onOpenTitle) { pick ->
                    posterOptions.controller.show(pick.toPreview(), null)
                }
            }
            }
            Spacer(Modifier.height(NuvioTheme.spacing.sm))
        }
        }
    }

    if (tasteModel != null && taste != null) {
        ScoutTasteDialogs(
            viewModel = tasteModel,
            thumbs = taste.thumbs,
            chosenGenre = chosenGenre,
            onCloseGenre = { chosenGenre = null },
            onOpenSlice = onOpenSlice,
            confirmFresh = confirmFresh,
            onCloseFresh = { confirmFresh = false },
            onFresh = { requesters[TAB_TASTE]?.let { runCatching { it.requestFocus() } } }
        )
    }

    if (state.pinFor == null) when (choosing) {
        CHOOSING_LENGTH -> ScoutLengthDialog(state.answers, onChoose = {
            viewModel.pressTime(it)
            choosing = null
        }, onDismiss = { choosing = null })
        CHOOSING_WHO -> ScoutWhoDialog(state, profiles, pictures, activeId, viewModel, onDismiss = { choosing = null })
    }

    rating?.let { pick ->
        val first = remember { FocusRequester() }
        val id = "tmdb:${pick.tmdbId}"
        NuvioDialog(onDismiss = { rating = null }, title = pick.name) {
            ScoutRateRow(
                current = scoutProfile.rating(id),
                onRate = {
                    ratings.setRating(ScoutTitle(id, if (pick.tv) "series" else "movie", pick.name, pick.poster), it)
                    rating = null
                },
                firstFocus = first
            )
        }
        LaunchedEffect(Unit) { first.requestFocusAfterFrames() }
    }

    val optionsState by posterOptions.controller.state.collectAsState()
    PosterOptionsHost(
        state = optionsState,
        controller = posterOptions.controller,
        onNavigateToDetail = { id, type, _ -> onOpenTitle(id, type) },
        onMoreLike = { item ->
            state.row.firstOrNull { "tmdb:${it.tmdbId}" == item.id }?.let { viewModel.moreLike(it) }
        }
    )

    state.pinFor?.let { profile ->
        Box(Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) {
            com.nuvio.tv.ui.screens.profile.ProfilePinOverlay(
                state = com.nuvio.tv.ui.screens.profile.ProfilePinOverlayState.Unlock(profile),
                isWorking = state.pinChecking,
                errorMessage = if (state.pinWrong) stringResource(R.string.account_error_incorrect_pin) else null,
                onClearError = viewModel::clearPinError,
                onDismiss = viewModel::dismissPin,
                onSubmit = viewModel::submitPin
            )
        }
    }
}

private fun moodOf(key: String) = ScoutMood.entries.firstOrNull { it.code == key.removePrefix(TAB_MOOD) }

/** The height that makes [count] tiles fill the row exactly, each the same size. */
@Composable
private fun scoutTileHeight(rowWidth: Dp, count: Int): Dp =
    (rowWidth - FOCUS_ROOM * 2 - NuvioTheme.spacing.xl * (count - 1)) / count * 9f / 16f

@Composable
private fun scoutTabItems(state: ScoutScreenState): List<ScoutTabItem> {
    val answers = state.answers
    if (state.asking) return listOf(ScoutTabItem(SCOUT_FOCUS_FOR_YOU, stringResource(R.string.scout_for_you), false))
    if (state.surprise) {
        return buildList {
            add(ScoutTabItem(SCOUT_FOCUS_THREE_MORE, stringResource(R.string.scout_tune_more), false))
            ScoutTune.entries.filter { it != ScoutTune.FROM_COUNTRY }.forEach {
                add(ScoutTabItem(TAB_TUNE + it.code, stringResource(it.labelRes), it in answers.tunes))
            }
            if (state.fromCountryOffered && answers.offersFromCountry) {
                add(
                    ScoutTabItem(
                        TAB_TUNE + ScoutTune.FROM_COUNTRY.code,
                        stringResource(R.string.scout_tune_from_country, Locale.getDefault().displayCountry),
                        ScoutTune.FROM_COUNTRY in answers.tunes
                    )
                )
            }
        }
    }
    val tab = state.tab
    return buildList {
        add(ScoutTabItem(SCOUT_FOCUS_FOR_YOU, stringResource(R.string.scout_for_you), tab == ScoutTab.ForYou))
        ScoutMood.choicesFor(answers.watch, answers.family).forEach { mood ->
            add(ScoutTabItem(TAB_MOOD + mood.code, stringResource(mood.labelRes), tab == ScoutTab.Feeling(mood)))
        }
        add(ScoutTabItem(TAB_SURPRISE, stringResource(R.string.scout_tile_surprise), false))
        add(ScoutTabItem(TAB_FILTERS, stringResource(R.string.scout_more_filters), false))
    }
}

@Composable
private fun ScoutTopWords(
    answers: ScoutAnswers,
    others: Set<Int>,
    profiles: List<UserProfile>,
    pictures: Map<Int, String>,
    activeId: Int,
    focus: (String) -> Modifier,
    onWatch: (ScoutWatch) -> Unit,
    onLength: () -> Unit,
    onWho: () -> Unit,
    onTaste: Boolean,
    onOpenTaste: () -> Unit,
    mixesOn: Boolean,
    onMixes: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ScoutWatch.entries.forEach { watch ->
            ScoutWord(
                label = stringResource(watch.labelRes),
                onClick = { onWatch(watch) },
                modifier = Modifier.padding(start = if (watch == ScoutWatch.entries.first()) FOCUS_ROOM - NuvioTheme.spacing.md else 0.dp).then(focus("watch_${watch.code}")),
                on = !mixesOn && answers.watch == watch
            )
        }
        if (com.nuvio.tv.domain.model.ScoutMixes.available) {
            ScoutWord(stringResource(R.string.scout_premix_word), onMixes, focus(WORD_MIXES), on = mixesOn)
        }
        Spacer(Modifier.weight(1f))
        ScoutWord(stringResource(answers.time.labelRes), onLength, focus("word_length"), on = answers.time != ScoutTime.ANY)
        ScoutWord(stringResource(R.string.scout_your_taste), onOpenTaste, focus(TAB_TASTE), on = onTaste)
        val joined = profiles.filter { it.id in others }
        val active = profiles.firstOrNull { it.id == activeId }
        ScoutWord(
            label = when {
                joined.isEmpty() -> stringResource(R.string.scout_just_me)
                joined.size == 1 -> stringResource(R.string.scout_who_and, joined.first().name)
                else -> stringResource(R.string.scout_who_count, joined.size + 1)
            },
            onClick = onWho,
            modifier = focus("word_who").padding(end = FOCUS_ROOM - NuvioTheme.spacing.md),
            on = joined.isNotEmpty() || answers.family,
            leading = {
                (listOfNotNull(active) + joined).take(AVATARS_SHOWN).forEach { profile ->
                    NuvioAvatar(AvatarModel(profile.id.toString(), profile.name, profile.avatarColorHex, pictures[profile.id]), NuvioTheme.spacing.xl)
                }
            }
        )
    }
}

@Composable
private fun ScoutLengthDialog(answers: ScoutAnswers, onChoose: (ScoutTime) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    NuvioDialog(onDismiss = onDismiss, title = stringResource(R.string.scout_q_how_long)) {
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
            ScoutTime.choicesFor(answers.watch).forEach { time ->
                ScoutOptionRow(
                    label = stringResource(time.labelRes),
                    on = answers.time == time,
                    onClick = { onChoose(time) },
                    modifier = if (answers.time == time) Modifier.focusRequester(first) else Modifier
                )
            }
        }
    }
    LaunchedEffect(Unit) { first.requestFocusAfterFrames() }
}

@Composable
private fun ScoutWhoDialog(
    state: ScoutScreenState,
    profiles: List<UserProfile>,
    pictures: Map<Int, String>,
    activeId: Int,
    viewModel: ScoutViewModel,
    onDismiss: () -> Unit
) {
    val first = remember { FocusRequester() }
    NuvioDialog(onDismiss = onDismiss, title = stringResource(R.string.scout_q_who), subtitle = stringResource(R.string.scout_who_hint)) {
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
            profiles.sortedBy { it.id != activeId }.forEachIndexed { index, profile ->
                val own = profile.id == activeId
                ScoutOptionRow(
                    label = profile.name,
                    on = own || profile.id in state.others,
                    onClick = { if (!own) viewModel.pressProfile(profile) },
                    modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                    note = if (own) stringResource(R.string.scout_who_you) else null,
                    leading = { NuvioAvatar(AvatarModel(profile.id.toString(), profile.name, profile.avatarColorHex, pictures[profile.id]), NuvioTheme.spacing.xxl) }
                )
            }
            ScoutOptionRow(
                label = stringResource(R.string.scout_family_night),
                on = state.answers.family,
                onClick = viewModel::pressFamily,
                note = stringResource(R.string.scout_family_suitable),
                leading = { Icon(Icons.Default.Groups, null, Modifier.size(NuvioTheme.spacing.xxl)) }
            )
        }
    }
    LaunchedEffect(Unit) { first.requestFocusAfterFrames() }
}

@Composable
private fun ScoutHeroBlock(
    shown: ScoutShownPick,
    logo: String?,
    compact: Boolean,
    ratingsOn: Boolean,
    rating: ScoutRating?,
    focus: (String) -> Modifier,
    controller: PosterOptionsController,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
    onRate: () -> Unit
) {
    val pick = shown.pick
    val v2 = LocalV2Appearance.current != null
    val scope = rememberCoroutineScope()
    var inLibrary by remember(pick.key) { mutableStateOf(false) }
    LaunchedEffect(pick.key) { inLibrary = controller.isInLibrary(pick.toPreview()) }
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
        ScoutHeroLogo(logo, pick.name, if (compact) LOGO_SMALL else LOGO_TALL)
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm), modifier = Modifier.focusGroup()) {
            ScoutChip(stringResource(R.string.hero_play), primary = !v2, onClick = onPlay, modifier = focus("hero_play"), icon = {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
            })
            ScoutChip(stringResource(R.string.scout_details), onClick = onDetails, modifier = focus("hero_details"), icon = {
                Icon(Icons.Outlined.Info, null, Modifier.size(18.dp))
            })
            ScoutChip(
                stringResource(if (inLibrary) R.string.hero_remove_from_library else R.string.hero_add_to_library),
                onClick = { scope.launch { inLibrary = controller.toggleLibraryOf(pick.toPreview()) } },
                modifier = focus("hero_library"),
                icon = { Icon(if (inLibrary) Icons.Default.Check else Icons.Default.Add, null, Modifier.size(18.dp)) }
            )
            if (ratingsOn) {
                ScoutChip(stringResource(R.string.scout_rate), onClick = onRate, modifier = focus("hero_rate"), icon = { ScoutRatingIcon(rating, 18.dp) })
            }
        }
        Text(
            text = scoutReasonLine(shown),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(.62f)
        )
        pick.overview?.takeIf { it.isNotBlank() }?.let { overview ->
            Text(
                text = overview,
                style = MaterialTheme.typography.bodyMedium.copy(textDirection = overview.contentTextDirection()),
                color = NuvioTheme.colors.TextPrimary,
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(.5f)
            )
        }
        Text(scoutMetaLine(shown), style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary, maxLines = 1)
    }
}

/** The title's logo, or its name in the theme's headline when it has none. */
@Composable
internal fun ScoutHeroLogo(logo: String?, name: String, height: Dp) {
    var failed by remember(logo) { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth(.5f).height(height), contentAlignment = Alignment.CenterStart) {
        if (!logo.isNullOrBlank() && !failed) {
            AsyncImage(
                model = logo,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
                onError = { failed = true },
                modifier = Modifier.fillMaxHeight()
            )
        } else {
            Text(name, style = MaterialTheme.typography.headlineLarge, color = NuvioTheme.colors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ScoutMoodLine(state: ScoutScreenState, onTaste: Boolean, onAsk: (() -> Unit)?, focus: (String) -> Modifier) {
    val text = when {
        onTaste -> null
        state.moreLike != null -> stringResource(R.string.scout_more_like, state.moreLike)
        state.surprise -> stringResource(R.string.scout_tile_surprise)
        else -> stringResource(R.string.scout_mood_question)
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(MOOD_LINE_HEIGHT).padding(horizontal = FOCUS_ROOM),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        if (text != null) Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary, maxLines = 1)
        if (text != null && onAsk != null && !state.surprise && state.moreLike == null) {
            ScoutChip(stringResource(R.string.scout_or_just_ask), onClick = onAsk, modifier = focus("ask"), icon = {
                Icon(Icons.Default.Mic, null, Modifier.size(18.dp))
            })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScoutPickRow(
    state: ScoutScreenState,
    saved: Set<String>,
    height: Dp,
    focus: (String) -> Modifier,
    viewModel: ScoutViewModel,
    onOpenTitle: (String, String) -> Unit,
    onLongPress: (ScoutPick) -> Unit
) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides NuvioScrollDefaults.smoothScrollSpec) {
        val list = key(state.rowTurn) { rememberLazyListState() }
        LazyRow(
            state = list,
            modifier = Modifier.fillMaxWidth().height(height + NuvioTheme.spacing.md).scoutRowBounds(),
            contentPadding = PaddingValues(horizontal = FOCUS_ROOM, vertical = NuvioTheme.spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl)
        ) {
            items(state.row, key = { it.key }) { pick ->
                ScoutWideTile(
                    picture = pick.backdrop ?: pick.poster,
                    title = pick.name,
                    line = scoutReasonLine(ScoutShownPick(pick, null, pick.key in state.everyone, pick.aiReason.takeIf { state.asking })),
                    height = height,
                    onClick = { onOpenTitle("tmdb:${pick.tmdbId}", if (pick.tv) "series" else "movie") },
                    onLongClick = { onLongPress(pick) },
                    onFocused = { viewModel.focusPick(pick) },
                    modifier = Modifier.focusProperties { canFocus = !state.rowLoading }.then(focus("pick_${pick.key}"))
                )
            }
            if (state.canSave && state.row.isNotEmpty()) {
                item(key = "save") {
                    val isSaved = state.answers.encode() in saved
                    ScoutWideTile(
                        picture = null,
                        title = stringResource(if (isSaved) R.string.scout_saved_row else R.string.scout_save_row),
                        line = null,
                        height = height,
                        onClick = viewModel::toggleSaved,
                        modifier = focus("save")
                    )
                }
            }
        }
    }
}

private fun ScoutPick.toPreview() = MetaPreview(
    id = "tmdb:$tmdbId",
    type = if (tv) ContentType.SERIES else ContentType.MOVIE,
    rawType = if (tv) "series" else "movie",
    name = name,
    poster = poster,
    posterShape = PosterShape.POSTER,
    background = backdrop,
    logo = null,
    description = overview,
    releaseInfo = released?.take(4),
    released = released,
    imdbRating = rating,
    voteCount = votes,
    genres = emptyList()
)

@Composable
private fun scoutMetaLine(shown: ScoutShownPick): String {
    val pick = shown.pick
    val detail: ScoutPickDetail? = shown.detail
    val parts = buildList {
        pick.released?.take(4)?.let(::add)
        detail?.seasons?.let { seasons ->
            add(
                pluralStringResource(
                    if (detail.ended) R.plurals.scout_meta_seasons_finished else R.plurals.scout_meta_seasons,
                    seasons,
                    seasons
                )
            )
        }
        detail?.minutes?.takeIf { it > 0 }?.let { minutes ->
            add(
                if (minutes < 60) stringResource(R.string.scout_meta_minutes, minutes)
                else stringResource(R.string.scout_meta_hours, minutes / 60, minutes % 60)
            )
        }
        detail?.rating?.let(::add)
        pick.rating?.takeIf { it > 0f }?.let { add(String.format(Locale.getDefault(), "%.1f", it)) }
    }
    return parts.joinToString(BULLET)
}

@Composable
private fun scoutReasonLine(shown: ScoutShownPick): String = shown.reason?.takeIf { it.isNotBlank() } ?: when (val why = scoutWhy(shown.pick, shown.detail, shown.suitsEveryone)) {
    ScoutWhy.SuitsEveryone -> stringResource(R.string.scout_why_everyone)
    is ScoutWhy.Close -> stringResource(R.string.scout_why_close, why.name)
    ScoutWhy.Finished -> stringResource(R.string.scout_why_finished)
    ScoutWhy.Gem -> stringResource(R.string.scout_why_gem)
    ScoutWhy.WellRated -> stringResource(R.string.scout_why_well_rated)
    ScoutWhy.New -> stringResource(R.string.scout_why_new)
}

@Composable
internal fun ScoutPageTitle(
    subtitle: String,
    inset: Dp = FOCUS_ROOM,
    subtitleLines: Int = 1,
    end: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = inset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        Icon(rememberRawSvgPainter(R.raw.sidebar_scout), null, Modifier.size(28.dp), tint = NuvioTheme.colors.Secondary)
        Text(stringResource(R.string.scout_title), style = MaterialTheme.typography.headlineMedium, color = NuvioTheme.colors.TextPrimary, maxLines = 1, modifier = Modifier.alignByBaseline())
        Text(
            text = subtitle,
            style = MaterialTheme.typography.titleMedium.copy(textDirection = subtitle.contentTextDirection()),
            color = NuvioTheme.colors.TextSecondary,
            maxLines = subtitleLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).alignByBaseline()
        )
        end()
    }
}

/** Left and Right stay inside: the end of a row is the end, wherever something else happens to sit further along. */
internal fun Modifier.scoutRowBounds(): Modifier = focusProperties {
    onExit = {
        if (requestedFocusDirection == FocusDirection.Left || requestedFocusDirection == FocusDirection.Right) cancelFocusChange()
    }
}.focusGroup()

@Composable
internal fun ScoutQuestion(label: String, bounded: Boolean = true, chips: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
        FlowRow(
            modifier = if (bounded) Modifier.scoutRowBounds() else Modifier,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) { chips() }
    }
}

@Composable
internal fun ScoutFoot(note: String? = null, hasButtons: Boolean = true, buttons: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (hasButtons) {
            Row(
                modifier = Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()).padding(FOCUS_ROOM),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                content = buttons
            )
        }
        if (note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = FOCUS_ROOM),
                textAlign = TextAlign.End
            )
        }
    }
}

/** One chip for both looks. Being on never changes its size, so nothing around it moves. */
@Composable
internal fun ScoutChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    on: Boolean = false,
    primary: Boolean = false,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
    trailing: (@Composable (focused: Boolean) -> Unit)? = null
) {
    val secondary = NuvioTheme.colors.Secondary
    var focused by remember { mutableStateOf(false) }
    val press = { if (enabled) onClick() }
    val chip = modifier
        .onFocusChanged { focused = it.isFocused }
        .alpha(if (enabled) 1f else .45f)
    if (LocalV2Appearance.current != null) {
        val shape = RoundedCornerShape(NuvioTheme.radii.md)
        val fill = when {
            primary -> secondary
            on -> secondary.copy(alpha = .34f)
            else -> null
        }
        val edge = if (on && !primary) Modifier.border(1.dp, secondary, shape) else Modifier
        NuvioActionPill(onClick = press, modifier = chip.then(edge), stationary = true, containerColor = fill) {
            ScoutChipContent(label, icon, MaterialTheme.typography.labelMedium, if (primary) NuvioTheme.colors.OnSecondary else null)
            trailing?.let {
                Spacer(Modifier.width(8.dp))
                it(focused)
            }
        }
        return
    }
    val shape = RoundedCornerShape(NuvioTheme.radii.full)
    Button(
        onClick = press,
        modifier = chip,
        shape = ButtonDefaults.shape(shape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(
            containerColor = when {
                primary -> secondary
                on -> secondary.copy(alpha = .34f)
                else -> NuvioTheme.colors.TextPrimary.copy(alpha = .08f)
            },
            focusedContainerColor = secondary,
            contentColor = if (primary) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.TextPrimary,
            focusedContentColor = NuvioTheme.colors.OnSecondary
        ),
        border = ButtonDefaults.border(
            border = if (on) Border(androidx.compose.foundation.BorderStroke(1.dp, secondary), shape = shape) else Border.None,
            focusedBorder = Border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
    ) {
        ScoutChipContent(label, icon, MaterialTheme.typography.labelLarge, null)
        trailing?.let {
            Spacer(Modifier.width(8.dp))
            it(focused)
        }
    }
}

@Composable
private fun ScoutChipContent(label: String, icon: (@Composable () -> Unit)?, style: androidx.compose.ui.text.TextStyle, color: Color?) {
    if (icon != null) {
        icon()
        Spacer(Modifier.width(8.dp))
    }
    if (color != null) Text(label, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    else Text(label, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
