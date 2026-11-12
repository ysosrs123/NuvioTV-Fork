package com.nuvio.tv.core.debrid

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A TorBox item added for thumbnails. TorBox must have created it between [fromServerMs] and [toServerMs] (TorBox's
 * clock); 0 when that is not known, and then it is never removed.
 */
internal data class AddedCopy(
    val torrentId: Int,
    val hash: String,
    val addedAtMs: Long,
    val fromServerMs: Long = 0L,
    val toServerMs: Long = 0L,
)

internal interface AddedCopyStorage {
    fun load(): String?

    /** Written before it returns, false when it could not be: a record must be on disk before the next request. */
    fun save(value: String): Boolean
}

/**
 * TorBox items added for thumbnails and not removed yet, kept on disk, and what playback claimed this run. A claimed
 * item is released: kept in the user's list and never removed.
 */
internal class AddedCopyRecords(private val storage: AddedCopyStorage) {
    companion object {
        const val LEFTOVER_AFTER_MS = 10L * 60 * 1000
        const val KEEP_TRYING_MS = 6L * 60 * 60 * 1000

        fun encode(records: List<AddedCopy>): String = records.joinToString(";") {
            "${it.torrentId},${it.hash},${it.addedAtMs},${it.fromServerMs},${it.toServerMs}"
        }

        fun decode(value: String?): List<AddedCopy> = value.orEmpty().split(';').mapNotNull { r ->
            val f = r.split(',')
            if (f.size != 5) return@mapNotNull null
            val id = f[0].toIntOrNull() ?: return@mapNotNull null
            val hash = f[1].trim().lowercase().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            AddedCopy(
                id, hash, f[2].toLongOrNull() ?: return@mapNotNull null,
                f[3].toLongOrNull() ?: 0L, f[4].toLongOrNull() ?: 0L,
            )
        }

        fun normal(hash: String?): String? = hash?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    }

    private var list: MutableList<AddedCopy>? = null
    private val claimedHashes = HashSet<String>()
    private val claimedIds = HashSet<Int>()
    private val deleting = HashMap<String, CompletableDeferred<Unit>>()
    private val deletingIds = HashMap<Int, CompletableDeferred<Unit>>()

    private fun records(): MutableList<AddedCopy> =
        list ?: decode(runCatching { storage.load() }.getOrNull()).toMutableList().also { list = it }

    private fun write(): Boolean = runCatching { storage.save(encode(records())) }.getOrDefault(false)

    /** False when the record could not be written to disk. */
    @Synchronized
    fun add(record: AddedCopy): Boolean {
        val r = records()
        r.removeAll { it.torrentId == record.torrentId }
        r += record.copy(hash = normal(record.hash) ?: record.hash)
        return write()
    }

    @Synchronized
    fun of(torrentId: Int): AddedCopy? = records().firstOrNull { it.torrentId == torrentId }

    @Synchronized
    fun all(): List<AddedCopy> = records().toList()

    /** Removed from TorBox, gone from there, released or given up: the record goes. */
    @Synchronized
    fun forget(torrentId: Int) {
        if (records().removeAll { it.torrentId == torrentId }) write()
    }

    /**
     * Playback is on [hash] or TorBox item [torrentId] (memory only, no disk). Returns the removal of that item in
     * flight, if any, so playback can wait for its answer.
     */
    @Synchronized
    fun mark(hash: String?, torrentId: Int? = null): Deferred<Unit>? {
        val h = normal(hash)
        h?.let { claimedHashes += it }
        torrentId?.let { claimedIds += it }
        return h?.let { deleting[it] } ?: torrentId?.let { deletingIds[it] }
    }

    /** [mark], and matching records are released (kept in the list) and returned. */
    @Synchronized
    fun claim(hash: String?, torrentId: Int? = null): List<AddedCopy> {
        mark(hash, torrentId)
        val h = normal(hash)
        if (h == null && torrentId == null) return emptyList()
        val released = records().filter { it.hash == h || it.torrentId == torrentId }
        if (released.isNotEmpty()) {
            records().removeAll(released)
            write()
        }
        return released
    }

