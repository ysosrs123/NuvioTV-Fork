package com.nuvio.tv.core.scout.sync

import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ScoutOutItem
import com.nuvio.tv.data.local.ScoutStore
import com.nuvio.tv.data.simkl.SimklSyncRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps Scout thumbs and the trackers of the active profile in step: thumbs pressed in Arc go out when
 * sending is on, tracker ratings come in as thumbs when using them is on.
 */
@Singleton
class ScoutRatingSync @Inject constructor(
    private val store: ScoutStore,
    private val profileManager: ProfileManager,
    private val simklSync: SimklSyncRepository,
    trakt: TraktRatingClient,
    simkl: SimklRatingClient,
    mdbList: MdbListRatingClient,
    weTrakr: WeTrakrRatingClient
) {
    private val clients: List<TrackerRatingClient> = listOf(trakt, simkl, mdbList, weTrakr)
    private val polled: List<TrackerRatingClient> = listOf(trakt, mdbList, weTrakr)
    private val simklClient = simkl

    suspend fun follow() = withContext(Dispatchers.IO) {
        profileManager.activeProfileId.collectLatest { profileId ->
            coroutineScope {
                launch { followOutbox(profileId) }
                launch { followImports(profileId) }
            }
        }
    }

    private suspend fun followOutbox(profileId: Int) {
        combine(store.outboxSize(profileId), store.profile.map { it.settings.sendToTrackers }) { size, on -> size > 0 && on }
            .distinctUntilChanged()
            .collectLatest { work ->
                var failures = 0
                while (work) {
                    val wait = runCatching { drain(profileId) }.getOrElse { error ->
                        if (error is CancellationException) throw error
                        Log.w(TAG, "send failed: ${error.javaClass.simpleName}")
                        RETRY_MS
                    }
                    when {
                        wait == null -> failures = 0
                        else -> failures++
                    }
                    delay(wait?.let { (it shl (failures - 1).coerceIn(0, 6)).coerceAtMost(MAX_RETRY_MS) } ?: IDLE_CHECK_MS)
                }
            }
    }

    /** Sends what is queued. Null when every connected tracker is done; otherwise how long to wait before trying again. */
    internal suspend fun drain(profileId: Int): Long? {
        val queued = store.outbox(profileId)
        if (queued.isEmpty()) return null
        val connected = clients.filter { it.connected() }
        if (connected.isEmpty()) return null
        val required = connected.map { it.providerId.storageId }.toSet()
        var retry: Long? = null
        connected.forEach { client ->
            val provider = client.providerId.storageId
            val items = queued.values.filter { provider !in it.done }
            if (items.isEmpty()) return@forEach
            try {
                sendTo(client, profileId, items, required)
            } catch (error: TrackerRateLimitedException) {
                retry = maxOf(retry ?: 0L, error.retryAfterMs ?: RETRY_MS)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "$provider send failed: ${error.javaClass.simpleName}")
                retry = maxOf(retry ?: 0L, RETRY_MS)
            }
        }
        return retry
    }

    private suspend fun sendTo(client: TrackerRatingClient, profileId: Int, items: List<ScoutOutItem>, required: Set<String>) {
        val provider = client.providerId.storageId
        val remote = client.ratings().byKey()
        val sent = store.sent(profileId, provider)
        val writes = ArrayList<Pair<ScoutOutItem, TrackerRatingWrite>>()
        val removes = ArrayList<Pair<ScoutOutItem, TrackerTitle>>()
        suspend fun finish(item: ScoutOutItem, value: Double?, keepSent: Boolean = false) =
            store.finishOut(profileId, item.title.id, item.action.stamp, provider, if (keepSent) sent[item.title.id] else value, required)
        items.forEach { item ->
            val title = item.title.trackerTitle()
            if (title == null) {
                store.dropOut(profileId, item.title.id, item.action.stamp)
                return@forEach
            }
            if (item.action.rating != null && !client.accepts(title)) {
                finish(item, null, keepSent = true)
                return@forEach
            }
            when (val step = planPush(item.action, remote.find(title)?.value, sent[item.title.id])) {
                is PushStep.Write -> writes += item to TrackerRatingWrite(title, step.value, item.action.stamp)
                PushStep.Remove -> removes += item to title
                is PushStep.Done -> finish(item, step.value)
                PushStep.Keep -> finish(item, null)
            }
        }
        writes.chunked(BATCH).forEach { batch ->
            val rejected = runRejectable { client.write(batch.map { it.second }) }
            batch.forEach { (item, write) -> finish(item, write.value, keepSent = rejected) }
        }
        removes.chunked(BATCH).forEach { batch ->
            val rejected = runRejectable { client.remove(batch.map { it.second }) }
            batch.forEach { (item, _) -> finish(item, null, keepSent = rejected) }
        }
    }

    /** True when the tracker refused the batch for good; it is then dropped rather than retried forever. */
    private suspend fun runRejectable(block: suspend () -> Unit): Boolean = try {
        block()
        false
    } catch (error: TrackerRejectedException) {
        Log.w(TAG, "batch refused: ${error.message}")
        true
    }

    private suspend fun followImports(profileId: Int) {
        store.profile.map { it.settings.useTrackerRatings }.distinctUntilChanged().collectLatest { on ->
            if (!on) return@collectLatest
            coroutineScope {
                launch {
                    simklSync.state.map { it.snapshot.storageRevision to it.hasLoaded }.distinctUntilChanged().collectLatest { (_, loaded) ->
                        if (!loaded) return@collectLatest
                        delay(SETTLE_MS)
                        pull(simklClient, profileId)
                    }
                }
                while (true) {
                    val now = System.currentTimeMillis()
                    polled.forEach { client ->
                        if (client.connected() && now - store.lastPulled(profileId, client.providerId.storageId) >= PULL_EVERY_MS) {
                            pull(client, profileId)
                        }
                    }
                    delay(POLL_CHECK_MS)
                }
            }
        }
    }

    internal suspend fun pull(client: TrackerRatingClient, profileId: Int) {
        if (!client.connected()) return
        val provider = client.providerId.storageId
        try {
            val remote = client.ratings()
            val now = System.currentTimeMillis()
            val changed = store.applyImport(profileId) { profile, pending, sent ->
                if (!profile.settings.useTrackerRatings) return@applyImport emptyList()
                val mine = sent.filterKeys { it.startsWith("$provider:") }.mapKeys { it.key.removePrefix("$provider:") }
                planImport(client.providerId, remote, profile.ratings, profile.dismissed, mine, pending, now)
            }
            store.markPulled(profileId, provider, now)
            if (changed > 0) Log.i(TAG, "$provider: $changed thumbs updated from ${remote.size} ratings")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "$provider read failed: ${error.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "ScoutRatingSync"
        const val BATCH = 50
        const val RETRY_MS = 30_000L
        const val MAX_RETRY_MS = 30 * 60_000L
        const val IDLE_CHECK_MS = 10 * 60_000L
        const val SETTLE_MS = 3_000L
        const val POLL_CHECK_MS = 15 * 60_000L
        const val PULL_EVERY_MS = 6 * 60 * 60_000L
    }
}
