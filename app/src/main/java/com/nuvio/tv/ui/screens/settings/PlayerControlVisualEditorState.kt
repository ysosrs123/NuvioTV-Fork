package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.data.local.*

/** Move is a transaction inside the unsaved draft; Back restores its exact starting value. */
internal data class PlayerControlVisualEditorState(
    val draft: PlayerControlLayoutDraft,
    val selected: PlayerControlAction = PlayerControlAction.PLAY_PAUSE,
    private val moveOrigin: PlayerControlLayoutDraft? = null
) {
    val moving: Boolean get() = moveOrigin != null
    fun select(action: PlayerControlAction) = if (moving) this else copy(selected = action)
    fun toggle() = if (moving || draft.rejected) this else copy(draft = draft.visibility(selected,
        !draft.preview.entries.single { it.action == selected }.visible))
    val canMove: Boolean get() = !moving && !draft.rejected && selected != PlayerControlAction.MORE
    val canPreviewMore: Boolean get() = draft.preview.entries.single { it.action == PlayerControlAction.MORE }.visible
    fun beginMove() = if (!canMove) this else copy(moveOrigin = draft)
    fun place() = copy(moveOrigin = null)
    fun cancelMove() = moveOrigin?.let { copy(draft = it, moveOrigin = null) } ?: this
    fun style(style: PlayerControlButtonStyle) = if (moving || draft.rejected) this else copy(draft = draft.style(style))
    fun vertical(target: PlayerControlAction?): PlayerControlVisualEditorState {
        if (!moving || target == null || target == selected || draft.rejected) return this
        val entry = draft.preview.entries.single { it.action == selected }
        val siblings = draft.preview.entries.filter { it.group == entry.group }.map { it.action }
        val from = siblings.indexOf(selected); val to = siblings.indexOf(target)
        if (to < 0) return this
        var changed = draft
        repeat(kotlin.math.abs(to - from)) { changed = changed.move(selected, if (to > from) 1 else -1) }
        return copy(draft = changed)
    }
    fun reset() = if (draft.rejected) this else copy(draft = draft.reset(), moveOrigin = null)
    fun reject() = copy(draft = draft.reject(), moveOrigin = null)

    /** Arrows swap neighbours, then cross a group boundary at its edge, including empty groups. */
    fun step(delta: Int): PlayerControlVisualEditorState {
        if (!moving || draft.rejected || delta !in listOf(-1, 1)) return this
        val entry = draft.preview.entries.single { it.action == selected }
        val siblings = draft.preview.entries.filter { it.group == entry.group }
        val index = siblings.indexOfFirst { it.action == selected }
        if (index + delta in siblings.indices) return copy(draft = draft.move(selected, delta))
        val next = PlayerControlGroup.entries.getOrNull(entry.group.ordinal + delta) ?: return this
        var changed = draft.group(selected, next)
        // Crossing right enters at the start; crossing left enters at the end.
        if (delta > 0) repeat(changed.preview.entries.count { it.group == next } - 1) {
            changed = changed.move(selected, -1)
        }
        return copy(draft = changed)
    }
}

internal enum class PlayerLayoutConfirmResult { NONE, CLICK, HOLD }

/** Uses the initial down time, not repeat events; a held key fires exactly once. */
internal class PlayerLayoutConfirmGesture(private val holdMillis: Long = 550L) {
    private var downTime: Long? = null
    private var held = false
    val pressed: Boolean get() = downTime != null
    fun down(time: Long) { if (downTime == null) { downTime = time; held = false } }
    fun timeout(time: Long): PlayerLayoutConfirmResult {
        val start = downTime ?: return PlayerLayoutConfirmResult.NONE
        if (held || time - start < holdMillis) return PlayerLayoutConfirmResult.NONE
        held = true
        return PlayerLayoutConfirmResult.HOLD
    }
    fun up(time: Long): PlayerLayoutConfirmResult {
        val start = downTime ?: return PlayerLayoutConfirmResult.NONE
        val result = when {
            held -> PlayerLayoutConfirmResult.NONE
            time - start >= holdMillis -> PlayerLayoutConfirmResult.HOLD
            else -> PlayerLayoutConfirmResult.CLICK
        }
        cancel()
        return result
    }
    fun cancel() { downTime = null; held = false }
}