    @Synchronized
    fun claimed(hash: String?, torrentId: Int?): Boolean =
        normal(hash)?.let { it in claimedHashes } == true || (torrentId != null && torrentId in claimedIds)

    /**
     * Starts the delete of [record] unless playback claimed it: from here until [endDelete], a claim of the same hash
     * or id gets the removal back from [mark] and waits for it.
     */
    @Synchronized
    fun beginDelete(record: AddedCopy): CompletableDeferred<Unit>? {
        if (of(record.torrentId) == null || claimed(record.hash, record.torrentId)) return null
        return CompletableDeferred<Unit>().also {
            deleting[record.hash] = it
            deletingIds[record.torrentId] = it
        }
    }

    @Synchronized
    fun endDelete(record: AddedCopy, d: CompletableDeferred<Unit>) {
        if (deleting[record.hash] === d) deleting.remove(record.hash)
        if (deletingIds[record.torrentId] === d) deletingIds.remove(record.torrentId)
        d.complete(Unit)
    }

    /** Records from an earlier pass to remove: older than [LEFTOVER_AFTER_MS], not [inUse] by a copy now. */
    @Synchronized
    fun leftovers(nowMs: Long, inUse: Set<Int>): List<AddedCopy> =
        records().filter { it.torrentId !in inUse && nowMs - it.addedAtMs >= LEFTOVER_AFTER_MS }

    /** Records older than [KEEP_TRYING_MS]: no longer removed (another device may be playing it by now), forgotten. */
    @Synchronized
    fun giveUp(nowMs: Long): List<AddedCopy> {
        val old = records().filter { nowMs - it.addedAtMs >= KEEP_TRYING_MS }
        if (old.isNotEmpty()) {
            records().removeAll(old)
            write()
        }
        return old
    }
}

/** Whether a thumbnail copy may be removed from the user's TorBox list. Only [DELETE] removes it. */
internal enum class CopyRemoval {
    DELETE,
    /** Not added for thumbnails (it was in the list before): never removed. */
    NOT_OURS,
    /** Playback resolved it: kept. */
    WATCHING,
    /** The item under that id no longer has the recorded hash: kept. */
    CHANGED,
    /** TorBox did not create it while the app added it, or that cannot be told: kept. */
    OLDER,
    /** TorBox no longer has it. */
    GONE;

    companion object {
        /** [found]: the item was read again by id; [currentHash] and [createdAtMs] (TorBox's clock) are its values then. */
        fun decide(record: AddedCopy?, claimed: Boolean, found: Boolean, currentHash: String?, createdAtMs: Long?): CopyRemoval =
            when {
                record == null -> NOT_OURS
                claimed -> WATCHING
                !found -> GONE
                AddedCopyRecords.normal(currentHash) != record.hash -> CHANGED
                !createdWhileAdding(record, createdAtMs) -> OLDER
                else -> DELETE
            }

        fun createdWhileAdding(record: AddedCopy, createdAtMs: Long?): Boolean =
            createdAtMs != null && record.fromServerMs > 0L && record.toServerMs >= record.fromServerMs &&
                createdAtMs in record.fromServerMs..record.toServerMs
    }
}

internal object TorboxTime {
    /** Slack around TorBox's `Date` headers, which are to the second. */
    const val SLACK_MS = 2_000L

