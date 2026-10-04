package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.data.local.*

/** Immutable preview; only explicit Save writes the captured store revision/profile. */
internal data class PlayerControlLayoutDraft(
    val snapshot: PlayerControlLayoutSnapshot,
    val layout: PlayerControlLayout? = snapshot.layout,
    val rejected: Boolean = false,
    val previewDefault: PlayerControlLayout = PlayerControlLayout.default()
) {
    val preview: PlayerControlLayout get() = layout ?: previewDefault
    fun visibility(action: PlayerControlAction, visible: Boolean) = copy(layout = preview.withVisibility(action, visible))
    fun group(action: PlayerControlAction, group: PlayerControlGroup) = copy(layout = preview.withGroup(action, group))
    fun move(action: PlayerControlAction, delta: Int): PlayerControlLayoutDraft {
        val moved = preview.move(action, delta)
        return if (moved == preview) this else copy(layout = moved)
    }
    fun style(style: PlayerControlButtonStyle) = copy(layout = preview.withStyle(style))
    fun reset() = copy(layout = null)
    fun reject() = copy(rejected = true)
    fun belongsTo(profile: Int) = snapshot.profileId == profile
}
