package com.nuvio.tv.core.cloud

import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.core.debrid.flattenAllDebridFiles
import com.nuvio.tv.core.debrid.hasDebridVideoExtension
import com.nuvio.tv.core.debrid.parseAllDebridMagnets
import com.nuvio.tv.data.remote.api.AllDebridApi
import com.nuvio.tv.data.remote.dto.AllDebridMagnetDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AllDebridCloudLibraryProviderApi @Inject constructor(
    private val api: AllDebridApi
) : CloudLibraryProviderApi {
    override val provider = DebridProviders.AllDebrid

    override suspend fun listItems(apiKey: String): Result<List<CloudLibraryItem>> =
        runCatching {
            val authorization = "Bearer $apiKey"
            val response = api.listMagnets(authorization)
            response.errorBody()?.close()
            val magnets = response.body()?.use { body ->
                if (response.isSuccessful) parseAllDebridMagnets(body.charStream()) else null
            } ?: throw IllegalStateException(null as String?)
            val ready = magnets.filter { it.isReady }.mapNotNull { it.id }
            val files = HashMap<Long, List<CloudLibraryFile>>()
            ready.chunked(FILES_BATCH).forEach { ids ->
                val filesResponse = api.magnetFiles(authorization, ids)
                filesResponse.errorBody()?.close()
                filesResponse.body()?.takeIf { filesResponse.isSuccessful && it.isSuccess }
                    ?.data?.magnets.orEmpty()
                    .forEach { entry ->
                        val id = entry.id ?: return@forEach
                        files[id] = entry.files.orEmpty().flattenAllDebridFiles().mapNotNull { file ->
                            val link = file.link?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                            CloudLibraryFile(
                                id = link,
                                name = file.name,
                                sizeBytes = file.size,
                                playable = file.name.hasDebridVideoExtension()
                            )
                        }.sortedWith(compareBy<CloudLibraryFile> { !it.playable }.thenBy { it.name.lowercase() })
                    }
            }
            magnets.mapNotNull { magnet -> magnet.toCloudLibraryItem(files[magnet.id].orEmpty()) }
        }

    override suspend fun resolvePlayback(
        apiKey: String,
        item: CloudLibraryItem,
        file: CloudLibraryFile
    ): CloudLibraryPlaybackResult {
        if (!file.playable) return CloudLibraryPlaybackResult.NotPlayable
        val link = file.id?.takeIf { it.isNotBlank() } ?: return CloudLibraryPlaybackResult.Failed()
        val authorization = "Bearer $apiKey"
        return try {
            val response = api.unlockLink(authorization, link)
            response.errorBody()?.close()
            val envelope = response.body()?.takeIf { response.isSuccessful }
                ?: return CloudLibraryPlaybackResult.Failed()
            val data = envelope.data?.takeIf { envelope.isSuccess }
                ?: return CloudLibraryPlaybackResult.Failed(envelope.error?.message)
            val url = data.link?.takeIf { it.isNotBlank() }
                ?: data.delayed?.let { awaitDelayed(authorization, it) }
                ?: return CloudLibraryPlaybackResult.Failed()
            CloudLibraryPlaybackResult.Success(
                url = url,
                filename = data.filename?.takeIf { it.isNotBlank() } ?: file.name.takeIf { it.isNotBlank() },
                videoSizeBytes = data.filesize ?: file.sizeBytes
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            CloudLibraryPlaybackResult.Failed(error.message)
        }
    }

    private suspend fun awaitDelayed(authorization: String, delayedId: Long): String? {
        repeat(DELAYED_ATTEMPTS) {
            delay(DELAYED_WAIT_MS)
            val response = api.delayedLink(authorization, delayedId)
            response.errorBody()?.close()
            val data = response.body()?.takeIf { response.isSuccessful && it.isSuccess }?.data ?: return null
            when (data.status) {
                2 -> return data.link?.takeIf { it.isNotBlank() }
                3 -> return null
            }
        }
        return null
    }

    private fun AllDebridMagnetDto.toCloudLibraryItem(files: List<CloudLibraryFile>): CloudLibraryItem? {
        val itemId = id?.toString() ?: return null
        val itemName = filename?.trim()?.takeIf { it.isNotBlank() } ?: itemId
        return CloudLibraryItem(
            providerId = provider.id,
            providerName = provider.displayName,
            id = itemId,
            type = CloudLibraryItemType.Torrent,
            name = itemName,
            status = if (isReady) "Ready" else status,
            sizeBytes = size,
            files = if (isReady) files else emptyList()
        )
    }

    private companion object {
        const val FILES_BATCH = 50
        const val DELAYED_ATTEMPTS = 6
        const val DELAYED_WAIT_MS = 5_000L
    }
}
