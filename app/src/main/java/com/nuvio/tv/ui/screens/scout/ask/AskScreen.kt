@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.scout.ask

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.ui.screens.detail.detailStartInset
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.screens.home.MODERN_LANDSCAPE_LOGO_GRADIENT
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.core.image.sizedHomeArtworkUrl
import com.nuvio.tv.core.scout.ask.AskProvider
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.LocalDimWatchedPosters
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.scout.ScoutChip
import com.nuvio.tv.ui.screens.scout.scoutReadingStyle
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.components.GlassRole
import com.nuvio.tv.ui.v2.components.nuvioGlass
import com.nuvio.tv.ui.v2.components.nuvioV2Focus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SPEECH_PAUSE_MS = 3_000L
private const val READY_FALLBACK_MS = 1_500L
private const val QUIET_CHECK_MS = 250L
private const val MIC_RISE_DB = 1f
private const val QUIET_AFTER_WORDS_MS = 4_500L
private const val QUIET_BEFORE_WORDS_MS = 9_000L
private const val LONGEST_TALK_MS = 90_000L
private const val ASK_WORDS_SHOWN = 100
private const val VOICE_TAG = "AskVoice"
private val FOCUS_ROOM = 16.dp

@Composable
fun AskScreen(
    onOpenTitle: (itemId: String, itemType: String) -> Unit,
    onBack: () -> Unit,
    viewModel: AskViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        viewModel.startPhonePage()
        onDispose { viewModel.stopPhonePage() }
    }
    BackHandler {
        when (state.stage) {
            AskStage.THINKING -> if (viewModel.leavesWithBack) onBack() else viewModel.cancelAsk()
            AskStage.RESULTS -> if (viewModel.leavesWithBack) onBack() else viewModel.backToAsk()
            AskStage.CHOOSE_AI -> if (state.keys.activeConfig != null) viewModel.backToAsk() else onBack()
            AskStage.ASK -> onBack()
        }
    }

    Box(Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) {
        when (state.stage) {
            AskStage.CHOOSE_AI -> ChooseAiContent(state, viewModel)
            AskStage.ASK -> AskContent(state, viewModel)
            AskStage.THINKING -> ThinkingContent(state)
            AskStage.RESULTS -> ResultsContent(state, viewModel, onOpenTitle)
        }
    }
}

@Composable
private fun pagePadding(): PaddingValues = PaddingValues(
    horizontal = (detailStartInset - FOCUS_ROOM).coerceAtLeast(0.dp),
    vertical = (NuvioTheme.spacing.screen.overscanVertical - FOCUS_ROOM).coerceAtLeast(0.dp)
)

@Composable
internal fun Modifier.askGlass(shape: Shape): Modifier =
    if (LocalV2Appearance.current != null) nuvioGlass(GlassRole.CONTROL, focused = false, shape = shape)
    else background(NuvioTheme.colors.BackgroundCard.copy(alpha = .88f), shape)

@Composable
internal fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(NuvioTheme.radii.lg)
    Column(
        modifier = modifier.askGlass(shape).padding(NuvioTheme.spacing.lg),
        verticalArrangement = Arrangement.Center,
        content = content
    )
}

@Composable
internal fun AskButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true
) {
    ScoutChip(label, onClick, modifier, primary = primary, enabled = enabled)
}

/** A focusable surface that keeps still when focused, so its neighbours never count as being below it. */
@Composable
internal fun AskCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(NuvioTheme.radii.md),
    fill: Color = Color.Transparent,
    focusedFill: Color = fill,
    onLongClick: (() -> Unit)? = null,
    onFocused: () -> Unit = {},
    content: @Composable () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val v2 = LocalV2Appearance.current != null
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .then(if (v2) Modifier.nuvioV2Focus(focused, shape, stationary = true) else Modifier),
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = 1f),
        colors = CardDefaults.colors(
            containerColor = fill,
            focusedContainerColor = focusedFill,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContentColor = NuvioTheme.colors.TextPrimary
        ),
        border = CardDefaults.border(
            focusedBorder = if (v2) Border.None else Border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
        )
    ) { content() }
}

@Composable
private fun AskBackdrop(url: String?, heavyScrim: Boolean = false) {
    val background = NuvioTheme.colors.Background
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    Crossfade(targetState = url, animationSpec = tween(450), label = "askBackdrop") { shown ->
        if (shown != null) {
            AsyncImage(
                model = sizedHomeArtworkUrl(shown, 1280),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
    val side = arrayOf(
        0f to background.copy(alpha = .96f),
        .42f to background.copy(alpha = .74f),
        .8f to background.copy(alpha = .12f)
    )
    Box(
        Modifier.fillMaxSize().background(
            if (ltr) Brush.horizontalGradient(*side)
            else Brush.horizontalGradient(*side.map { (at, color) -> (1f - at) to color }.sortedBy { it.first }.toTypedArray())
        )
    )
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                0f to Color.Transparent,
                (if (heavyScrim) .22f else .28f) to Color.Transparent,
                (if (heavyScrim) .5f else .56f) to background.copy(alpha = .78f),
                (if (heavyScrim) .68f else .74f) to background
            )
        )
    )
}

