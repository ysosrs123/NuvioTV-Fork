package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.remote.api.RealDebridApi
import com.nuvio.tv.data.remote.dto.RealDebridTorrentFileDto
import com.nuvio.tv.data.remote.dto.RealDebridTorrentInfoDto
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealDebridDirectDebridResolver @Inject constructor(
    private val dataStore: DebridSettingsDataStore,
    private val api: RealDebridApi,
    private val fileSelector: RealDebridFileSelector,
    private val auth: RealDebridAuth,
    private val accounts: DebridAccounts
) {
    suspend fun resolve(
        stream: Stream,
        season: Int?,
        episode: Int?,
        mayAddToAccount: Boolean = true
    ): DirectDebridResolveResult {
        val resolve = stream.clientResolve ?: return DirectDebridResolveResult.Error
        val stored = dataStore.settings.first().realDebridApiKey.trim()
        if (stored.isBlank()) return DirectDebridResolveResult.MissingApiKey

        return try {
            val hash = resolve.infoHash ?: stream.getEffectiveInfoHash()
            accounts.findReady(DebridProviders.REAL_DEBRID_ID, stored, hash)?.let { existing ->
                fromExistingTorrent(stored, existing.id, resolve, season, episode)?.let { return it }
            }
            if (!mayAddToAccount) return DirectDebridResolveResult.NotCached
            val magnet = resolve.magnetUri?.takeIf { it.isNotBlank() }
                ?: buildMagnetUri(resolve)
                ?: return DirectDebridResolveResult.Stale
            val result = addAndResolve(stored, magnet, resolve, season, episode, wholePack = true)
            if (result is RetrySingleFile) {
                addAndResolve(stored, magnet, resolve, season, episode, wholePack = false).asResult()
            } else {
                result.asResult()
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            DirectDebridResolveResult.Error
        }
    }

    private suspend fun fromExistingTorrent(
        stored: String,
        torrentId: String,
        resolve: StreamClientResolve,
        season: Int?,
        episode: Int?
    ): DirectDebridResolveResult? {
        val info = call(stored) { api.getTorrentInfo(it, torrentId) }?.takeIf { it.isSuccessful }?.body()
            ?: return null
        if (!info.status.equals("downloaded", ignoreCase = true)) return null
        val file = fileSelector.selectFile(info.files.orEmpty(), resolve, season, episode)
            ?.takeIf { it.selected == 1 }
            ?: return null
        val link = info.linkFor(file) ?: return null
        return unrestrict(stored, link, file) as? DirectDebridResolveResult.Success
    }

    private suspend fun addAndResolve(
        stored: String,
        magnet: String,
        resolve: StreamClientResolve,
        season: Int?,
        episode: Int?,
        wholePack: Boolean
    ): Attempt {
        val add = call(stored) { api.addMagnet(it, magnet) } ?: return Attempt.Done(DirectDebridResolveResult.Error)
        val torrentId = add.body()?.id?.takeIf { add.isSuccessful && it.isNotBlank() }
            ?: return Attempt.Done(add.toFailureForAdd())
        var keep = false
        try {
            val infoBefore = awaitFiles(stored, torrentId) ?: return Attempt.Done(DirectDebridResolveResult.Stale)
            val files = infoBefore.files.orEmpty()
            val file = fileSelector.selectFile(files, resolve, season, episode)
                ?: return Attempt.Done(DirectDebridResolveResult.Stale)
            val fileId = file.id ?: return Attempt.Done(DirectDebridResolveResult.Stale)
            val pack = if (wholePack) packFiles(files, file) else listOf(file)
            val select = call(stored) { api.selectFiles(it, torrentId, pack.mapNotNull { f -> f.id }.joinToString(",")) }
            select?.body()?.close()
            select?.errorBody()?.close()
            if (select == null || (!select.isSuccessful && select.code() != 202)) {
                return Attempt.Done(DirectDebridResolveResult.Stale)
            }

            val infoAfter = call(stored) { api.getTorrentInfo(it, torrentId) }?.takeIf { it.isSuccessful }?.body()
                ?: return Attempt.Done(DirectDebridResolveResult.Stale)
            if (!infoAfter.status.equals("downloaded", ignoreCase = true)) {
                return if (pack.size > 1) RetrySingleFile else Attempt.Done(DirectDebridResolveResult.NotCached)
            }
            val chosen = infoAfter.files.orEmpty().firstOrNull { it.id == fileId } ?: file
            val link = infoAfter.linkFor(chosen) ?: return Attempt.Done(DirectDebridResolveResult.Stale)
            val result = unrestrict(stored, link, chosen)
            keep = result is DirectDebridResolveResult.Success
            if (keep) accounts.invalidate(DebridProviders.REAL_DEBRID_ID, stored)
            return Attempt.Done(result)
        } finally {
            if (!keep) {
                withContext(NonCancellable) {
                    runCatching { call(stored) { api.deleteTorrent(it, torrentId) }?.errorBody()?.close() }
                }
            }
        }
    }

    private suspend fun awaitFiles(stored: String, torrentId: String): RealDebridTorrentInfoDto? {
        repeat(FILE_LIST_ATTEMPTS) { attempt ->
            val info = call(stored) { api.getTorrentInfo(it, torrentId) }?.takeIf { it.isSuccessful }?.body()
                ?: return null
            if (!info.files.isNullOrEmpty() || !info.status.equals("magnet_conversion", ignoreCase = true)) return info
            if (attempt < FILE_LIST_ATTEMPTS - 1) delay(FILE_LIST_WAIT_MS)
        }
        return null
    }

    private suspend fun unrestrict(
        stored: String,
        link: String,
        file: RealDebridTorrentFileDto
    ): DirectDebridResolveResult {
        val answer = call(stored) { api.unrestrictLink(it, link) } ?: return DirectDebridResolveResult.Stale
        val unrestrict = answer.takeIf { it.isSuccessful }
            ?: return DirectDebridResolveResult.busyOrStale(answer.code())
        val body = unrestrict.body()
        val url = body?.download?.takeIf { it.isNotBlank() } ?: return DirectDebridResolveResult.Stale
        return DirectDebridResolveResult.Success(
            url = url,
            filename = body.filename?.takeIf { it.isNotBlank() } ?: file.displayName().takeIf { it.isNotBlank() },
            videoSize = body.filesize ?: file.bytes
        )
    }

    private suspend fun <T> call(stored: String, block: suspend (String) -> Response<T>): Response<T>? =
        auth.call(stored, block)

    private sealed interface Attempt {
        data class Done(val result: DirectDebridResolveResult) : Attempt
    }

    private data object RetrySingleFile : Attempt

    private fun Attempt.asResult(): DirectDebridResolveResult = when (this) {
        is Attempt.Done -> result
        RetrySingleFile -> DirectDebridResolveResult.NotCached
    }

    private fun Response<com.nuvio.tv.data.remote.dto.RealDebridAddTorrentDto>.toFailureForAdd(): DirectDebridResolveResult {
        errorBody()?.close()
        return when (code()) {
            401, 403 -> DirectDebridResolveResult.Error
            else -> DirectDebridResolveResult.busyOrStale(code())
        }
    }

    private fun buildMagnetUri(resolve: StreamClientResolve): String? {
        val hash = resolve.infoHash?.takeIf { it.isNotBlank() } ?: return null
        return buildString {
            append("magnet:?xt=urn:btih:")
            append(hash)
            resolve.sources
                ?.filter { it.isNotBlank() }
                ?.forEach { source ->
                    append("&tr=")
                    append(java.net.URLEncoder.encode(source, "UTF-8"))
                }
        }
    }

    private companion object {
        const val FILE_LIST_ATTEMPTS = 3
        const val FILE_LIST_WAIT_MS = 400L
    }
}

/**
 * Episodes of a season pack are taken together, so the next episode is already in the account. Other files
 * (films, single episodes, extras) are taken one by one.
 */
internal fun packFiles(files: List<RealDebridTorrentFileDto>, chosen: RealDebridTorrentFileDto): List<RealDebridTorrentFileDto> {
    val episodes = files.filter { file ->
        file.id != null && file.displayName().hasDebridVideoExtension() && EPISODE_TAG.containsMatchIn(file.displayName())
    }
    return if (episodes.size > 1 && episodes.any { it.id == chosen.id }) episodes else listOf(chosen)
}

/** Real-Debrid lists one link per selected file, in file order. */
internal fun RealDebridTorrentInfoDto.linkFor(file: RealDebridTorrentFileDto): String? {
    val links = links.orEmpty().filter { it.isNotBlank() }
    if (links.isEmpty()) return null
    val selected = files.orEmpty().filter { it.selected == 1 }.sortedBy { it.id ?: Int.MAX_VALUE }
    val index = selected.indexOfFirst { it.id == file.id }
    return when {
        links.size == selected.size && index >= 0 -> links[index]
        links.size == 1 && selected.size <= 1 -> links.first()
        else -> null
    }
}

private val EPISODE_TAG = Regex("(?<![a-z0-9])(?:s\\d{1,2}e\\d{1,3}|\\d{1,2}x\\d{2,3})(?![0-9])", RegexOption.IGNORE_CASE)
