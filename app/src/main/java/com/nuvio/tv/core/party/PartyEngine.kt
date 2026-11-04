package com.nuvio.tv.core.party

import kotlinx.coroutines.flow.StateFlow

/** What the player and the party screens need from a party, whichever kind it is. Called on the main thread. */
interface PartyEngine {
    val state: StateFlow<PartyState>

    /** The title a guest should open. The app navigates there, then calls [consumeMediaRequest]. */
    val mediaRequest: StateFlow<PartyMedia?>

    /** What the party is watching, for the guest's stream picker. */
    val partyMedia: PartyMedia?

    val attachedMedia: PartyMedia?

    fun consumeMediaRequest()

    /** Guest only: opens what the party is watching again, for a guest who wandered off. */
    fun requestPartyTitle()

    fun leaveParty()

    /** Host only: closes the room for everyone. */
    fun endParty()

    fun acknowledgeEnded()

    fun setGuestsControl(enabled: Boolean)

    /** Host only: stops waiting for a member who is still buffering. */
    fun continueWithoutWaiting()

    fun attachPlayer(player: PartyPlayer, media: PartyMedia)

    fun detachPlayer(player: PartyPlayer)

    /** The person at this device is about to move the position. */
    fun noteLocalSeek()

    fun tick()
}
