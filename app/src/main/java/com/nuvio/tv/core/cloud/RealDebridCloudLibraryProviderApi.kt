package com.nuvio.tv.core.cloud

import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.core.debrid.RealDebridAuth
import com.nuvio.tv.core.debrid.hasDebridVideoExtension
import com.nuvio.tv.data.remote.api.RealDebridApi
import com.nuvio.tv.data.remote.dto.RealDebridTorrentInfoDto
import com.nuvio.tv.data.remote.dto.RealDebridTorrentListItemDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealDebridCloudLibraryProviderApi @Inject constructor(
    private val api: RealDebridApi,
    private val auth: RealDebridAuth
) : CloudLibraryProviderApi {
    override val provider = DebridProviders.RealDebrid

    override suspend fun listItems(apiKey: String): Result<List<CloudLibraryItem>> =
        runCatching {
            val response = auth.call(apiKey) { api.listTorrents(it, limit = LIST_LIMIT) }
                ?: throw IllegalStateException(null as String?)
            if (response.code() == 204) return@runCatching emptyList()
            if (!response.isSuccessful) throw IllegalStateException(response.errorBody()?.string().orEmpty().ifBlank { null })
            val torrents = response.body().orEmpty()
            val detailed = torrents
                .filter { it.status.equals("downloaded", ignoreCase = true) && (it.links?.size ?: 0) > 1 }
                .take(DETAIL_LIMIT)
            val infos = fetchInfos(apiKey, detailed.mapNotNull { it.id })
            torrents.mapNotNull { torrent -> torrent.toCloudLibraryItem(infos[torrent.id]) }
        }

    override suspend fun resolvePlayback(
        apiKey: String,
        item: CloudLibraryItem,
        file: CloudLibraryFile
    ): CloudLibraryPlaybackResult {
        if (!file.playable) return CloudLibraryPlaybackResult.NotPlayable
        val link = file.id?.takeIf { it.isNotBlank() } ?: return CloudLibraryPlaybackResult.Failed()
        return try {
            val response = auth.call(apiKey) { api.unrestrictLink(it, link) }
                ?: return CloudLibraryPlaybackResult.MissingCredentials
            val body = response.body()?.takeIf { response.isSuccessful }
            response.errorBody()?.close()
            val url = body?.download?.takeIf { it.isNotBlank() } ?: return CloudLibraryPlaybackResult.Failed()
            CloudLibraryPlaybackResult.Success(
                url = url,
                filename = body.filename?.takeIf { it.isNotBlank() } ?: file.name.takeIf { it.isNotBlank() },
                videoSizeBytes = body.filesize ?: file.sizeBytes
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            CloudLibraryPlaybackResult.Failed(error.message)
        }
    }

    private suspend fun fetchInfos(apiKey: String, ids: List<String>): Map<String, RealDebridTorrentInfoDto> {
        if (ids.isEmpty()) return emptyMap()
        val permits = Semaphore(INFO_PARALLEL)
        return coroutineScope {
            ids.map { id ->
                async {
                    permits.withPermit {
                        runCatching {
                            auth.call(apiKey) { api.getTorrentInfo(it, id) }
                                ?.takeIf { it.isSuccessful }?.body()
                        }.getOrNull()?.let { id to it }
                    }
                }
            }.awaitAll().filterNotNull().toMap()
        }
    }

    private fun RealDebridTorrentListItemDto.toCloudLibraryItem(info: RealDebridTorrentInfoDto?): CloudLibraryItem? {
        val itemId = id?.takeIf { it.isNotBlank() } ?: return null
        val itemName = filename?.trim()?.takeIf { it.isNotBlank() } ?: itemId
        val ready = status.equals("downloaded", ignoreCase = true)
        return CloudLibraryItem(
            providerId = provider.id,
            providerName = provider.displayName,
            id = itemId,
            type = CloudLibraryItemType.Torrent,
            name = itemName,
            status = if (ready) "Ready" else status,
            sizeBytes = bytes,
            files = if (ready) realDebridCloudFiles(itemName, links.orEmpty(), info) else emptyList()
        )
    }

    private companion object {
        const val LIST_LIMIT = 500
        const val DETAIL_LIMIT = 30
        const val INFO_PARALLEL = 4
    }
}

/**
 * Real-Debrid gives one link per selected file. A single-file torrent's link is named after the torrent; the
 * names of a pack's files come from the torrent details when they were read.
 */
internal fun realDebridCloudFiles(
    torrentName: String,
    links: List<String>,
    info: RealDebridTorrentInfoDto?
): List<CloudLibraryFile> {
    val usable = links.filter { it.isNotBlank() }
    if (usable.isEmpty()) return emptyList()
    if (usable.size == 1) {
        val name = info?.files.orEmpty().singleOrNull { it.selected == 1 }?.displayName() ?: torrentName
        return listOf(CloudLibraryFile(id = usable.first(), name = name, playable = name.hasDebridVideoExtension()))
    }
    val selected = info?.files.orEmpty().filter { it.selected == 1 }.sortedBy { it.id ?: Int.MAX_VALUE }
    if (selected.size != usable.size) return emptyList()
    return selected.zip(usable) { file, link ->
        val name = file.displayName()
        CloudLibraryFile(id = link, name = name, sizeBytes = file.bytes, playable = name.hasDebridVideoExtension())
    }.sortedWith(compareBy<CloudLibraryFile> { !it.playable }.thenBy { it.name.lowercase() })
}