/** Slowly moving posters behind a page. Only the layer moves, so nothing is redrawn while it does. */
@Composable
private fun PosterWall(posters: List<String>, drift: Boolean, scrim: Brush) {
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        if (posters.isNotEmpty()) {
            val tile = maxWidth / 6.6f
            val gap = tile * .09f
            val shift = if (drift) {
                rememberInfiniteTransition(label = "askWall").animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(46_000, easing = LinearEasing), RepeatMode.Reverse),
                    label = "askWallAt"
                )
            } else null
            val travel = with(LocalDensity.current) { tile.toPx() * .45f }
            Row(
                modifier = Modifier
                    .requiredWidth(maxWidth * 1.3f)
                    .requiredHeight(maxHeight * 1.5f)
                    .align(Alignment.Center)
                    .graphicsLayer {
                        rotationZ = -7f
                        translationY = -travel * (shift?.value ?: 0f)
                    },
                horizontalArrangement = Arrangement.spacedBy(gap)
            ) {
                repeat(WALL_COLUMNS) { column ->
                    Column(
                        modifier = Modifier.offset(y = if (column % 2 == 0) 0.dp else -tile * .55f),
                        verticalArrangement = Arrangement.spacedBy(gap)
                    ) {
                        repeat(WALL_ROWS) { row ->
                            AsyncImage(
                                model = sizedHomeArtworkUrl(posters[(column * 5 + row * 3) % posters.size], 342),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .width(tile)
                                    .aspectRatio(2f / 3f)
                                    .clip(RoundedCornerShape(NuvioTheme.radii.md))
                            )
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxSize().background(scrim))
    }
}

private const val WALL_COLUMNS = 8
private const val WALL_ROWS = 5

@Composable
private fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = NuvioTheme.colors.TextSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

@Composable
private fun PhoneCard(state: AskUiState) {
    val shape = RoundedCornerShape(NuvioTheme.radii.lg)
    Row(
        modifier = Modifier.width(300.dp).askGlass(shape).padding(NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        state.phoneQr?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = stringResource(R.string.cd_qr_code),
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(NuvioTheme.radii.sm))
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)) {
            Text(stringResource(R.string.scout_question_phone_title), style = MaterialTheme.typography.labelLarge, color = NuvioTheme.colors.TextPrimary)
            Text(state.phoneError ?: stringResource(R.string.scout_question_phone_hint), style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextSecondary)
        }
    }
}

