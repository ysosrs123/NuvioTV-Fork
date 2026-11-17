package com.nuvio.tv.ui.scout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.ScoutStore
import com.nuvio.tv.domain.model.ScoutAskTarget
import com.nuvio.tv.domain.model.ScoutProfile
import com.nuvio.tv.domain.model.ScoutRating
import com.nuvio.tv.domain.model.ScoutSettings
import com.nuvio.tv.domain.model.ScoutTitle
import com.nuvio.tv.domain.model.scoutShouldAsk
import dagger.hilt.android.lifecycle.HiltViewModel
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingProviderRegistry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ScoutRatingViewModel @Inject constructor(
    private val store: ScoutStore,
    private val resolver: com.nuvio.tv.core.tmdb.TmdbCollectionSourceResolver,
    private val mixSession: com.nuvio.tv.core.player.ScoutMixSession = com.nuvio.tv.core.player.ScoutMixSession(),
    private val trackers: TrackingProviderRegistry,
    private val titleIdentity: com.nuvio.tv.ui.components.posteroptions.PosterTitleIdentity
) : ViewModel() {
    val profile: StateFlow<ScoutProfile> = store.profile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScoutProfile())

    /** Whether this profile is signed in to a tracker that takes ratings. */
    val trackerSignedIn: StateFlow<Boolean> = trackers.providers()
        .filter { it.descriptor.id != TrackingProviderId.FLOPPY }
        .map { it.isAuthenticated }
        .let { flows -> if (flows.isEmpty()) flowOf(false) else combine(flows) { states -> states.any { it } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val pendingAsk: StateFlow<ScoutAskTarget?> = store.pendingAsk

    fun setRating(title: ScoutTitle, rating: ScoutRating?) {
        viewModelScope.launch { store.setRating(title, rating) }
    }

    /** The title a poster's thumb is saved under: the one its details page rates. */
    suspend fun savedTitle(title: ScoutTitle, addonBaseUrl: String?): ScoutTitle {
        val preview = com.nuvio.tv.domain.model.MetaPreview(
            id = title.id,
            type = com.nuvio.tv.domain.model.ContentType.fromString(title.type),
            rawType = title.type,
            name = title.name,
            poster = title.poster,
            posterShape = com.nuvio.tv.domain.model.PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList()
        )
        val saved = runCatching { titleIdentity.resolve(preview, addonBaseUrl) }.getOrDefault(preview)
        return if (saved.id == title.id && saved.apiType == title.type) title else title.copy(id = saved.id, type = saved.apiType)
    }

    fun setRatingMerging(saved: ScoutTitle, aliases: Set<String>, rating: ScoutRating?) {
        viewModelScope.launch { store.setRating(saved, rating, aliases = aliases) }
    }

    fun updateSettings(transform: (ScoutSettings) -> ScoutSettings) {
        viewModelScope.launch { store.update(transform) }
    }

    fun offerAsk(target: ScoutAskTarget?) {
        if (target != null && !mixSession.active) store.offerAsk(target)
    }

    fun answerAsk(target: ScoutAskTarget, rating: ScoutRating?) {
        store.clearAsk()
        viewModelScope.launch {
            store.markAsked(target.askKey)
            if (rating != null) store.setRating(target.title, rating)
        }
    }

    suspend fun shouldAsk(target: ScoutAskTarget): Boolean = scoutShouldAsk(store.current(), target)

    fun dropAsk() = store.clearAsk()

    suspend fun genres(): Map<Int, String> = runCatching {
        resolver.genres(com.nuvio.tv.domain.model.TmdbCollectionMediaType.MOVIE) +
            resolver.genres(com.nuvio.tv.domain.model.TmdbCollectionMediaType.TV)
    }.getOrDefault(emptyMap())
}
