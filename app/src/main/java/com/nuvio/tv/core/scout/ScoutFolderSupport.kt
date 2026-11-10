package com.nuvio.tv.core.scout

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ScoutStore
import android.util.Log
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.ScoutProfile
import com.nuvio.tv.domain.model.listedTitles
import com.nuvio.tv.domain.model.scoutExclusionName
import com.nuvio.tv.domain.model.scoutNormalizedName
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.Lazy
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val LIBRARY_WAIT_MS = 2_000L
private const val LISTED_FRESH_MS = 60_000L
private const val CHANGE_SETTLE_MS = 2_000L

/** Titles Scout must not offer: on Plan to watch or Watching, whatever the Home switches say. */
data class ScoutListed(val ids: Set<String> = emptySet(), val names: Set<String> = emptySet())

/** What Scout's rows and tile pages leave out, read fresh each time a row loads. */
@Singleton
class ScoutFolderSupport @Inject constructor(
    private val store: ScoutStore,
    private val profiles: ProfileManager,
    private val watchProgress: Lazy<WatchProgressRepository>,
    private val library: Lazy<LibraryRepository>
) {
    fun sliceId(collectionId: String, folderId: String): String? =
        folderId.takeIf { collectionId == ScoutCatalogProvider.TILES_COLLECTION_ID && ScoutSlices.byId(it) != null }

    fun isSaved(sliceId: String): Flow<Boolean> = store.profile.map { sliceId in it.settings.savedSlices }

    suspend fun toggleSaved(sliceId: String) {
        store.update { settings ->
            val saved = settings.savedSlices
            settings.copy(savedSlices = if (sliceId in saved) saved - sliceId else saved + sliceId)
        }
    }

    private class RememberedList(val profileId: Int, val at: Long, val listed: ScoutListed)

    @Volatile private var remembered: RememberedList? = null
    private val refreshing = AtomicBoolean(false)
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The last answer at once when there is one; the library can take seconds to reply. */
    suspend fun listed(): ScoutListed = withContext(Dispatchers.Default) {
        val profileId = profiles.activeProfileId.value
        val last = remembered?.takeIf { it.profileId == profileId }
            ?: return@withContext listedLock.withLock {
                remembered?.takeIf { it.profileId == profileId }?.listed
                    ?: loadListed().also { rememberListed(profileId, it) }
                    ?: ScoutListed()
            }
        if (System.currentTimeMillis() - last.at >= LISTED_FRESH_MS && refreshing.compareAndSet(false, true)) {
            background.launch {
                try {
                    rememberListed(profileId, loadListed())
                } finally {
                    refreshing.set(false)
                }
            }
        }
        last.listed
    }

    private fun rememberListed(profileId: Int, listed: ScoutListed?) {
        if (listed == null || profiles.activeProfileId.value != profileId) return
        remembered = RememberedList(profileId, System.currentTimeMillis(), listed)
    }

    private suspend fun loadListed(): ScoutListed? = withTimeoutOrNull(LIBRARY_WAIT_MS) {
        val tabs = library.get().listTabs.first()
        val items = library.get().libraryItems.first()
        val inProgress = watchProgress.get().allProgress.first().filter { it.isInProgress() }.map { it.contentId }
        val listed = listedTitles(tabs, items, inProgress)
        val keys = listed.planListKeys + listed.watchingListKeys
        ScoutListed(
            ids = listed.planIds + listed.watchingIds,
            names = items.filter { entry -> entry.listKeys.any(keys::contains) }
                .mapTo(HashSet()) { scoutExclusionName(it.name, it.releaseInfo) }
        )
    }

    private class SeenNames(val profileId: Int, val profile: ScoutProfile, val listed: ScoutListed, val at: Long, val names: Set<String>)

    @Volatile private var seenNames: SeenNames? = null
    private val namesLock = Mutex()
    private val listedLock = Mutex()

    private suspend fun namesToHide(profile: ScoutProfile, listed: ScoutListed): Set<String> = namesLock.withLock {
        val profileId = profiles.activeProfileId.value
        val now = System.currentTimeMillis()
        seenNames?.takeIf { it.profileId == profileId && it.profile == profile && it.listed === listed && now - it.at < LISTED_FRESH_MS }?.let { return@withLock it.names }
        val repository = watchProgress.get()
        val names = repository.tasteHistory.first().map { scoutExclusionName(it.title, it.releaseInfo) }.toSet() +
            repository.allProgress.first().map { scoutNormalizedName(it.name) } +
            profile.ratings.values.filter { it.rating == com.nuvio.tv.domain.model.ScoutRating.NOT_FOR_ME }.map { scoutNormalizedName(it.title.name) } +
            listed.names
        seenNames = SeenNames(profileId, profile, listed, now, names)
        names
    }

    suspend fun withoutSeen(items: List<MetaPreview>): List<MetaPreview> = withContext(Dispatchers.Default) {
        val started = System.currentTimeMillis()
        val profile = store.current()
        val listed = listed()
        val names = namesToHide(profile, listed)
        val named = System.currentTimeMillis()
        items.filterNot { item ->
            val name = scoutNormalizedName(item.name)
            val year = item.releaseInfo?.take(4)?.toIntOrNull()
            item.id in listed.ids || name in names ||
                (year != null && (year - 1..year + 1).any { "$name|$it" in names })
        }.also {
            Log.i("ScoutSpeed", "seen names=${named - started} filter=${System.currentTimeMillis() - named} items=${items.size}")
        }
    }

    /** A rating, a watch or a list change: rows on Home read themselves again. */
    @OptIn(FlowPreview::class)
    val changes: Flow<Unit> = flow {
        emitAll(
            combine(
                store.profile.map { profile -> profile.ratings.mapValues { it.value.rating } },
                watchProgress.get().tasteHistory.map { items -> items.size },
                library.get().libraryItems.map { items -> items.map { it.id to it.listKeys } }
            ) { ratings, watched, lists -> Triple(ratings, watched, lists) }
                .distinctUntilChanged()
                .drop(1)
                .debounce(CHANGE_SETTLE_MS)
                .map { }
        )
    }.flowOn(Dispatchers.Default)
}