@Composable
private fun AskContent(state: AskUiState, viewModel: AskViewModel) {
    var backdropAt by rememberSaveable { mutableIntStateOf(0) }
    var askToClear by remember { mutableStateOf(false) }
    var askToRemove by remember { mutableStateOf<AskAsked?>(null) }
    var removedAt by remember { mutableStateOf<Int?>(null) }
    val input = rememberAskInput()
    val requesters = remember { HashMap<AskSpot, FocusRequester>() }
    fun requester(spot: AskSpot): FocusRequester = when (spot) {
        AskSpot.Mic -> input.slots.mic
        AskSpot.Field -> input.slots.field
        else -> requesters.getOrPut(spot) { FocusRequester() }
    }
    val hasMic = input.hasMic
    val asked = state.asked
    val home = if (hasMic) AskSpot.Mic else AskSpot.Field

    fun Modifier.at(spot: AskSpot): Modifier = focusRequester(requester(spot)).focusProperties {
        fun target(move: AskMove): FocusRequester = when {
            input.voice.listening -> FocusRequester.Cancel
            input.slots.heard != null && move == AskMove.UP && spot is AskSpot.Starter -> input.slots.firstChoice
            else -> askFocusNext(spot, move, hasMic, asked.size)?.let { requester(it) } ?: FocusRequester.Cancel
        }
        up = target(AskMove.UP)
        down = target(AskMove.DOWN)
        start = target(AskMove.START)
        end = target(AskMove.END)
    }

    LaunchedEffect(asked) {
        val index = removedAt ?: return@LaunchedEffect
        removedAt = null
        askSpotAfterRemoval(index, asked.size, home)?.let { requester(it).requestFocusAfterFrames() }
    }

    AskBackdrop(state.starters.getOrNull(backdropAt)?.meta?.background)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(pagePadding())
            .verticalScroll(rememberScrollState())
            .padding(FOCUS_ROOM)
    ) {
        AskInputBar(
            input = input,
            onAsk = viewModel::ask,
            next = { spot, move -> askFocusNext(spot, move, hasMic, asked.size)?.let { requester(it) } },
            choicesDown = requester(AskSpot.Starter(0)),
            header = {
                Eyebrow(stringResource(R.string.scout_question_search_line))
                Spacer(Modifier.height(NuvioTheme.spacing.xs))
                Text(
                    stringResource(R.string.scout_mood_question),
                    style = MaterialTheme.typography.displayMedium,
                    color = NuvioTheme.colors.TextPrimary
                )
                Spacer(Modifier.height(NuvioTheme.spacing.md))
            },
            trailing = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                val shape = RoundedCornerShape(NuvioTheme.radii.md)
                AskCard(onClick = viewModel::openChooseAi, modifier = Modifier.askGlass(shape).at(AskSpot.ChangeAi), shape = shape) {
                    Row(
                        modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
                    ) {
                        state.keys.active?.let { ProviderMark(it, 20.dp) }
                        Text(state.providerName, style = MaterialTheme.typography.labelLarge)
                        Icon(Icons.Filled.ArrowDropDown, stringResource(R.string.scout_question_change_ai), Modifier.size(22.dp))
                    }
                }
                PhoneCard(state)
            }
            },
            idleLine = { voiceProblem ->
                val problem = voiceProblem ?: state.failure
                Text(
                    problem ?: stringResource(R.string.scout_question_sent_to, state.providerName),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (problem != null) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary
                )
            }
        )
        Spacer(Modifier.height(NuvioTheme.spacing.lg))
        SectionHeading(stringResource(R.string.scout_question_try_these))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
            state.starters.forEachIndexed { index, starter ->
                StarterCard(
                    starter = starter,
                    onClick = { viewModel.ask(starter.text) },
                    onFocused = { backdropAt = index },
                    modifier = Modifier.weight(1f).at(AskSpot.Starter(index))
                )
            }
        }
        if (asked.isNotEmpty()) {
            Spacer(Modifier.height(NuvioTheme.spacing.lg))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                SectionHeading(stringResource(R.string.scout_question_you_asked), bottom = 0.dp)
                AskButton(stringResource(R.string.scout_question_clear), { askToClear = true }, Modifier.at(AskSpot.Clear))
            }
            Spacer(Modifier.height(NuvioTheme.spacing.sm))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val width = (maxWidth - NuvioTheme.spacing.md * (ASK_STARTERS - 1)) / ASK_STARTERS
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = FOCUS_ROOM / 2),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
                ) {
                    asked.forEachIndexed { index, item ->
                        AskedCard(
                            item = item,
                            onClick = { viewModel.ask(item.question) },
                            onLongClick = { askToRemove = item },
                            modifier = Modifier.width(width).at(AskSpot.Asked(index))
                        )
                    }
                }
            }
        }
    }

    if (askToClear) {
        NuvioDialog(
            onDismiss = { askToClear = false },
            title = stringResource(R.string.scout_question_clear_title),
            subtitle = stringResource(R.string.scout_question_clear_body)
        ) {
            val cancel = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { cancel.requestFocus() } }
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                AskButton(stringResource(R.string.action_cancel), { askToClear = false }, Modifier.focusRequester(cancel))
                AskButton(stringResource(R.string.scout_question_clear), {
                    askToClear = false
                    removedAt = 0
                    viewModel.clearAsked()
                })
            }
        }
    }
    askToRemove?.let { item ->
        NuvioDialog(onDismiss = { askToRemove = null }, title = item.question) {
            val confirm = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { confirm.requestFocus() } }
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                AskButton(stringResource(R.string.scout_question_remove_one), {
                    askToRemove = null
                    removedAt = asked.indexOf(item).coerceAtLeast(0)
                    viewModel.forgetAsked(item.question)
                }, Modifier.focusRequester(confirm), primary = true)
                item.rowId?.let { row ->
                    AskButton(stringResource(R.string.scout_question_remove_row), {
                        askToRemove = null
                        removedAt = asked.indexOf(item).coerceAtLeast(0)
                        viewModel.removeRow(row)
                    })
                }
                AskButton(stringResource(R.string.action_cancel), { askToRemove = null })
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String, bottom: Dp = NuvioTheme.spacing.md) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        color = NuvioTheme.colors.TextPrimary,
        modifier = Modifier.padding(bottom = bottom)
    )
}

