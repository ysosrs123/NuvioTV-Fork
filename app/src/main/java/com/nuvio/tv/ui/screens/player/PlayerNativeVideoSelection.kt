package com.nuvio.tv.ui.screens.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Selected renderer lifecycle, independent of factory eligibility or late decoder labels. */
internal class PlayerNativeVideoSelection {
    private var owner: Any? = null
    private val selected = MutableStateFlow(false)
    val changes: StateFlow<Boolean> = selected.asStateFlow()
    val isSelected: Boolean get() = selected.value
    @Synchronized fun activate(newOwner: Any) { owner = newOwner; selected.value = false }
    @Synchronized fun update(sourceOwner: Any, enabled: Boolean) {
        if (owner === sourceOwner) selected.value = enabled
    }
    @Synchronized fun clear() { owner = null; selected.value = false }
}
