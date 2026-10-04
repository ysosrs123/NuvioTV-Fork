@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.nuvio.tv.ui.screens.settings

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.unit.IntRect
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.nuvio.tv.R
import com.nuvio.tv.data.local.*
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.player.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Render-only player preview: no player, media session or network work is created here. */
@Composable
internal fun PlayerControlLayoutEditor(
    snapshot: PlayerControlLayoutSnapshot,
    currentProfile: Int?,
    save: suspend (PlayerControlLayoutSnapshot, PlayerControlLayout?) -> Boolean,
    onDismiss: () -> Unit
) {
    val v2 = LocalV2Appearance.current != null
    val referenceWidth = LocalWindowInfo.current.containerSize.width
    val activityDensity = LocalDensity.current
    val canvasHeight = with(activityDensity) { LocalWindowInfo.current.containerSize.height.toDp() }
    val footerHeight = (canvasHeight * .3f).coerceIn(56.dp, 192.dp)
    var editor by remember { mutableStateOf(PlayerControlVisualEditorState(PlayerControlLayoutDraft(snapshot,
        previewDefault = PlayerControlLayout.original(v2)))) }
    var previewEpisode by remember { mutableStateOf(false) }
    var moreExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(editor.canPreviewMore) { if (!editor.canPreviewMore) moreExpanded = false }
    var popupBounds by remember { mutableStateOf<IntRect?>(null) }
    val previewAvailable = PlayerControlAction.entries.toSet().let { if (previewEpisode) it else it - setOf(PlayerControlAction.EPISODES, PlayerControlAction.NEXT_EPISODE) }
    var deckPlan by remember { mutableStateOf(PlayerControlDeckPlan(emptyList(), 0, 0, 0, null)) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val targets = remember { PlayerControlAction.entries.associateWith { FocusRequester() } }
    val cancelFocus = remember { FocusRequester() }
    val saveFocus = remember { FocusRequester() }
    val moveFocus = remember { FocusRequester() }
    var focusRequest by remember { mutableIntStateOf(0) }
    val gesture = remember { PlayerLayoutConfirmGesture() }
    var pressEpoch by remember { mutableIntStateOf(0) }
    fun cancelPress() { gesture.cancel(); pressEpoch++ }
    fun confirm(result: PlayerLayoutConfirmResult) {
        if (saving || editor.draft.rejected) return
        when (result) {
            PlayerLayoutConfirmResult.CLICK -> editor = if (editor.moving) editor.place() else editor.toggle()
            PlayerLayoutConfirmResult.HOLD -> if (!editor.moving) editor = editor.beginMove()
            PlayerLayoutConfirmResult.NONE -> Unit
        }
    }
    fun dismiss() {
        if (saving) return
        cancelPress()
        if (editor.moving) { editor = editor.cancelMove() } else onDismiss()
    }
    LaunchedEffect(currentProfile) {
        if (currentProfile != null && !editor.draft.belongsTo(currentProfile)) onDismiss()
    }
    LaunchedEffect(focusRequest) {
        withFrameNanos { }; withFrameNanos { }
        if (runCatching { targets.getValue(editor.selected).requestFocus() }.getOrNull() != true)
            runCatching { cancelFocus.requestFocus() }
    }
    LaunchedEffect(editor.draft.rejected) {
        if (editor.draft.rejected) { cancelPress(); withFrameNanos { }; runCatching { cancelFocus.requestFocus() } }
    }
    LaunchedEffect(pressEpoch) {
        if (gesture.pressed) { delay(550L); confirm(gesture.timeout(SystemClock.uptimeMillis())) }
    }
    DisposableEffect(Unit) { onDispose { gesture.cancel() } }

    NuvioDialog(onDismiss = ::dismiss, title = stringResource(R.string.player_layout_title),
        subtitle = stringResource(R.string.player_layout_visual_help), width = 1080.dp,
        contentPadding = 16.dp, contentSpacing = 8.dp, usePlatformDefaultWidth = false) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (!editor.moving) false
                else when (native.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (native.action == KeyEvent.ACTION_DOWN) {
                            cancelPress()
                            editor = editor.step(if (native.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (native.action == KeyEvent.ACTION_DOWN) {
                            cancelPress()
                            editor = editor.vertical(deckPlan.neighbour(editor.selected,
                                if (native.keyCode == KeyEvent.KEYCODE_DPAD_UP) PlayerControlDirection.UP else PlayerControlDirection.DOWN))
                        }
                        true
                    }
                    KeyEvent.KEYCODE_BACK -> { if (native.action == KeyEvent.ACTION_UP) dismiss(); true }
                    else -> false
                }
            }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = minOf(330.dp, canvasHeight * .45f))
                    .background(Brush.verticalGradient(listOf(Color(0xff10151c), Color.Black)), RoundedCornerShape(12.dp))
                    .onGloballyPositioned {
                        val r = it.boundsInWindow()
                        popupBounds = IntRect(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt())
                    }) {
                    CompositionLocalProvider(LocalDensity provides activityDensity) {
                    ScaledPlayerControlPreview(referenceWidth, Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), popupBounds = popupBounds) {
                        PlayerControlChrome(v2,
                            title = {
                                Text(stringResource(R.string.player_layout_preview_title), color = Color.White,
                                    style = MaterialTheme.typography.titleLarge, maxLines = 1)
                                Text("2026", color = Color.White, style = MaterialTheme.typography.bodySmall)
                            }, timeline = {
                                Box(Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.CenterStart) {
                                    Box(Modifier.fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = .25f)))
                                    Box(Modifier.fillMaxWidth(.35f).height(4.dp).background(Color.White))
                                }
                            }, time = {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("00:35:00", color = Color.White, style = MaterialTheme.typography.bodySmall)
                                    Text("-01:05:00", color = Color.White, style = MaterialTheme.typography.bodySmall)
                                }
                            }, deck = {
                                PlayerControlDeck(editor.draft.preview, previewAvailable, targets::getValue,
                                    preview = true, enabled = !saving && !editor.draft.rejected,
                                    moving = editor.selected.takeIf { editor.moving }, moreExpanded = moreExpanded, popupSelected = editor.selected, upFocus = if (editor.canMove) moveFocus else saveFocus,
                                    onBottom = { if (!editor.moving) runCatching { saveFocus.requestFocus() } },
                                    onClick = { action -> editor = editor.select(action); confirm(PlayerLayoutConfirmResult.CLICK) },
                                    onFocused = { editor = editor.select(it) },
                                    onFocusLost = { if (editor.selected == it) cancelPress() },
                                    onPlan = { deckPlan = it },
                                    onSelectKey = { action, native ->
                                        if (native.action == KeyEvent.ACTION_DOWN) {
                                            if (!saving && !editor.draft.rejected && native.repeatCount == 0 && !gesture.pressed) {
                                                editor = editor.select(action); gesture.down(native.eventTime); pressEpoch++
                                            }
                                        } else if (native.action == KeyEvent.ACTION_UP) {
                                            if (native.isCanceled) cancelPress() else { confirm(gesture.up(native.eventTime)); pressEpoch++ }
                                        }
                                        true
                                    })
                            })
                    }
                    }
                }
                val selected = editor.draft.preview.entries.single { it.action == editor.selected }
                Text(stringResource(R.string.player_layout_selected_group, playerControlLabel(editor.selected),
                    stringResource(if (!selected.visible) R.string.player_layout_hidden else if (selected.action !in previewAvailable) R.string.player_layout_preview_unavailable else if (selected.action in deckPlan.collapsedActions) R.string.player_layout_in_more else R.string.player_layout_shown)),  
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(if (editor.moving) R.string.player_layout_move_help else if (!editor.canPreviewMore)
                    R.string.player_layout_more_hidden_help else if (editor.selected == PlayerControlAction.MORE)
                    R.string.player_layout_more_fixed_help else if (editor.draft.layout == null)
                    R.string.player_layout_defaults_help else R.string.player_layout_idle_help),
                    style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
        }
        if (editor.draft.rejected) Text(stringResource(R.string.player_layout_stale), style = MaterialTheme.typography.bodySmall)
        if (failed) Text(stringResource(R.string.player_layout_save_failed), style = MaterialTheme.typography.bodySmall)
        FlowRow(Modifier.fillMaxWidth().heightIn(max = footerHeight).verticalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (!saving && !editor.draft.rejected && !editor.moving) {
                    cancelPress(); saving = true; failed = false
                    val captured = editor.draft
                    scope.launch {
                        try {
                            if (save(captured.snapshot, captured.layout)) onDismiss() else editor = editor.reject()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { failed = true }
                        finally { saving = false }
                    }
                }
            }, enabled = !editor.draft.rejected && !editor.moving, modifier = Modifier.focusRequester(saveFocus)) {
                Text(stringResource(if (saving) R.string.player_layout_saving else R.string.action_save))
            }
            Button(onClick = ::dismiss, enabled = !saving, modifier = Modifier.focusRequester(cancelFocus)) {
                Text(stringResource(R.string.action_cancel))
            }
            Button(onClick = { cancelPress(); editor = editor.reset() }, enabled = !saving && !editor.draft.rejected && !editor.moving) {
                Text(stringResource(R.string.player_layout_reset), maxLines = 1)
            }
            Button(onClick = { cancelPress(); editor = editor.beginMove(); focusRequest++ },
                enabled = !saving && editor.canMove, modifier = Modifier.focusRequester(moveFocus)) {
                Text(stringResource(R.string.player_layout_move), maxLines = 1)
            }
            Button(onClick = { cancelPress(); editor = editor.style(PlayerControlButtonStyle.entries[
                (editor.draft.preview.style.ordinal + 1) % PlayerControlButtonStyle.entries.size]) },
                enabled = !saving && !editor.draft.rejected && !editor.moving) {
                Text(stringResource(R.string.player_layout_button_style, stringResource(
                    if (editor.draft.preview.style == PlayerControlButtonStyle.PREVIOUS) R.string.player_layout_style_previous
                    else R.string.player_layout_style_labelled)), maxLines = 1)
            }
            Button(onClick = { cancelPress(); moreExpanded = !moreExpanded }, enabled = !saving && !editor.draft.rejected && !editor.moving && editor.canPreviewMore) {
                Text(stringResource(if (moreExpanded) R.string.player_layout_more_expanded else R.string.player_layout_more_collapsed), maxLines = 1)
            }
            Button(onClick = { cancelPress(); previewEpisode = !previewEpisode }, enabled = !saving && !editor.draft.rejected && !editor.moving) {
                Text(stringResource(if (previewEpisode) R.string.player_layout_preview_episode else R.string.player_layout_preview_movie), maxLines = 1)
            }
        }
    }
}