@Composable
private fun StarterCard(starter: AskStarter, onClick: () -> Unit, onFocused: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    AskCard(
        onClick = onClick,
        onFocused = onFocused,
        modifier = modifier.aspectRatio(16f / 9f),
        shape = shape,
        fill = NuvioTheme.colors.BackgroundCard,
        focusedFill = NuvioTheme.colors.BackgroundCard
    ) {
        Box(Modifier.fillMaxSize().clip(shape)) {
            starter.meta?.background?.let {
                AsyncImage(
                    model = sizedHomeArtworkUrl(it, 780),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Box(
                Modifier.fillMaxSize().background(MODERN_LANDSCAPE_LOGO_GRADIENT)
            )
            Text(
                starter.text,
                style = MaterialTheme.typography.labelLarge,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(NuvioTheme.spacing.md)
            )
        }
    }
}

@Composable
private fun AskedCard(item: AskAsked, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    AskCard(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.aspectRatio(16f / 9f),
        shape = shape,
        fill = NuvioTheme.colors.BackgroundCard,
        focusedFill = NuvioTheme.colors.BackgroundCard
    ) {
        Box(Modifier.fillMaxSize().clip(shape)) {
            Row(Modifier.fillMaxSize()) {
                item.posters.take(3).forEach { poster ->
                    AsyncImage(
                        model = sizedHomeArtworkUrl(poster, 342),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }
            val veil = NuvioTheme.colors.Background
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.4f to Color.Transparent, .74f to veil.copy(alpha = .86f), 1f to veil.copy(alpha = .96f))))
            Column(
                modifier = Modifier.align(Alignment.BottomStart).padding(NuvioTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
            ) {
                if (item.rowId != null) {
                    Text(stringResource(R.string.scout_question_row_saved), style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextPrimary)
                }
                Text(
                    item.question,
                    style = MaterialTheme.typography.labelLarge,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
internal fun MicButton(listening: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, onFocused: () -> Unit = {}) {
    val secondary = NuvioTheme.colors.Secondary
    val v2 = LocalV2Appearance.current != null
    AskCard(
        onClick = onClick,
        onFocused = onFocused,
        modifier = modifier.then(if (v2) Modifier.nuvioGlass(GlassRole.CONTROL, focused = false, shape = CircleShape) else Modifier),
        shape = CircleShape,
        fill = if (listening) secondary else if (v2) Color.Transparent else NuvioTheme.colors.BackgroundCard,
        focusedFill = secondary
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.fillMaxSize(.52f))
        }
    }
}

@Composable
internal fun QuestionField(value: String, onValueChange: (String) -> Unit, onSubmit: () -> Unit, modifier: Modifier = Modifier, placeholder: String? = null) {
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    val v2 = LocalV2Appearance.current != null
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.take(com.nuvio.tv.core.scout.ask.ASK_MAX_QUESTION)) },
        modifier = modifier
            .fillMaxWidth()
            .nuvioV2Focus(focused, shape, stationary = true)
            .then(if (v2) Modifier.nuvioGlass(GlassRole.CONTROL, focused = focused, shape = shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.keyCode == KeyEvent.KEYCODE_ENTER || native.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    if (native.action == KeyEvent.ACTION_DOWN) {
                        onSubmit()
                        keyboard?.hide()
                    }
                    true
                } else false
            },
        keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = {
            onSubmit()
            keyboard?.hide()
        }),
        singleLine = true,
        shape = shape,
        placeholder = { Text(placeholder ?: stringResource(R.string.scout_question_type_prompt), color = NuvioTheme.colors.TextTertiary) },
        textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content, color = NuvioTheme.colors.TextPrimary),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = if (v2) Color.Transparent else NuvioTheme.colors.FocusRing,
            unfocusedIndicatorColor = if (v2) Color.Transparent else NuvioTheme.colors.Border,
            cursorColor = NuvioTheme.colors.FocusRing
        )
    )
}

internal fun AskProvider.initial(): String? = when (this) {
    AskProvider.GEMINI -> "G"
    AskProvider.CHATGPT -> "O"
    AskProvider.CLAUDE -> "C"
    AskProvider.GROQ, AskProvider.OTHER -> null
}

internal fun AskProvider.tileColor(): Color? = when (this) {
    AskProvider.GEMINI -> Color(0xFF4A5BD0)
    AskProvider.GROQ -> Color(0xFF0E8A8A)
    AskProvider.CHATGPT -> Color(0xFF2F7D4F)
    AskProvider.CLAUDE -> Color(0xFF8A5CD6)
    AskProvider.OTHER -> null
}

