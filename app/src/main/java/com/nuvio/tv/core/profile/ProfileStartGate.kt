package com.nuvio.tv.core.profile

internal object ProfileStartGate {

    /** Null while the saved PIN states have not been read yet. Signing out removes every PIN. */
    fun hasPin(signedOut: Boolean, savedPinStates: Map<Int, Boolean>?, profileId: Int): Boolean? =
        if (signedOut) false else savedPinStates?.let { it[profileId] == true }

    /** [savedHasPin] is for the profile id as saved on disk, which the shown one can lag behind at start. */
    fun canAutoSelect(
        rememberLastProfile: Boolean,
        hasEverSelectedProfile: Boolean,
        shownHasPin: Boolean?,
        savedHasPin: Boolean?
    ): Boolean = rememberLastProfile && hasEverSelectedProfile && shownHasPin == false && savedHasPin == false
}
