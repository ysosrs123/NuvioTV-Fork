package com.nuvio.tv.core.calendar

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * One JSON file per profile in `filesDir/calendar`, written through a temporary file at most every [THROTTLE_MS]
 * unless forced, and kept under [MAX_FILE_BYTES]. A write that started before a clear is dropped.
 */
@Singleton
class CalendarStorage internal constructor(
    private val directory: () -> File,
    private val nowMs: () -> Long
) : ProfileScopedCredentialStore {

    @Inject
    constructor(@ApplicationContext context: Context) : this({ File(context.filesDir, DIRECTORY) }, System::currentTimeMillis)

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }
    private val lock = Any()
    private val generationState = MutableStateFlow(0)
    private val lastWriteAtMs = HashMap<Int, Long>()

    /** Goes up on every clear. Pass the value to [save]; a clear in between makes the save a no-op. */
    val generations: StateFlow<Int> = generationState.asStateFlow()

    fun generation(): Int = generationState.value

    suspend fun load(profileId: Int): CalendarSnapshot = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val file = file(profileId)
            if (!file.exists()) return@synchronized CalendarSnapshot()
            try {
                json.decodeFromString(CalendarSnapshot.serializer(), file.readText())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Calendar cache unreadable, starting empty: ${error.message}")
                CalendarSnapshot()
            }
        }
    }

    /** Returns false when the write was skipped (throttled, or the profile was cleared since [expectedGeneration]). */
    suspend fun save(
        profileId: Int,
        snapshot: CalendarSnapshot,
        expectedGeneration: Int,
        force: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (expectedGeneration != generationState.value) return@synchronized false
            val now = nowMs()
            val last = lastWriteAtMs[profileId]
            if (!force && last != null && now - last < THROTTLE_MS) return@synchronized false
            try {
                val bytes = encodeWithinLimit(boundCalendarSnapshot(snapshot, Instant.ofEpochMilli(now)).copy(updatedAtMs = now))
                val target = file(profileId)
                target.parentFile?.mkdirs()
                val tmp = File(target.parentFile, "${target.name}.tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                lastWriteAtMs[profileId] = now
                true
            } catch (error: Exception) {
                Log.w(TAG, "Failed to write calendar cache: ${error.message}")
                false
            }
        }
    }

    private fun encodeWithinLimit(snapshot: CalendarSnapshot): ByteArray {
        var current = snapshot
        while (true) {
            val bytes = json.encodeToString(CalendarSnapshot.serializer(), current).toByteArray()
            if (bytes.size <= MAX_FILE_BYTES) return bytes
            val trimmed = trimLeastActiveTitles(current, (current.titles.size / 10).coerceAtLeast(1))
            if (trimmed.titles.size == current.titles.size) return bytes
            current = trimmed
        }
    }

    override fun removeProfile(profileId: Int) = synchronized(lock) {
        generationState.value++
        lastWriteAtMs.remove(profileId)
        val target = file(profileId)
        target.delete()
        File(target.parentFile, "${target.name}.tmp").delete()
        Unit
    }

    override fun clearAllProfiles() = synchronized(lock) {
        generationState.value++
        lastWriteAtMs.clear()
        directory().deleteRecursively()
        Unit
    }

    internal fun file(profileId: Int): File = File(directory(), "calendar_$profileId.json")

    private companion object {
        const val TAG = "CalendarStorage"
        const val DIRECTORY = "calendar"
        const val THROTTLE_MS = 30_000L
        const val MAX_FILE_BYTES = 1_000_000
    }
}

private val SEEN_KEEP: Duration = Duration.ofDays(30)
private val TVMAZE_MISSING_KEEP: Duration = Duration.ofDays(30)
private const val MAX_TVMAZE_IDS = 1_000

fun boundCalendarSnapshot(snapshot: CalendarSnapshot, now: Instant): CalendarSnapshot {
    val seenSince = now.minus(SEEN_KEEP).toEpochMilli()
    val missingSince = now.minus(TVMAZE_MISSING_KEEP).toEpochMilli()
    return snapshot.copy(
        titles = snapshot.titles.take(CALENDAR_MAX_TITLES).map { it.bounded(now) },
        tvmazeIds = snapshot.tvmazeIds
            .filterValues { it.id != null || it.checkedAtMs >= missingSince }
            .entries.sortedByDescending { it.value.checkedAtMs }
            .take(MAX_TVMAZE_IDS)
            .associate { it.key to it.value },
        resolvedIds = snapshot.resolvedIds
            .filterValues { !it.ids.isEmpty || it.checkedAtMs >= now.minus(RESOLVED_MISSING_KEEP).toEpochMilli() }
            .entries.sortedByDescending { it.value.checkedAtMs }
            .take(MAX_RESOLVED_IDS)
            .associate { it.key to it.value },
        seenReminders = snapshot.seenReminders.filterValues { it >= seenSince }
    )
}

/**
 * Adds what this refresh found to the saved ids: [found] by the source reader, [looked] up from the add-ons. A miss
 * never replaces ids found before.
 */
fun mergeCalendarResolvedIds(
    saved: Map<String, CalendarResolvedIds>,
    found: Map<String, CalendarIds>,
    looked: Map<String, CalendarResolvedIds>,
    now: Instant
): Map<String, CalendarResolvedIds> {
    val merged = LinkedHashMap(saved)
    looked.forEach { (contentId, result) ->
        if (!result.ids.isEmpty || merged[contentId]?.ids?.isEmpty != false) merged[contentId] = result
    }
    found.forEach { (contentId, ids) -> merged[contentId] = CalendarResolvedIds(ids, now.toEpochMilli()) }
    return merged
}

fun Map<String, CalendarResolvedIds>.usableIds(): Map<String, CalendarIds> =
    entries.filter { !it.value.ids.isEmpty }.associate { it.key to it.value.ids }

/** Content ids still unread that are worth asking the add-ons about: not already known, and no miss in the last day. */
fun calendarIdsToLookUp(
    unresolved: List<Pair<CalendarTitleType, String>>,
    saved: Map<String, CalendarResolvedIds>,
    now: Instant,
    max: Int = MAX_ID_LOOKUPS
): List<Pair<CalendarTitleType, String>> {
    val retryBefore = now.minus(RESOLVED_MISSING_RETRY).toEpochMilli()
    return unresolved.distinctBy { it.second }
        .filter { (_, contentId) -> saved[contentId]?.let { it.ids.isEmpty && it.checkedAtMs < retryBefore } ?: true }
        .take(max)
}

private val RESOLVED_MISSING_KEEP: Duration = Duration.ofDays(30)
private val RESOLVED_MISSING_RETRY: Duration = Duration.ofDays(1)
private const val MAX_RESOLVED_IDS = 1_000
private const val MAX_ID_LOOKUPS = 10

/** Drops the [count] titles with the oldest activity; titles with the bell on stay. */
fun trimLeastActiveTitles(snapshot: CalendarSnapshot, count: Int): CalendarSnapshot {
    val dropped = snapshot.titles
        .filterNot { CalendarOrigin.BELL in it.origins }
        .sortedBy { it.lastActivityMs ?: Long.MIN_VALUE }
        .take(count)
        .mapTo(HashSet()) { it.key }
    return snapshot.copy(titles = snapshot.titles.filterNot { it.key in dropped })
}