@Composable
internal fun ProviderMark(provider: AskProvider, size: Dp) {
    val tile = provider.tileColor()
    if (tile == null) {
        Icon(painterResource(R.drawable.ic_ai_other), null, Modifier.size(size))
        return
    }
    Box(
        Modifier.size(size).background(tile, RoundedCornerShape(NuvioTheme.radii.sm)),
        contentAlignment = Alignment.Center
    ) {
        val letter = provider.initial()
        if (letter == null) {
            Icon(painterResource(R.drawable.ic_ai_groq), null, Modifier.size(size * 0.62f), tint = Color.White)
        } else {
            Text(
                letter,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = (size.value * 0.48f).sp),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ChooseAiContent(state: AskUiState, viewModel: AskViewModel) {
    val requesters = remember { HashMap<AskProvider, FocusRequester>() }
    fun requester(provider: AskProvider) = requesters.getOrPut(provider) { FocusRequester() }
    val test = remember { FocusRequester() }
    val hasKey = state.selected in state.keys.configs
    LaunchedEffect(state.keys.configs.size) { runCatching { requester(state.selected).requestFocus() } }

    val background = NuvioTheme.colors.Background
    PosterWall(
        posters = state.wall,
        drift = false,
        scrim = Brush.horizontalGradient(0f to background.copy(alpha = .97f), .6f to background.copy(alpha = .9f), 1f to background.copy(alpha = .76f))
    )
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(pagePadding())
            .verticalScroll(rememberScrollState())
            .padding(FOCUS_ROOM),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl)
    ) {
        Column(Modifier.weight(1.25f)) {
            Eyebrow(stringResource(R.string.scout_question_search_line))
            Spacer(Modifier.height(NuvioTheme.spacing.xs))
            Text(stringResource(R.string.scout_ai_title), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextPrimary)
            Spacer(Modifier.height(NuvioTheme.spacing.xs))
            Text(stringResource(R.string.scout_ai_stored_lead), style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
            Spacer(Modifier.height(NuvioTheme.spacing.lg))
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)) {
                AskProvider.entries.forEach { provider ->
                    ProviderRow(
                        provider = provider,
                        hasKey = provider in state.keys.configs,
                        inUse = provider == state.keys.active,
                        onClick = { viewModel.selectProvider(provider) },
                        onFocused = { viewModel.lookAtProvider(provider) },
                        modifier = Modifier
                            .focusRequester(requester(provider))
                            .focusProperties {
                                end = if (hasKey) test else FocusRequester.Cancel
                                start = FocusRequester.Cancel
                            }
                    )
                }
            }
        }
        val shape = RoundedCornerShape(NuvioTheme.radii.xl)
        Column(
            modifier = Modifier.weight(1f).askGlass(shape).padding(NuvioTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            state.phoneQr?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = stringResource(R.string.cd_qr_code),
                    modifier = Modifier.size(150.dp).clip(RoundedCornerShape(NuvioTheme.radii.sm))
                )
            }
            Text(
                stringResource(R.string.scout_ai_add_title, if (state.selected == AskProvider.OTHER) stringResource(R.string.scout_ai_other) else state.selected.displayName()),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = NuvioTheme.colors.TextPrimary
            )
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)) {
                listOf(R.string.scout_ai_step_1, R.string.scout_ai_step_2, R.string.scout_ai_step_3).forEachIndexed { index, step ->
                    Text("${index + 1}. ${stringResource(step)}", style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
                }
            }
            state.phoneError?.let { Text(it, style = scoutReadingStyle(), color = NuvioTheme.colors.Error) }
            state.testMessage?.let { message ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
                    if (state.testGood) Icon(Icons.Filled.Check, null, Modifier.size(22.dp), tint = NuvioTheme.colors.Success)
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = if (state.testGood) NuvioTheme.colors.Success else NuvioTheme.colors.Error)
                }
            }
            if (hasKey) {
                Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                    AskButton(
                        stringResource(if (state.testing) R.string.scout_ai_testing else R.string.scout_ai_test),
                        viewModel::testSelected,
                        Modifier.focusRequester(test).focusProperties { start = requester(state.selected) },
                        enabled = !state.testing
                    )
                    AskButton(stringResource(R.string.scout_ai_remove), viewModel::removeSelectedKey)
                }
            }
        }
    }
}

@Composable
private fun ProviderRow(
    provider: AskProvider,
    hasKey: Boolean,
    inUse: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(NuvioTheme.radii.lg)
    val secondary = NuvioTheme.colors.Secondary
    val name = if (provider == AskProvider.OTHER) stringResource(R.string.scout_ai_other) else provider.displayName()
    val sub = stringResource(
        when (provider) {
            AskProvider.GEMINI -> R.string.scout_ai_gemini_line
            AskProvider.GROQ -> R.string.scout_ai_groq_line
            AskProvider.CHATGPT -> R.string.scout_ai_chatgpt_line
            AskProvider.CLAUDE -> R.string.scout_ai_claude_line
            AskProvider.OTHER -> R.string.scout_ai_other_sub
        }
    )
    val badge = stringResource(
        when {
            inUse -> R.string.scout_ai_in_use
            hasKey -> R.string.scout_ai_key_added
            provider == AskProvider.OTHER -> R.string.scout_ai_advanced
            provider.freeTier -> R.string.scout_ai_free
            else -> R.string.scout_ai_paid
        }
    )
    val focusFill = secondary.copy(alpha = .22f)
    AskCard(
        onClick = onClick,
        onFocused = onFocused,
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        focusedFill = focusFill
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
        ) {
            ProviderMark(provider, 36.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)) {
                Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
            }
            if (inUse) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = NuvioTheme.colors.OnSecondary,
                    modifier = Modifier.background(secondary, RoundedCornerShape(NuvioTheme.radii.xl)).padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xxs)
                )
            } else {
                Text(badge, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
            }
        }
    }
}

