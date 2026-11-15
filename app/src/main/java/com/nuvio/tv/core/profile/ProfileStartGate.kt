package com.nuvio.tv.core.profile

internal object ProfileStartGate {

    /** Null while the saved PIN states have not been read yet. Signing out removes every PIN. */
    fun hasPin(signedOut: Boolean, savedPinStates: Map<Int, Boolean>?, profileId: Int): Boolean? =
        if (signedOut) false else savedPinStates?.let { it[profileId] == true }

    /**
     * The profile list at start: more than one profile, a PIN on the shown one, or asked for. [hasPin] is the real PIN
     * state; a relaxed PIN choice only lets the remembered profile open by itself, which happens before this is read.
     */
    fun showPicker(selectedThisSession: Boolean, profileCount: Int, hasPin: Boolean?, pickerRequested: Boolean): Boolean =
        !selectedThisSession && (profileCount > 1 || hasPin == true || pickerRequested)

    /** Whether the PIN is asked when Arc starts; null while the PIN state is not known yet. */
    fun asksAtStart(hasPin: Boolean?, ask: PinAsk?): Boolean? = (ask ?: PinAsk.EVERY_TIME).atStart(hasPin)

    /** [savedHasPin] is for the profile id as saved on disk, which the shown one can lag behind at start. */
    fun canAutoSelect(
        rememberLastProfile: Boolean,
        hasEverSelectedProfile: Boolean,
        shownHasPin: Boolean?,
        savedHasPin: Boolean?
    ): Boolean = rememberLastProfile && hasEverSelectedProfile && shownHasPin == false && savedHasPin == false
}
