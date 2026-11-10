package com.nuvio.tv.ui.screens.scout

import androidx.lifecycle.ViewModel
import com.nuvio.tv.core.scout.ScoutEngine
import com.nuvio.tv.domain.model.ScoutPick
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ScoutNavViewModel @Inject constructor(
    private val engine: ScoutEngine
) : ViewModel() {
    fun surprisePick(): ScoutPick? = engine.pool.value?.picked?.take(SURPRISE_FROM)?.randomOrNull()

    private companion object {
        const val SURPRISE_FROM = 60
    }
}