@Composable
private fun ThinkingContent(state: AskUiState) {
    val background = NuvioTheme.colors.Background
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val stops = arrayOf(0f to background.copy(alpha = .97f), .55f to background.copy(alpha = .84f), 1f to background.copy(alpha = .38f))
    PosterWall(
        posters = state.wall,
        drift = true,
        scrim = if (ltr) Brush.horizontalGradient(*stops) else Brush.horizontalGradient(*stops.map { (at, c) -> (1f - at) to c }.sortedBy { it.first }.toTypedArray())
    )
    Column(
        modifier = Modifier.fillMaxSize().padding(pagePadding()).padding(FOCUS_ROOM),
        verticalArrangement = Arrangement.Center
    ) {
        Eyebrow(stringResource(R.string.scout_question_thinking, state.providerName))
        Spacer(Modifier.height(NuvioTheme.spacing.sm))
        Text(
            state.question,
            style = MaterialTheme.typography.displaySmall.copy(textDirection = TextDirection.Content),
            fontWeight = FontWeight.Bold,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(.72f)
        )
        Spacer(Modifier.height(NuvioTheme.spacing.xl))
        MovingLine()
        Spacer(Modifier.height(NuvioTheme.spacing.sm))
        Text(stringResource(R.string.scout_question_cancel_hint), style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
    }
}

@Composable
private fun MovingLine() {
    val ring = NuvioTheme.colors.FocusRing
    val track = NuvioTheme.colors.TextPrimary.copy(alpha = .14f)
    val moving = rememberInfiniteTransition(label = "askLine")
    val at by moving.animateFloat(
        initialValue = -.34f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_700, easing = LinearEasing)),
        label = "askLineAt"
    )
    Canvas(Modifier.width(280.dp).height(4.dp).clip(RoundedCornerShape(NuvioTheme.radii.xxs))) {
        drawRect(track)
        drawRect(ring, topLeft = Offset(size.width * at, 0f), size = Size(size.width * .34f, size.height))
    }
}

private fun formatRuntime(minutes: String?): String? {
    val total = minutes?.toIntOrNull()?.takeIf { it > 0 } ?: return null
    return if (total >= 60) "${total / 60}h ${total % 60}m" else "${total}m"
}

@Composable
private fun ResultsContent(state: AskUiState, viewModel: AskViewModel, onOpenTitle: (String, String) -> Unit) {
    var focusedId by rememberSaveable { mutableStateOf<String?>(null) }
    val items = state.items
    val focusedIndex = items.indexOfFirst { it.meta.id == focusedId }.coerceAtLeast(0)
    val focusedItem = items.getOrNull(focusedIndex)
    val gridState = rememberLazyGridState()
    val first = remember { FocusRequester() }
    val firstChip = remember { FocusRequester() }
    val againButton = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        gridState.scrollToItem(focusedIndex)
        runCatching { first.requestFocus() }
    }

    var seenPressed by remember { mutableStateOf(false) }
    LaunchedEffect(state.showSeen) {
        if (!seenPressed) return@LaunchedEffect
        seenPressed = false
        val target = if (state.showSeen) items.indexOfFirst { it.seen }.coerceAtLeast(0) else 0
        gridState.animateScrollToItem(target)
    }

    AskBackdrop(focusedItem?.meta?.background, heavyScrim = true)
    Column(Modifier.fillMaxSize().padding(pagePadding()).padding(horizontal = FOCUS_ROOM).padding(top = FOCUS_ROOM)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
            Eyebrow(state.question, Modifier.weight(1f))
            AskButton(
                stringResource(R.string.scout_question_ask_again),
                viewModel::backToAsk,
                Modifier.focusRequester(againButton).focusProperties { down = firstChip }
            )
            AskButton(
                stringResource(if (state.rowSaved) R.string.scout_question_row_saved else R.string.scout_question_save_row),
                viewModel::saveRow,
                Modifier.focusProperties { down = firstChip },
                primary = true,
                enabled = !state.rowSaved
            )
        }
        Spacer(Modifier.height(NuvioTheme.spacing.sm))
        Box(Modifier.height(72.dp), contentAlignment = Alignment.CenterStart) {
            val logo = focusedItem?.meta?.logo
            if (logo != null) {
                AsyncImage(
                    model = logo,
                    contentDescription = focusedItem.meta.name,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                    modifier = Modifier.fillMaxHeight().width(320.dp)
                )
            } else {
                Text(
                    focusedItem?.meta?.name.orEmpty(),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.height(NuvioTheme.spacing.xs))
        val meta = focusedItem?.meta
        val typeWord = stringResource(if (meta?.type == ContentType.SERIES) R.string.type_series else R.string.type_movie)
        Text(
            listOfNotNull(
                typeWord.takeIf { meta != null },
                meta?.releaseInfo,
                formatRuntime(meta?.runtime),
                meta?.genres?.take(3)?.joinToString(", ")?.takeIf(String::isNotBlank)
            ).joinToString("  •  "),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(NuvioTheme.spacing.xs))
        Text(
            state.rowMessage ?: focusedItem?.reason?.ifBlank { meta?.description }.orEmpty(),
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(.62f).heightIn(min = 52.dp)
        )
        Spacer(Modifier.height(NuvioTheme.spacing.sm))
        val intoGrid = if (items.isNotEmpty()) first else FocusRequester.Cancel
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
            listOf(
                R.string.scout_picks_refine_violent, R.string.scout_picks_refine_older,
                R.string.scout_picks_refine_newer, R.string.scout_picks_refine_lighter
            ).forEachIndexed { index, chip ->
                val word = stringResource(chip)
                AskButton(
                    word,
                    { viewModel.ask(state.question + ". " + word) },
                    Modifier
                        .then(if (index == 0) Modifier.focusRequester(firstChip) else Modifier)
                        .focusProperties {
                            up = againButton
                            down = intoGrid
                        }
                )
            }
            if (state.seenCount > 0) {
                Spacer(Modifier.weight(1f))
                ScoutChip(
                    stringResource(R.string.scout_question_show_watched, state.seenCount),
                    {
                        seenPressed = true
                        viewModel.toggleSeen()
                    },
                    Modifier.focusProperties {
                        up = againButton
                        down = intoGrid
                    },
                    on = state.showSeen
                )
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            state = gridState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = NuvioTheme.spacing.md, horizontal = NuvioTheme.spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
        ) {
            itemsIndexed(items, key = { _, item -> item.meta.id }) { index, item ->
                CompositionLocalProvider(LocalDimWatchedPosters provides state.dimWatched) {
                    GridContentCard(
                        item = item.meta,
                        isWatched = item.watched,
                        focusRequester = if (index == focusedIndex) first else null,
                        showReleaseInfo = true,
                        onFocused = { focusedId = item.meta.id },
                        onClick = { onOpenTitle(item.meta.id, item.meta.apiType) }
                    )
                }
            }
        }
    }
}

