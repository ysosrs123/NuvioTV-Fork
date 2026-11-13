package com.nuvio.tv.core.player

import javax.inject.Inject
import javax.inject.Singleton

enum class ScoutMixReturn {
    /** Left before the episode counted as watched, or never reached the player. */
    LEFT,
    /** Backed out after the watched mark: the pick has been seen, but nothing should start by itself. */
    WATCHED,
    /** Ran to its end: the mix may carry on. */
    ENDED,
    /** The player's next-episode card counted down: the mix plays its next pick at once. */
    NEXT,
    /** The same, but someone pressed it, so the viewer is still there. */
    NEXT_PRESSED,
    /** Every source was asked and none had a link: the mix tries another episode. */
    NO_SOURCES
}

/** What the player offers as the mix's next episode. */
data class ScoutMixUpNext(
    val mixName: String,
    val showName: String,
    val contentId: String,
    val videoId: String,
    val season: Int?,
    val episode: Int?,
    val title: String,
    val thumbnail: String?,
    val startsByItself: Boolean
)

/** The episode a mix is playing right now. Lasts as long as the app does. */
@Singleton
class ScoutMixSession @Inject constructor() {
    private data class Playing(val profileId: Int, val mixId: String, val contentId: String, val videoId: String)

    @Volatile private var playing: Playing? = null
    @Volatile private var outcome = ScoutMixReturn.LEFT
    @Volatile private var upNext: ScoutMixUpNext? = null
    @Volatile private var nextPressed = false

    val active: Boolean get() = playing != null

    fun start(profileId: Int, mixId: String, contentId: String, videoId: String) {
        playing = Playing(profileId, mixId, contentId, videoId)
        outcome = ScoutMixReturn.LEFT
        upNext = null
        nextPressed = false
    }

    fun pressedNext(profileId: Int, contentId: String?, videoId: String?) {
        if (matches(profileId, contentId, videoId)) nextPressed = true
    }

    /** Kept only for the episode it was chosen after. */
    fun offerNext(afterVideoId: String, next: ScoutMixUpNext) {
        if (playing?.videoId == afterVideoId) upNext = next
    }

    fun upNextFor(profileId: Int, contentId: String?, videoId: String?): ScoutMixUpNext? =
        upNext?.takeIf { matches(profileId, contentId, videoId) }

    fun matches(profileId: Int, contentId: String?, videoId: String?): Boolean =
        playing?.let { it.profileId == profileId && it.contentId == contentId && it.videoId == videoId } == true

    /** The sources page found no link for the episode the mix is playing. False when no mix is playing it. */
    fun noSources(contentId: String?, videoId: String?): Boolean {
        val now = playing ?: return false
        if (now.contentId != contentId || now.videoId != videoId) return false
        outcome = ScoutMixReturn.NO_SOURCES
        return true
    }

    /** Tells the mix page how the player closed. False when no mix is playing this show. */
    fun returnFromPlayer(contentId: String?, outcome: ScoutMixReturn): Boolean {
        if (contentId == null || playing?.contentId != contentId) return false
        this.outcome = outcome
        return true
    }

    /** How the episode went, once. Whichever way back, the session is over. */
    fun finish(): ScoutMixReturn {
        val result = when {
            playing == null -> ScoutMixReturn.LEFT
            outcome == ScoutMixReturn.NEXT && nextPressed -> ScoutMixReturn.NEXT_PRESSED
            else -> outcome
        }
        playing = null
        outcome = ScoutMixReturn.LEFT
        upNext = null
        nextPressed = false
        return result
    }
}
