package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.remote.api.AllDebridApi
import com.nuvio.tv.data.remote.dto.AllDebridUnlockDto
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AllDebridDirectDebridResolver @Inject constructor(
    private val dataStore: DebridSettingsDataStore,
    private val api: AllDebridApi,
    private val fileSelector: AllDebridFileSelector,
    private val accounts: DebridAccounts
) {
    suspend fun resolve(
        stream: Stream,
        season: Int?,
        episode: Int?,
        mayAddToAccount: Boolean = true
    ): DirectDebridResolveResult {
        val resolve = stream.clientResolve ?: return DirectDebridResolveResult.Error
        val stored = dataStore.settings.first().allDebridApiKey.trim()
        if (stored.isBlank()) return DirectDebridResolveResult.MissingApiKey
        val authorization = "Bearer $stored"

        return try {
            val hash = (resolve.infoHash ?: stream.getEffectiveInfoHash())?.trim()?.lowercase()
            accounts.findReady(DebridProviders.ALL_DEBRID_ID, stored, hash)?.let { existing ->
                existing.id.toLongOrNull()?.let { id ->
                    val result = fromMagnet(authorization, id, resolve, season, episode)
                    if (result is DirectDebridResolveResult.Success) return result
                }
            }
            if (!mayAddToAccount) return DirectDebridResolveResult.NotCached
            val magnet = resolve.magnetUri?.takeIf { it.isNotBlank() }
                ?: hash
                ?: return DirectDebridResolveResult.Stale
            addAndResolve(stored, authorization, magnet, hash, resolve, season, episode)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            DirectDebridResolveResult.Error
        }
    }

    private suspend fun addAndResolve(
        stored: String,
        authorization: String,
        magnet: String,
        hash: String?,
        resolve: StreamClientResolve,
        season: Int?,
        episode: Int?
    ): DirectDebridResolveResult {
        val listedBefore = accounts.items(DebridProviders.ALL_DEBRID_ID, stored)?.map { it.id }?.toSet()
        val rememberedBefore = accounts.rememberedAllDebridMagnet(hash)
        val upload = api.uploadMagnets(authorization, listOf(magnet))
        val uploadSec = (upload.headers().getDate("Date")?.time ?: System.currentTimeMillis()) / 1000L
        val envelope = upload.body()
        upload.errorBody()?.close()
        if (!upload.isSuccessful || envelope == null) {
            return if (upload.code() == 401 || upload.code() == 403) DirectDebridResolveResult.Error
            else DirectDebridResolveResult.Stale
        }
        if (!envelope.isSuccess) return envelope.error?.code.toFailure()
        val uploaded = envelope.data?.magnets?.firstOrNull() ?: return DirectDebridResolveResult.Stale
        uploaded.error?.let { return it.code.toFailure() }
        val id = uploaded.id ?: return DirectDebridResolveResult.Stale
        accounts.rememberAllDebridMagnet(uploaded.hash ?: hash, id)

        var result: DirectDebridResolveResult = DirectDebridResolveResult.NotCached
        try {
            if (uploaded.ready == true) result = fromMagnet(authorization, id, resolve, season, episode)
        } finally {
            if (result !is DirectDebridResolveResult.Success) {
                withContext(NonCancellable) {
                    val missingBefore = if (listedBefore != null) id.toString() !in listedBefore
                    else rememberedBefore != id.toString()
                    if (missingBefore && runCatching { addedNow(authorization, id, uploadSec) }.getOrDefault(false)) {
                        runCatching { api.deleteMagnet(authorization, id).errorBody()?.close() }
                    }
                }
            }
        }
        accounts.invalidate(DebridProviders.ALL_DEBRID_ID, stored)
        return result
    }

    /**
     * AllDebrid hands back the existing entry when the title was already in the account; that one is never removed.
     * An entry counts as added now only when it was missing from the account list read just before (or, without that
     * list, was not the entry remembered for the hash) and its upload time (server clock) matches this upload.
     */
    private suspend fun addedNow(authorization: String, id: Long, uploadSec: Long): Boolean {
        val response = api.magnetStatus(authorization, id)
        response.errorBody()?.close()
        val uploadDate = response.body()?.use { parseAllDebridMagnets(it.charStream()) }
            ?.firstOrNull { it.id == id }?.uploadDate
            ?: return false
        return uploadDate >= uploadSec - UPLOAD_CLOCK_SLACK_SEC
    }

    private suspend fun fromMagnet(
        authorization: String,
        id: Long,
        resolve: StreamClientResolve,
        season: Int?,
        episode: Int?
    ): DirectDebridResolveResult {
        val response = api.magnetFiles(authorization, listOf(id))
        response.errorBody()?.close()
        val entry = response.body()?.takeIf { response.isSuccessful && it.isSuccess }
            ?.data?.magnets?.firstOrNull { it.id == id }
            ?: return DirectDebridResolveResult.Stale
        if (entry.error != null) return DirectDebridResolveResult.Stale
        val files = entry.files.orEmpty().flattenAllDebridFiles()
        val file = fileSelector.selectFile(files, resolve, season, episode) ?: return DirectDebridResolveResult.Stale
        val link = file.link ?: return DirectDebridResolveResult.Stale
        return unlock(authorization, link, file)
    }

    private suspend fun unlock(authorization: String, link: String, file: AllDebridFile): DirectDebridResolveResult {
        val response = api.unlockLink(authorization, link)
        response.errorBody()?.close()
        val data = response.body()?.takeIf { response.isSuccessful && it.isSuccess }?.data
            ?: return DirectDebridResolveResult.Stale
        val url = data.link?.takeIf { it.isNotBlank() } ?: awaitDelayed(authorization, data)
            ?: return DirectDebridResolveResult.Stale
        return DirectDebridResolveResult.Success(
            url = url,
            filename = data.filename?.takeIf { it.isNotBlank() } ?: file.name.takeIf { it.isNotBlank() },
            videoSize = data.filesize ?: file.size
        )
    }

    private suspend fun awaitDelayed(authorization: String, unlock: AllDebridUnlockDto): String? {
        val delayedId = unlock.delayed ?: return null
        repeat(DELAYED_ATTEMPTS) {
            delay(DELAYED_WAIT_MS)
            val response = api.delayedLink(authorization, delayedId)
            response.errorBody()?.close()
            val data = response.body()?.takeIf { response.isSuccessful && it.isSuccess }?.data ?: return null
            when (data.status) {
                DELAYED_READY -> return data.link?.takeIf { it.isNotBlank() }
                DELAYED_FAILED -> return null
            }
        }
        return null
    }

    private fun String?.toFailure(): DirectDebridResolveResult = when (this) {
        "AUTH_BAD_APIKEY", "AUTH_MISSING_APIKEY", "AUTH_BLOCKED", "AUTH_USER_BANNED", "MAGNET_MUST_BE_PREMIUM" ->
            DirectDebridResolveResult.Error
        else -> DirectDebridResolveResult.Stale
    }

    private companion object {
        const val UPLOAD_CLOCK_SLACK_SEC = 10L
        const val DELAYED_ATTEMPTS = 6
        const val DELAYED_WAIT_MS = 5_000L
        const val DELAYED_READY = 2
        const val DELAYED_FAILED = 3
    }
}