internal class AskVoice(
    val available: Boolean,
    val listening: Boolean,
    val ready: Boolean,
    val words: String,
    val problem: String?,
    val start: () -> Unit,
    val more: (String) -> Unit,
    val stop: () -> Unit,
    val cancel: () -> Unit,
    val warm: () -> Unit
)

/**
 * [keepGoing] asks for one continuous session where the speech service offers it. Most TV services end
 * at the first pause; the words so far go to [onHeard], and [AskVoice.more] listens again and adds to them.
 */
@Composable
internal fun rememberAskVoice(keepGoing: Boolean = false, onHeard: (String) -> Unit): AskVoice {
    val context = LocalContext.current
    val heard by rememberUpdatedState(onHeard)
    val scope = rememberCoroutineScope()
    val available = remember(context) { SpeechRecognizer.isRecognitionAvailable(context) }
    val recognizer = remember(context, available) {
        if (available) runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull() else null
    }
    val continuous = keepGoing && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val onDevice = remember(context, continuous) {
        if (continuous && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
        } else {
            null
        }
    }
    val preferOnDevice = remember(onDevice) { booleanArrayOf(onDevice != null) }
    val activeRecognizer = { if (preferOnDevice[0]) onDevice else recognizer }
    var listening by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf("") }
    var partial by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    val times = remember { longArrayOf(0L, 0L) }
    val micFloor = remember { floatArrayOf(Float.MAX_VALUE) }
    val jobs = remember { arrayOfNulls<Job>(2) }
    val failedText = stringResource(R.string.scout_question_voice_failed)
    val micText = stringResource(R.string.scout_question_mic_permission)
    val currentWords = { joinAskWords(said, partial) }

    val listenOnce: () -> Unit = {
        micFloor[0] = Float.MAX_VALUE
        Log.d(VOICE_TAG, "listening with ${if (preferOnDevice[0]) "on-device" else "standard"} recogniser, continuous=$continuous")
        runCatching {
            activeRecognizer()?.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SPEECH_PAUSE_MS)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SPEECH_PAUSE_MS)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    if (continuous && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS)
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, LONGEST_TALK_MS)
                    }
                }
            )
        }.onFailure {
            listening = false
            problem = failedText
        }
    }
    val finish: () -> Unit = {
        val text = currentWords().trim()
        listening = false
        ready = false
        runCatching { activeRecognizer()?.cancel() }
        said = ""
        partial = ""
        if (text.isNotEmpty()) { heard(text) } else { problem = failedText }
    }
    val endOfSession: () -> Unit = {
        said = currentWords()
        partial = ""
        Log.d(VOICE_TAG, "session ended after ${SystemClock.elapsedRealtime() - times[0]} ms")
        finish()
    }
    val watchForQuiet: () -> Unit = {
        jobs[1]?.cancel()
        jobs[1] = scope.launch {
            while (listening) {
                delay(QUIET_CHECK_MS)
                val now = SystemClock.elapsedRealtime()
                val stop = askVoiceShouldStop(true, currentWords().isNotBlank(), now - times[1], now - times[0] > LONGEST_TALK_MS)
                if (listening && stop) {
                    Log.d(VOICE_TAG, "stopped after ${now - times[0]} ms, quiet ${now - times[1]} ms")
                    finish()
                }
            }
        }
    }
    val listen: (String) -> Unit = { seed ->
        val now = SystemClock.elapsedRealtime()
        times[0] = now
        times[1] = now
        said = seed
        partial = ""
        problem = null
        ready = false
        jobs[0]?.cancel()
        listening = true
        Log.d(VOICE_TAG, "start")
        listenOnce()
        if (continuous) watchForQuiet()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { listen("") } else { problem = micText }
    }
    DisposableEffect(recognizer, onDevice) {
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(VOICE_TAG, "ready after ${SystemClock.elapsedRealtime() - times[0]} ms")
                jobs[0]?.cancel()
                jobs[0] = scope.launch {
                    delay(READY_FALLBACK_MS)
                    if (listening) ready = true
                }
            }
            override fun onBeginningOfSpeech() {
                Log.d(VOICE_TAG, "speech began after ${SystemClock.elapsedRealtime() - times[0]} ms")
                ready = true
                times[1] = SystemClock.elapsedRealtime()
            }
            override fun onRmsChanged(rmsdB: Float) {
                if (!listening || ready) return
                micFloor[0] = minOf(micFloor[0], rmsdB)
                if (askMicHears(rmsdB, micFloor[0])) {
                    Log.d(VOICE_TAG, "mic level rose after ${SystemClock.elapsedRealtime() - times[0]} ms")
                    ready = true
                }
            }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf(String::isNotBlank)?.let {
                    if (partial.isEmpty()) Log.d(VOICE_TAG, "first words after ${SystemClock.elapsedRealtime() - times[0]} ms")
                    partial = it
                    ready = true
                    times[1] = SystemClock.elapsedRealtime()
                }
            }
            override fun onError(error: Int) {
                Log.d(VOICE_TAG, "error $error")
                if (!listening) return
                if (error == SpeechRecognizer.ERROR_CLIENT) return
                if (preferOnDevice[0] && askOnDeviceGivesUp(error)) {
                    preferOnDevice[0] = false
                    listenOnce()
                    return
                }
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY &&
                    SystemClock.elapsedRealtime() - times[0] < BUSY_GIVE_UP_MS
                ) {
                    scope.launch {
                        delay(BUSY_RETRY_MS)
                        if (listening) listenOnce()
                    }
                    return
                }
                endOfSession()
            }
            override fun onResults(results: Bundle?) {
                if (!listening) return
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf(String::isNotEmpty)?.let {
                    partial = it
                    times[1] = SystemClock.elapsedRealtime()
                }
                endOfSession()
            }
            override fun onSegmentResults(segmentResults: Bundle) {
                if (!listening) return
                segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf(String::isNotEmpty)?.let {
                    Log.d(VOICE_TAG, "segment after ${SystemClock.elapsedRealtime() - times[0]} ms")
                    said = joinAskWords(said, it)
                    partial = ""
                    ready = true
                    times[1] = SystemClock.elapsedRealtime()
                }
            }
            override fun onEndOfSegmentedSession() {
                Log.d(VOICE_TAG, "continuous session ended after ${SystemClock.elapsedRealtime() - times[0]} ms")
                if (listening) endOfSession()
            }
        }
        recognizer?.setRecognitionListener(listener)
        onDevice?.setRecognitionListener(listener)
        runCatching { activeRecognizer()?.cancel() }
        onDispose {
            recognizer?.setRecognitionListener(null)
            recognizer?.destroy()
            onDevice?.setRecognitionListener(null)
            onDevice?.destroy()
        }
    }
    return AskVoice(
        available = available && recognizer != null,
        listening = listening,
        ready = ready,
        words = currentWords(),
        problem = problem,
        start = {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (granted) listen("") else permission.launch(Manifest.permission.RECORD_AUDIO)
        },
        more = { seed -> listen(seed) },
        stop = { if (listening) finish() },
        cancel = {
            listening = false
            ready = false
            said = ""
            partial = ""
            runCatching { activeRecognizer()?.cancel() }
        },
        warm = { if (!listening) runCatching { activeRecognizer()?.cancel() } }
    )
}

private const val BUSY_RETRY_MS = 300L
private const val BUSY_GIVE_UP_MS = 6_000L

internal fun askMicHears(rmsdB: Float, floor: Float): Boolean = rmsdB >= floor + MIC_RISE_DB

internal fun askOnDeviceGivesUp(error: Int): Boolean = when (error) {
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
    SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
    SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT,
    SpeechRecognizer.ERROR_SERVER -> true
    else -> false
}

internal fun askWordsTail(words: String, shown: Int = ASK_WORDS_SHOWN): String =
    if (words.length <= shown) words else "…" + words.takeLast(shown).substringAfter(' ')

internal fun joinAskWords(said: String, partial: String): String =
    listOf(said, partial).filter(String::isNotBlank).joinToString(" ")

internal fun askVoiceShouldStop(keepGoing: Boolean, hasWords: Boolean, quietMs: Long, tooLong: Boolean): Boolean = when {
    !keepGoing || tooLong -> true
    hasWords -> quietMs >= QUIET_AFTER_WORDS_MS
    else -> quietMs >= QUIET_BEFORE_WORDS_MS
}
