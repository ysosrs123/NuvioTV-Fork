package com.nuvio.tv.core.party

/** The player as the party sees it. Called on the main thread only. */
interface PartyPlayer {
    val positionMs: Long
    val durationMs: Long
    val wantsToPlay: Boolean
    val isBuffering: Boolean

    /** False while bitstream audio, tunnelling or the native video path is active: the party then never changes speed. */
    val speedAllowed: Boolean

    /** True while the app is in the background. Being away is not a pause for the room. */
    val isAway: Boolean get() = false

    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float)
}

enum class PartyRole { HOST, GUEST }

enum class PartyStatus { IDLE, CONNECTING, ACTIVE, RECONNECTING }

enum class PartyMemberStatus { IN_SYNC, SYNCING, BUFFERING, CATCHING_UP, AWAY }

data class PartyMemberView(
    val id: String,
    val name: String,
    val isHost: Boolean,
    val isSelf: Boolean,
    val status: PartyMemberStatus,
    val avatarUrl: String? = null,
    val colour: String? = null,
    /** Set once the member uses friends; matches [PartyFriend.key]. */
    val friendKey: String? = null,
)

data class PartyHoldView(
    val reason: PartyHoldReason,
    val memberName: String?,
    val isSelf: Boolean,
    val secondsLeft: Int,
)

data class PartyState(
    val status: PartyStatus = PartyStatus.IDLE,
    val role: PartyRole? = null,
    val code: String? = null,
    val members: List<PartyMemberView> = emptyList(),
    val hostPresent: Boolean = false,
    val guestsControl: Boolean = true,
    val shareLink: Boolean = false,
    val hold: PartyHoldView? = null,
    val driftMs: Long? = null,
    val selfPaused: Boolean = false,
    val catchingUp: Boolean = false,
    val playingPartyTitle: Boolean = false,
    val durationMismatchMs: Long? = null,
    val problem: PartyTransportProblem? = null,
    val endedByHost: Boolean = false,
    /** Goes up each time the host changes while this device is in the party. */
    val hostChanges: Int = 0,
    /** Members this device asked to add as a friend and who have not answered yet. */
    val friendAsked: Set<String> = emptySet(),
) {
    val isActive: Boolean get() = status != PartyStatus.IDLE
}
