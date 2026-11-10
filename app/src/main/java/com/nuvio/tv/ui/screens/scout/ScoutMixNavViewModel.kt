package com.nuvio.tv.ui.screens.scout

import androidx.lifecycle.ViewModel
import com.nuvio.tv.core.player.ScoutMixReturn
import com.nuvio.tv.core.player.ScoutMixSession
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Lets the navigation graph ask whether the player is playing for a mix. */
@HiltViewModel
class ScoutMixNavViewModel @Inject constructor(private val session: ScoutMixSession) : ViewModel() {
    fun playbackEnded(contentId: String?): Boolean = session.returnFromPlayer(contentId, ScoutMixReturn.ENDED)

    fun nextChosen(contentId: String?): Boolean = session.returnFromPlayer(contentId, ScoutMixReturn.NEXT)

    fun backPressed(contentId: String?, watched: Boolean): Boolean =
        session.returnFromPlayer(contentId, if (watched) ScoutMixReturn.WATCHED else ScoutMixReturn.LEFT)
}
