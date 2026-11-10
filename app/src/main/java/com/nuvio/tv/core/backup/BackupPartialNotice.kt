package com.nuvio.tv.core.backup

import com.nuvio.tv.core.runtime.AppRestarter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Holds the restart after a partly failed restore until the viewer has read the warning. Outlives a rebuilt screen. */
@Singleton
class BackupPartialNotice @Inject constructor(
    private val restarter: AppRestarter,
    private val signInOffer: BackupSignInOffer
) {
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    private var offerSignIn = false

    fun show(offerSignIn: Boolean) {
        this.offerSignIn = offerSignIn
        _visible.value = true
    }

    /** Returns only if Arc could not restart itself. */
    fun finish(): Boolean {
        _visible.value = false
        if (offerSignIn) signInOffer.arm()
        return restarter.restart()
    }
}
