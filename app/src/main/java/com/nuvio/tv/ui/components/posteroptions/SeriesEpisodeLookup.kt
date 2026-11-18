package com.nuvio.tv.ui.components.posteroptions

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.repository.MetaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The episodes a whole-show Mark watched covers. Scout and TMDB rows hand out tmdb: ids, which the primary meta
 * add-on often does not know, so the other add-ons are asked next (as the details page does), then the primary again
 * under the title's IMDb id.
 */
internal suspend fun lookUpSeriesEpisodes(
    metaRepository: MetaRepository,
    item: MetaPreview,
    type: String,
    addonBaseUrl: String?,
    resolve: suspend (MetaPreview) -> MetaPreview,
    pick: (Meta) -> List<Video>,
): List<Video> {
    fun List<Video>?.found(): List<Video>? = this?.takeIf { it.isNotEmpty() }

    metaRepository.getMetaFromPrimaryAddon(type, item.id).lastMeta()?.let(pick).found()?.let { return it }
    metaRepository.getMetaFromAllAddons(type, item.id, sourceAddonBaseUrl = addonBaseUrl?.takeIf { it.isNotBlank() })
        .lastMeta()?.let(pick).found()?.let { return it }
    val resolved = runCatching { resolve(item) }.getOrNull() ?: return emptyList()
    if (resolved.id == item.id) return emptyList()
    return metaRepository.getMetaFromPrimaryAddon(type, resolved.id).lastMeta()?.let(pick).found().orEmpty()
}

private const val EPISODE_LOOKUP_TIMEOUT_MS = 8_000L

private suspend fun Flow<NetworkResult<Meta>>.lastMeta(): Meta? {
    var meta: Meta? = null
    withTimeoutOrNull(EPISODE_LOOKUP_TIMEOUT_MS) {
        collect { result -> if (result is NetworkResult.Success) meta = result.data }
    }
    return meta
}