    /** TorBox's `created_at` in ms; a time without a zone is UTC. Null when it cannot be read. */
    fun parse(text: String?): Long? {
        val t = text?.trim()?.replace(' ', 'T')?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { OffsetDateTime.parse(t).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { Instant.parse(t).toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(t).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
    }
}

/** TorBox adds for thumbnails: [PER_TITLE] per playing title, none after TorBox refused one, for this run. */
internal class TorboxAddLimits {
    companion object {
        const val PER_TITLE = 3

        /** A TorBox answer that means no more adds: too many requests, or an account limit. */
        fun refuses(code: Int, text: String?): Boolean {
            if (code == 429) return true
            val t = text?.lowercase() ?: return false
            return "too many" in t || "limit" in t
        }
    }

    private val counts = HashMap<String, Int>()
    @Volatile var stoppedWhy: String? = null
        private set

    /** Why [title] may not get another add, null when it may. */
    @Synchronized
    fun refusal(title: String): String? {
        stoppedWhy?.let { return it }
        if ((counts[title] ?: 0) >= PER_TITLE) return "$PER_TITLE copies were added for this title already"
        return null
    }

    @Synchronized
    fun counted(title: String) {
        counts[title] = (counts[title] ?: 0) + 1
    }

    /** True when the answer stops adds for this run. */
    fun answered(code: Int, text: String?): Boolean {
        if (!refuses(code, text)) return false
        stoppedWhy = "TorBox refused an add (HTTP $code)"
        return true
    }
}

/** How a source's debrid copy is reached, or why it is left out. */
internal sealed class CopyRoute {
    data object Torbox : CopyRoute()
    data object Premiumize : CopyRoute()
    /**
     * An app debrid entry of another service, resolved with the app's key. Real-Debrid and AllDebrid only use titles
     * already in the account.
     */
    data object Resolve : CopyRoute()
    data class LeftOut(val why: String) : CopyRoute()

    companion object {
        /**
         * [directService]: the provider of an app debrid entry, null for any other source. [hasHash]: the source names
         * its torrent. Null when the source is reached by its own link (or is no debrid source at all).
         */
        fun of(directService: String?, hasHash: Boolean, torboxKey: Boolean, premiumizeKey: Boolean): CopyRoute? =
            when {
                directService != null -> when (DebridProviders.byId(directService)?.id) {
                    DebridProviders.TORBOX_ID -> if (torboxKey) Torbox else LeftOut("TorBox entry without a TorBox key")
                    DebridProviders.PREMIUMIZE_ID ->
                        if (premiumizeKey) Premiumize else LeftOut("Premiumize entry without a Premiumize key")
                    else -> Resolve
                }
                !hasHash -> null
                torboxKey -> Torbox
                premiumizeKey -> Premiumize
                else -> null
            }
    }
}

/** The app's record of TorBox items added for thumbnails, and the claims playback makes on them. */
@Singleton
class ThumbnailCopyAdded internal constructor(internal val records: AddedCopyRecords) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(AddedCopyRecords(PrefsAddedCopyStorage(context)))

    private companion object {
        const val DELETE_WAIT_MS = 70_000L
    }

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Any item added for thumbnails is still recorded. Reads the disk the first time: never on the main thread. */
    fun hasRecords(): Boolean = runCatching { records.all().isNotEmpty() }.getOrDefault(false)

    /** Playback is on [hash] or TorBox item [torrentId]: a thumbnail copy of it stays in the user's list. */
    fun claim(hash: String?, torrentId: Int? = null) {
        if (AddedCopyRecords.normal(hash) == null && torrentId == null) return
        records.mark(hash, torrentId)
        io.launch { release(hash, torrentId) }
    }

    /** [claim], and when a removal of that item is in flight, waits for its answer so playback can add the copy again. */
    suspend fun claimAndWait(hash: String?, torrentId: Int? = null) {
        if (AddedCopyRecords.normal(hash) == null && torrentId == null) return
        records.mark(hash, torrentId)?.let { withTimeoutOrNull(DELETE_WAIT_MS) { it.await() } }
        withContext(Dispatchers.IO) { release(hash, torrentId) }
    }

    private fun release(hash: String?, torrentId: Int?) {
        runCatching { records.claim(hash, torrentId) }.getOrNull()?.forEach {
            Log.i("ThumbCopy", "TorBox: thumbnail copy kept, you are watching it (id ${it.torrentId})")
        }
    }
}

private class PrefsAddedCopyStorage(context: Context) : AddedCopyStorage {
    private val prefs by lazy { context.getSharedPreferences("thumb_copy_added", Context.MODE_PRIVATE) }

    override fun load(): String? = prefs.getString("records", null)

    override fun save(value: String): Boolean = prefs.edit().putString("records", value).commit()
}
