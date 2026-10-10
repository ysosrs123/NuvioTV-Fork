package com.nuvio.tv.data.iptv

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement
import com.nuvio.tv.core.iptv.GuideChannel
import com.nuvio.tv.core.iptv.GuideImportChannels
import com.nuvio.tv.core.iptv.GuideImportCounts
import com.nuvio.tv.core.iptv.GuideImportFilter
import com.nuvio.tv.core.iptv.GuideImportResult
import com.nuvio.tv.core.iptv.GuideParseLimits
import com.nuvio.tv.core.iptv.GuideProgrammeCap
import com.nuvio.tv.core.iptv.GuideStorageCaps
import com.nuvio.tv.core.iptv.GUIDE_AIRING_LOOKBACK_MILLIS
import com.nuvio.tv.core.iptv.GUIDE_MIN_DATABASE_BYTES
import com.nuvio.tv.core.iptv.GuideFeedIndex
import com.nuvio.tv.core.iptv.GuideKey
import com.nuvio.tv.core.iptv.GuideNameIndex
import com.nuvio.tv.core.iptv.guideAiringAt
import com.nuvio.tv.core.iptv.guideChannelNameKeys
import com.nuvio.tv.core.iptv.guideDatabaseCap
import com.nuvio.tv.core.iptv.guideFeedBudget
import com.nuvio.tv.core.iptv.guideImportDecision
import com.nuvio.tv.core.iptv.guideMatchName
import com.nuvio.tv.core.iptv.guideProgrammeBytes
import com.nuvio.tv.core.iptv.guideSearchQuery
import com.nuvio.tv.core.iptv.guideSearchTitle
import com.nuvio.tv.core.iptv.RefreshDecision
import com.nuvio.tv.core.iptv.SPORTS_OPEN_ENDED_MILLIS
import com.nuvio.tv.core.iptv.SportsGuide
import com.nuvio.tv.core.iptv.XtreamGuideReference
import com.nuvio.tv.core.iptv.mergeGuideChannel
import com.nuvio.tv.core.iptv.parseGuideInput
import java.io.Closeable
import java.io.InputStream
import java.net.URI
import java.util.UUID
import org.json.JSONObject

class IptvGuideStore(
    context: Context, databaseName: String = "iptv-guide.db",
    private val secrets: IptvSecretBox = EnvelopeIptvSecretBox(AndroidIptvSecretBox()),
    private val maxDatabaseBytes: Long? = null,
    private val releaseDocument: (String) -> Unit = { uri ->
        context.applicationContext.contentResolver.releasePersistableUriPermission(android.net.Uri.parse(uri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    },
    private val now: () -> Long = System::currentTimeMillis,
) : Closeable {
    init { require(maxDatabaseBytes == null || maxDatabaseBytes >= 64 * 1024) }
    private val helper = Database(context.applicationContext, databaseName)
    private val folder = context.applicationContext.getDatabasePath(databaseName).parentFile
    @Volatile private var capBytes = maxDatabaseBytes ?: GUIDE_MIN_DATABASE_BYTES

    fun refreshCapacity(): Long {
        if (maxDatabaseBytes == null) capBytes = guideDatabaseCap(folder?.usableSpace ?: 0L)
        return capBytes
    }

    fun createFeed(profileId: Int, label: String, endpoint: String): IptvGuideRef = transaction { db ->
        validate(label, endpoint)
        val ref = IptvGuideRef(profileId, UUID.randomUUID().toString())
        db.insertOrThrow("feeds", null, ContentValues().apply {
            put("id", ref.feedId); put("profile", ref.profileId); put("label", label); put("version", 1L); put("requested", 0L)
            put("endpoint", secrets.seal(aad(ref, "endpoint"), endpoint))
        })
        ref
    }

    fun editFeed(ref: IptvGuideRef, label: String, endpoint: String) = transaction { db ->
        validate(label, endpoint)
        val old = feed(db, ref)
        val previous = endpoint(db, ref)
        val changed = previous != endpoint
        db.update("feeds", ContentValues().apply {
            put("label", label)
            if (changed) {
                put("version", Math.addExact(old.configurationVersion, 1)); put("requested", Math.addExact(old.requestedGeneration, 1))
                put("endpoint", secrets.seal(aad(ref, "endpoint"), endpoint)); putNull("validators")
            }
        }, "id=?", arrayOf(ref.feedId))
        if (changed && isDocument(previous) && previous.trim() !in documents(db)) listOf(previous.trim()) else emptyList()
    }.let(::release)

    fun removeFeed(ref: IptvGuideRef) = transaction { db ->
        feed(db, ref)
        val previous = endpoint(db, ref)
        db.delete("stages", "feed=?", arrayOf(ref.feedId))
        db.delete("feeds", "id=? AND profile=?", arrayOf(ref.feedId, ref.profileId.toString())).also { check(it == 1) }
        if (isDocument(previous) && previous.trim() !in documents(db)) listOf(previous.trim()) else emptyList()
    }.let(::release).also { reclaim() }

    fun endpoint(ref: IptvGuideRef): String = transaction { db -> feed(db, ref); endpoint(db, ref) }
    fun feed(ref: IptvGuideRef): IptvGuideFeed = transaction { db -> feed(db, ref) }

    fun removeProfile(profileId: Int) = transaction { db ->
        require(profileId >= 0)
        val args = arrayOf(profileId.toString())
        val removed = documents(db, profileId)
        db.delete("stages", "feed IN (SELECT id FROM feeds WHERE profile=?)", args)
        db.delete("feeds", "profile=?", args)
        removed - documents(db)
    }.let(::release).also { reclaim() }

    fun clearAllProfiles() = transaction { db ->
        val removed = documents(db)
        db.delete("stages", null, null); db.delete("feeds", null, null)
        removed
    }.let(::release).also { reclaim() }

    private fun isDocument(endpoint: String) = endpoint.trim().startsWith("content:")
    private fun documents(db: SQLiteDatabase, profileId: Int? = null): Set<String> =
        db.rawQuery("SELECT id,profile,endpoint FROM feeds" + if (profileId == null) "" else " WHERE profile=?",
            profileId?.let { arrayOf(it.toString()) }).use { c -> buildSet {
            while (c.moveToNext()) {
                val endpoint = secrets.open(aad(IptvGuideRef(c.getInt(1), c.getString(0)), "endpoint"), c.getBlob(2))
                if (isDocument(endpoint)) add(endpoint.trim())
            }
        } }
    private fun release(uris: Collection<String>) { for (uri in uris) runCatching { releaseDocument(uri) } }

    fun feeds(profileId: Int, offset: Int = 0, limit: Int = 100): List<IptvGuideFeed> = read { db ->
        require(profileId >= 0 && offset >= 0 && limit in 1..200)
        db.rawQuery("SELECT id,label,version,requested,active_generation,refreshed_at FROM feeds WHERE profile=? ORDER BY label COLLATE NOCASE,id LIMIT ? OFFSET ?",
            arrayOf(profileId.toString(), limit.toString(), offset.toString())).use { c -> buildList {
                while (c.moveToNext()) add(IptvGuideFeed(IptvGuideRef(profileId, c.getString(0)), c.getString(1), c.getLong(2), c.getLong(3),
                    if (c.isNull(4)) null else c.getLong(4), if (c.isNull(5)) null else c.getLong(5)))
            } }
    }

    fun matchingIndexes(profileId: Int, feedIds: List<String>, externalIds: Set<String>): List<GuideFeedIndex> = read { db ->
        require(profileId >= 0 && feedIds.size <= 16 && feedIds.distinct().size == feedIds.size)
        feedIds.forEach { IptvGuideRef(profileId, it) }
        require(externalIds.size <= 600 && externalIds.all { it.isNotBlank() && it.length <= 4096 })
        val matches = feedIds.associateWith { mutableSetOf<String>() }
        if (feedIds.isNotEmpty() && externalIds.isNotEmpty()) {
            val args = arrayOf(profileId.toString(), *feedIds.toTypedArray(), *externalIds.toTypedArray())
            db.rawQuery("SELECT f.id,c.external_id FROM feeds f JOIN channels c ON c.stage=f.active_stage WHERE f.profile=? AND f.version=f.active_version AND f.id IN (${feedIds.joinToString(",") { "?" }}) AND c.external_id IN (${externalIds.joinToString(",") { "?" }})", args).use { c ->
                while (c.moveToNext()) matches.getValue(c.getString(0)).add(c.getString(1))
            }
        }
        feedIds.map { GuideFeedIndex(it, matches.getValue(it)) }
    }

    fun nameIndexes(profileId: Int, feedIds: List<String>, names: Set<String>): List<GuideNameIndex> = read { db ->
        require(profileId >= 0 && feedIds.size <= 16 && feedIds.distinct().size == feedIds.size && names.size <= 400)
        val found = feedIds.associateWith { mutableMapOf<String, MutableSet<String>>() }
        if (feedIds.isNotEmpty() && names.isNotEmpty()) {
            db.rawQuery("SELECT f.id,n.name,n.external_id FROM feeds f JOIN channel_names n ON n.stage=f.active_stage WHERE f.profile=? AND f.version=f.active_version AND f.id IN (${feedIds.joinToString(",") { "?" }}) AND n.name IN (${names.joinToString(",") { "?" }})",
                arrayOf(profileId.toString(), *feedIds.toTypedArray(), *names.toTypedArray())).use { c ->
                while (c.moveToNext()) found.getValue(c.getString(0)).getOrPut(c.getString(1)) { mutableSetOf() }.add(c.getString(2))
            }
        }
        feedIds.map { GuideNameIndex(it, found.getValue(it)) }
    }

    fun programmeKeys(profileId: Int, keys: Collection<GuideKey>, window: IptvGuideWindow): Set<GuideKey> = read { db ->
        val feedIds = keys.map { it.feedId }.distinct()
        require(profileId >= 0 && feedIds.size <= 16 && keys.all { it.externalId.isNotBlank() && it.externalId.length <= 4096 })
        feedIds.forEach { IptvGuideRef(profileId, it) }
        val wanted = keys.toSet()
        val found = HashSet<GuideKey>()
        for (chunk in keys.map { it.externalId }.distinct().chunked(400)) {
            db.rawQuery("SELECT f.id,p.external_id FROM feeds f CROSS JOIN programmes p WHERE f.profile=? AND f.version=f.active_version AND f.id IN (${feedIds.joinToString(",") { "?" }}) AND p.stage=f.active_stage AND p.external_id IN (${chunk.joinToString(",") { "?" }}) AND p.start<? AND (p.stop>? OR (p.stop IS NULL AND p.start>=?)) GROUP BY f.id,p.external_id",
                arrayOf(profileId.toString(), *feedIds.toTypedArray(), *chunk.toTypedArray(), window.untilMillis.toString(), window.fromMillis.toString(), window.fromMillis.toString())).use { c ->
                while (c.moveToNext()) GuideKey(c.getString(0), c.getString(1)).takeIf(wanted::contains)?.let(found::add)
            }
        }
        found
    }

    fun searchChannels(ref: IptvGuideRef, query: String, limit: Int = 60): List<GuideChannel> = read { db ->
        require(limit in 1..200 && query.length <= 256)
        feed(db, ref)
        val name = guideMatchName(query)
        val sql = if (name.isEmpty()) "SELECT c.external_id,c.payload FROM channels c JOIN feeds f ON c.stage=f.active_stage WHERE f.id=? AND f.profile=? AND f.version=f.active_version ORDER BY c.external_id LIMIT ?"
            else "SELECT c.external_id,c.payload FROM channels c JOIN feeds f ON c.stage=f.active_stage WHERE f.id=? AND f.profile=? AND f.version=f.active_version AND c.external_id IN (SELECT n.external_id FROM channel_names n WHERE n.stage=f.active_stage AND instr(n.name,?)>0) ORDER BY c.external_id LIMIT ?"
        val args = if (name.isEmpty()) arrayOf(ref.feedId, ref.profileId.toString(), limit.toString()) else arrayOf(ref.feedId, ref.profileId.toString(), name, limit.toString())
        db.rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) add(IptvGuideJson.channel(c.getString(1), c.getString(0))) } }
    }

    fun airingMatches(profileId: Int, feedIds: List<String>, query: String, nowMillis: Long, limit: Int = 200): List<IptvAiringMatch> = read { db ->
        require(profileId >= 0 && feedIds.size <= 16 && feedIds.distinct().size == feedIds.size && limit in 1..500)
        feedIds.forEach { IptvGuideRef(profileId, it) }
        val text = guideSearchQuery(query) ?: return@read emptyList()
        val now = nowMillis.toString(); val since = (nowMillis - GUIDE_AIRING_LOOKBACK_MILLIS).toString()
        val found = mutableListOf<IptvAiringMatch>()
        for (feed in feedIds) {
            if (found.size >= limit) break
            db.rawQuery("SELECT c.external_id,c.payload,p.payload FROM feeds f CROSS JOIN channels c CROSS JOIN programmes p WHERE f.id=? AND f.profile=? AND f.version=f.active_version AND c.stage=f.active_stage AND p.stage=c.stage AND p.external_id=c.external_id AND p.start<=? AND p.start>? AND (p.stop>? OR (p.stop IS NULL AND NOT EXISTS (SELECT 1 FROM programmes q WHERE q.stage=p.stage AND q.external_id=p.external_id AND q.start>p.start AND q.start<=?))) AND instr(p.search_title,?)>0 ORDER BY c.external_id,p.start,p.id LIMIT ?",
                arrayOf(feed, profileId.toString(), now, since, now, now, text, (limit - found.size).toString())).use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val channel = IptvGuideJson.channel(c.getString(1), id); val programme = IptvGuideJson.programme(c.getString(2), id)
                    if (guideAiringAt(programme, nowMillis)) found += IptvAiringMatch(GuideKey(feed, id), channel, programme)
                }
            }
        }
        found
    }

    fun sportsMatches(profileId: Int, feedIds: List<String>, nowMillis: Long, untilMillis: Long, limit: Int = 400,
        lookbackMillis: Long = GUIDE_AIRING_LOOKBACK_MILLIS): List<IptvAiringMatch> = read { db ->
        require(profileId >= 0 && feedIds.size <= 16 && feedIds.distinct().size == feedIds.size && limit in 1..5000 && untilMillis >= nowMillis &&
            lookbackMillis in 0..GUIDE_AIRING_LOOKBACK_MILLIS)
        feedIds.forEach { IptvGuideRef(profileId, it) }
        val found = mutableListOf<IptvAiringMatch>()
        for ((index, feed) in feedIds.withIndex()) {
            val share = (limit - found.size) / (feedIds.size - index)
            if (share <= 0) break
            db.rawQuery("SELECT c.external_id,c.payload,p.payload FROM feeds f CROSS JOIN programmes p CROSS JOIN channels c WHERE f.id=? AND f.profile=? AND f.version=f.active_version AND p.stage=f.active_stage AND p.sport=1 AND p.start<=? AND p.start>? AND (p.stop>? OR (p.stop IS NULL AND p.start>?)) AND c.stage=p.stage AND c.external_id=p.external_id ORDER BY p.start,p.id LIMIT ?",
                arrayOf(feed, profileId.toString(), untilMillis.toString(), (nowMillis - lookbackMillis).toString(), nowMillis.toString(),
                    (nowMillis - SPORTS_OPEN_ENDED_MILLIS).toString(), share.toString())).use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val channel = IptvGuideJson.channel(c.getString(1), id); val programme = IptvGuideJson.programme(c.getString(2), id)
                    if (programme.start.precise) found += IptvAiringMatch(GuideKey(feed, id), channel, programme)
                }
            }
        }
        found
    }

    fun beginRefresh(ref: IptvGuideRef): IptvGuideTicket = transaction { db -> beginRefresh(db, ref) }

    fun prepareRefresh(ref: IptvGuideRef, window: IptvGuideWindow, filterKey: String? = null): IptvGuideRefreshRequest = transaction { db ->
        val ticket = beginRefresh(db, ref)
        IptvGuideRefreshRequest(ticket, endpoint(db, ref), if (covers(db, ref, window) && sameFilter(db, ref, filterKey)) validators(db, ref) else null)
    }

    fun upToDate(ref: IptvGuideRef, window: IptvGuideWindow, filterKey: String?, maxAgeMillis: Long): Boolean = transaction { db ->
        feed(db, ref)
        covers(db, ref, window) && sameFilter(db, ref, filterKey) &&
            db.rawQuery("SELECT 1 FROM feeds WHERE id=? AND active_stage IS NOT NULL AND refreshed_at>=?", arrayOf(ref.feedId, (now() - maxAgeMillis).toString())).use { it.moveToFirst() }
    }

    fun acceptNotModified(ticket: IptvGuideTicket, window: IptvGuideWindow): Boolean = transaction { db ->
        if (!current(db, ticket)) return@transaction false
        val cache = validators(db, ticket.ref)
        (current(db, ticket) && covers(db, ticket.ref, window) && cache != null && (cache.etag != null || cache.lastModified != null)).also { accepted ->
            if (accepted) db.update("feeds", ContentValues().apply { put("refreshed_at", now()) }, "id=?", arrayOf(ticket.ref.feedId))
        }
    }

    fun dropActive(ref: IptvGuideRef) {
        val stages = transaction { db ->
            feed(db, ref)
            val active = db.rawQuery("SELECT active_stage FROM feeds WHERE id=? AND active_stage IS NOT NULL", arrayOf(ref.feedId)).use { if (it.moveToFirst()) listOf(it.getLong(0)) else emptyList() }
            db.update("feeds", ContentValues().apply {
                for (column in listOf("active_stage", "active_generation", "active_version", "validators", "window_from", "window_until", "filter_key")) putNull(column)
            }, "id=?", arrayOf(ref.feedId))
            active
        }
        purge(stages)
        reclaim()
    }

    private fun covers(db: SQLiteDatabase, ref: IptvGuideRef, window: IptvGuideWindow): Boolean =
        db.rawQuery("SELECT 1 FROM feeds WHERE id=? AND profile=? AND version=active_version AND window_from<=? AND window_until>=?",
            arrayOf(ref.feedId, ref.profileId.toString(), window.fromMillis.toString(), window.untilMillis.toString())).use { it.moveToFirst() }

    private fun sameFilter(db: SQLiteDatabase, ref: IptvGuideRef, filterKey: String?): Boolean =
        filterKey == null || filterKey(db, ref) == filterKey

    private fun filterKey(db: SQLiteDatabase, ref: IptvGuideRef): String? =
        db.rawQuery("SELECT filter_key FROM feeds WHERE id=?", arrayOf(ref.feedId)).use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }

    private fun beginRefresh(db: SQLiteDatabase, ref: IptvGuideRef): IptvGuideTicket {
        val old = feed(db, ref)
        val generation = Math.addExact(old.requestedGeneration, 1)
        db.update("feeds", ContentValues().apply { put("requested", generation) }, "id=?", arrayOf(ref.feedId))

        db.delete("stages", "feed=? AND generation<? AND id NOT IN (SELECT active_stage FROM feeds WHERE active_stage IS NOT NULL)", arrayOf(ref.feedId, generation.toString()))
        return IptvGuideTicket(ref, old.configurationVersion, generation)
    }

    fun validators(ref: IptvGuideRef): IptvCacheValidators? = transaction { db -> validators(db, ref) }

    private fun validators(db: SQLiteDatabase, ref: IptvGuideRef): IptvCacheValidators? {
        feed(db, ref)
        return db.rawQuery("SELECT validators FROM feeds WHERE id=? AND version=active_version", arrayOf(ref.feedId)).use {
            if (!it.moveToFirst() || it.isNull(0)) null else JSONObject(secrets.open(aad(ref, "validators"), it.getBlob(0))).let { json ->
                IptvCacheValidators(json.optional("etag"), json.optional("lastModified"))
            }
        }
    }

    fun importGuide(
        ticket: IptvGuideTicket, input: InputStream, window: IptvGuideWindow,
        validators: IptvCacheValidators = IptvCacheValidators(),
        limits: GuideParseLimits = GuideParseLimits(),
        filter: GuideImportFilter? = null,
        checkCancellation: () -> Unit = {},
        caps: GuideStorageCaps = GuideStorageCaps(),
    ): RefreshDecision = importGuideResult(ticket, input, window, validators, limits, filter, checkCancellation, caps).decision

    fun importGuideResult(
        ticket: IptvGuideTicket, input: InputStream, window: IptvGuideWindow,
        validators: IptvCacheValidators = IptvCacheValidators(),
        limits: GuideParseLimits = GuideParseLimits(),
        filter: GuideImportFilter? = null,
        checkCancellation: () -> Unit = {},
        caps: GuideStorageCaps = GuideStorageCaps(),
    ): GuideImportResult {
        require(listOf(validators.etag, validators.lastModified).all { it == null || (it.length <= 4096 && '\r' !in it && '\n' !in it) })
        val cap = refreshCapacity()
        val claim = transaction { db ->
            if (!current(db, ticket)) return@transaction null
            val others = count(db, "SELECT COUNT(*) FROM feeds WHERE active_stage IS NOT NULL AND id<>?", arrayOf(ticket.ref.feedId))
            val kept = db.rawQuery("SELECT 1 FROM feeds WHERE id=? AND active_stage IS NOT NULL", arrayOf(ticket.ref.feedId)).use { it.moveToFirst() }
            db.insertWithOnConflict("stages", null, ContentValues().apply { put("feed", ticket.ref.feedId); put("generation", ticket.generation) }, SQLiteDatabase.CONFLICT_IGNORE)
                .takeIf { it != -1L }?.let { it to guideFeedBudget(cap, others.toInt(), kept) }
        } ?: return GuideImportResult(RefreshDecision.STALE)
        val (stage, budget) = claim
        val db = helper.writableDatabase
        var published = false
        var importFailure: Throwable? = null
        val statements = mutableListOf<SQLiteStatement>()
        try {
            val channelRow = db.compileStatement("INSERT OR IGNORE INTO channels(stage,external_id,payload) VALUES(?,?,?)").also(statements::add)
            val nameRow = db.compileStatement("INSERT OR IGNORE INTO channel_names(stage,external_id,name) VALUES(?,?,?)").also(statements::add)
            val programmeRow = db.compileStatement("INSERT OR IGNORE INTO programmes(stage,external_id,start,stop,precise,payload,search_title,sport) VALUES(?,?,?,?,?,?,?,?)").also(statements::add)
            val channels = GuideImportChannels(filter, caps.channels)
            val programmeCap = GuideProgrammeCap(caps)
            val sportsChannels = HashSet<String>()
            val newChannels = ArrayList<GuideChannel>()
            val duplicates = ArrayList<GuideChannel>()
            val rows = ArrayList<ProgrammeRow>()
            var buffered = 0L
            var inFile = 0L; var matched = 0L; var inWindow = 0L; var rejected = 0L; var stored = 0L; var bytes = 0L; var overBudget = 0L
            fun names(channel: GuideChannel) {
                for (name in guideChannelNameKeys(channel)) {
                    nameRow.clearBindings(); nameRow.bindLong(1, stage); nameRow.bindString(2, channel.externalId); nameRow.bindString(3, name); nameRow.executeInsert()
                }
            }
            fun flush() {
                if (newChannels.isEmpty() && duplicates.isEmpty() && rows.isEmpty()) return
                checkCancellation()
                transaction { tx ->
                    if (!current(tx, ticket)) throw StaleImport()
                    for (channel in newChannels) {
                        channelRow.clearBindings(); channelRow.bindLong(1, stage); channelRow.bindString(2, channel.externalId); channelRow.bindString(3, IptvGuideJson.channel(channel))
                        channelRow.executeInsert()
                        names(channel)
                    }
                    for (channel in duplicates) {
                        val existing = tx.rawQuery("SELECT payload FROM channels WHERE stage=? AND external_id=?", arrayOf(stage.toString(), channel.externalId)).use {
                            if (it.moveToFirst()) IptvGuideJson.channel(it.getString(0), channel.externalId) else null
                        } ?: continue
                        val merged = mergeGuideChannel(existing, channel)
                        if (merged != existing) tx.update("channels", ContentValues().apply { put("payload", IptvGuideJson.channel(merged)) },
                            "stage=? AND external_id=?", arrayOf(stage.toString(), channel.externalId))
                        names(channel)
                    }
                    for (row in rows) {
                        programmeRow.clearBindings()
                        programmeRow.bindLong(1, stage); programmeRow.bindString(2, row.externalId); programmeRow.bindLong(3, row.start)
                        row.stop?.let { programmeRow.bindLong(4, it) } ?: programmeRow.bindNull(4)
                        programmeRow.bindLong(5, if (row.precise) 1 else 0); programmeRow.bindString(6, row.payload)
                        row.searchTitle?.let { programmeRow.bindString(7, it) } ?: programmeRow.bindNull(7)
                        if (row.sport) programmeRow.bindLong(8, 1) else programmeRow.bindNull(8)
                        if (programmeRow.executeInsert() != -1L) stored++
                    }
                }
                newChannels.clear(); duplicates.clear(); rows.clear(); buffered = 0
            }
            fun added(size: Int) {
                buffered += size
                if (newChannels.size + duplicates.size + rows.size >= BATCH_ROWS || buffered >= BATCH_CHARACTERS) flush()
            }
            checkCancellation()
            val summary = parseGuideInput(input, channel = { channel ->
                when (channels.channel(channel)) {
                    GuideImportChannels.Admission.NEW -> {
                        newChannels += channel
                        if (SportsGuide.isSportsChannel(channel.names)) sportsChannels += channel.externalId
                        added(channel.names.sumOf { it.text.length } + channel.externalId.length)
                    }
                    GuideImportChannels.Admission.DUPLICATE -> {
                        duplicates += channel.copy(externalId = requireNotNull(channels.storedId(channel.externalId)))
                        added(channel.names.sumOf { it.text.length } + channel.externalId.length)
                    }
                    GuideImportChannels.Admission.SKIPPED -> Unit
                }
            }, programme = programme@{ parsed ->
                val id = channels.programme(parsed.channelExternalId) ?: return@programme
                matched++
                val start = parsed.start.epochMillis
                if (start >= window.untilMillis || !(parsed.stop?.epochMillis?.let { it > window.fromMillis } ?: (start >= window.fromMillis))) return@programme
                inWindow++
                val programme = programmeCap.admit(if (parsed.channelExternalId == id) parsed else parsed.copy(channelExternalId = id)) ?: return@programme
                val payload = IptvGuideJson.programme(programme)
                val searchTitle = guideSearchTitle(programme.titles)
                val size = guideProgrammeBytes(id, payload, searchTitle)
                if (bytes + size > budget) { overBudget++; return@programme }
                bytes += size
                rows += ProgrammeRow(id, start, programme.stop?.epochMillis, programme.canSchedulePrecisely, payload, searchTitle,
                    SportsGuide.isSportsProgramme(programme.titles, programme.categories, id in sportsChannels))
                added(payload.length)
            }, limits = limits, checkCancellation = checkCancellation, rejected = { id, start ->
                if (channels.wants(id) && (start == null || (start < window.untilMillis && start >= window.fromMillis - REJECTED_LOOKBACK_MILLIS))) rejected++
            }, wants = { channels.programme(it) != null })
            inFile = summary.programmes.toLong()
            flush()
            for (channel in channels.impliedChannels) { newChannels += channel; added(channel.externalId.length) }
            flush()
            if (channels.renames.isNotEmpty()) transaction { tx ->
                if (!current(tx, ticket)) throw StaleImport()
                for ((from, to) in channels.renames) {
                    tx.execSQL("UPDATE OR IGNORE programmes SET external_id=? WHERE stage=? AND external_id=?", arrayOf<Any>(to, stage, from))
                    tx.delete("programmes", "stage=? AND external_id=?", arrayOf(stage.toString(), from))
                }
            }
            checkCancellation()
            val result = transaction { tx ->
                if (!current(tx, ticket)) return@transaction GuideImportResult(RefreshDecision.STALE) to emptyList<Long>()
                val comparable = filter == null || filterKey(tx, ticket.ref) == filter.fingerprint
                val previous = if (!comparable) 0L else count(tx, "SELECT COUNT(*) FROM programmes p JOIN feeds f ON f.active_stage=p.stage WHERE f.id=? AND f.version=f.active_version AND p.start<? AND (p.stop>? OR (p.stop IS NULL AND p.start>=?))",
                    arrayOf(ticket.ref.feedId, window.untilMillis.toString(), window.fromMillis.toString(), window.fromMillis.toString()))
                val decision = guideImportDecision(GuideImportCounts(channels.channelCount + channels.skipped, inFile, matched, inWindow, stored, rejected,
                    previous, filter == null || !filter.empty, comparable))
                IptvLog.info("guide import channels=${channels.channelCount} skipped=${channels.skipped} file=${summary.programmes} matched=$matched window=$inWindow " +
                    "stored=$stored rejected=$rejected capped=${programmeCap.dropped} budget=$overBudget previous=$previous decision=${decision.decision}${decision.issue?.let { " issue=$it" }.orEmpty()}")
                if (decision.decision != RefreshDecision.PUBLISH) return@transaction decision to emptyList<Long>()
                checkCancellation()
                tx.update("feeds", ContentValues().apply {
                    put("active_stage", stage); put("active_generation", ticket.generation); put("active_version", ticket.configurationVersion); put("refreshed_at", now())
                    put("window_from", window.fromMillis); put("window_until", window.untilMillis)
                    if (filter == null) putNull("filter_key") else put("filter_key", filter.fingerprint)
                    put("validators", secrets.seal(aad(ticket.ref, "validators"), JSONObject().put("etag", validators.etag).put("lastModified", validators.lastModified).toString()))
                }, "id=? AND version=? AND requested=?", arrayOf(ticket.ref.feedId, ticket.configurationVersion.toString(), ticket.generation.toString())).also { check(it == 1) }
                decision to tx.rawQuery("SELECT id FROM stages WHERE feed=? AND id<>?", arrayOf(ticket.ref.feedId, stage.toString())).use { c ->
                    buildList { while (c.moveToNext()) add(c.getLong(0)) }
                }
            }
            published = result.first.decision == RefreshDecision.PUBLISH
            if (published) {
                try { purge(result.second) } catch (error: Exception) { IptvLog.failure("guide cleanup", error) }
                reclaim()
            }
            return result.first
        } catch (_: StaleImport) {
            return GuideImportResult(RefreshDecision.STALE)
        } catch (error: Throwable) {
            importFailure = error
            throw error
        } finally {
            statements.forEach { runCatching { it.close() } }
            if (!published) {
                try { purge(listOf(stage)) }
                catch (cleanup: Throwable) { if (importFailure == null) throw cleanup else importFailure.addSuppressed(cleanup) }
            }
        }
    }

    private fun purge(stages: List<Long>) {
        for (stage in stages) {
            val args = arrayOf(stage.toString())
            if (transaction { db -> db.rawQuery("SELECT 1 FROM feeds WHERE active_stage=?", args).use { it.moveToFirst() } }) continue
            for (table in listOf("programmes", "channel_names", "channels")) {
                while (transaction { db -> db.delete(table, "rowid IN (SELECT rowid FROM $table WHERE stage=? LIMIT $PURGE_ROWS)", args) } >= PURGE_ROWS) Unit
            }
            transaction { db -> db.delete("stages", "id=? AND id NOT IN (SELECT active_stage FROM feeds WHERE active_stage IS NOT NULL)", args) }
        }
    }

    private fun reclaim() {
        try {
            val db = helper.writableDatabase
            val incremental = db.rawQuery("PRAGMA auto_vacuum", null).use { it.moveToFirst() && it.getInt(0) == 2 }
            repeat(if (incremental) MAX_VACUUM_STEPS else 1) {
                val free = if (!incremental) 0L else db.rawQuery("PRAGMA freelist_count", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
                if (free > 0) db.rawQuery("PRAGMA incremental_vacuum($VACUUM_PAGES)", null).use { it.count }
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                if (free <= VACUUM_PAGES) return
            }
        } catch (error: Exception) { IptvLog.failure("guide reclaim", error) }
    }

    fun programmes(ref: IptvGuideRef, externalId: String, window: IptvGuideWindow, offset: Int = 0, limit: Int = 100): IptvProgrammePage = read { db ->
        require(offset >= 0 && limit in 1..200 && externalId.isNotBlank())
        feed(db, ref)
        val items = db.rawQuery("SELECT p.payload FROM programmes p JOIN feeds f ON p.stage=f.active_stage WHERE f.id=? AND f.profile=? AND f.version=f.active_version AND p.external_id=? AND p.start<? AND (p.stop>? OR (p.stop IS NULL AND p.start>=?)) ORDER BY p.start,p.id LIMIT ? OFFSET ?",
            arrayOf(ref.feedId, ref.profileId.toString(), externalId, window.untilMillis.toString(), window.fromMillis.toString(), window.fromMillis.toString(), (limit + 1).toString(), offset.toString())).use { c ->
            buildList { while (c.moveToNext()) add(IptvGuideJson.programme(c.getString(0), externalId)) }
        }
        IptvProgrammePage(items.take(limit), items.size > limit)
    }

    fun channelPage(ref: IptvGuideRef, offset: Int = 0, limit: Int = 200): List<GuideChannel> = read { db ->
        require(offset >= 0 && limit in 1..500)
        feed(db, ref)
        db.rawQuery("SELECT c.external_id,c.payload FROM channels c JOIN feeds f ON c.stage=f.active_stage WHERE f.id=? AND f.profile=? AND f.version=f.active_version ORDER BY c.external_id LIMIT ? OFFSET ?",
            arrayOf(ref.feedId, ref.profileId.toString(), limit.toString(), offset.toString())).use { c -> buildList { while (c.moveToNext()) add(IptvGuideJson.channel(c.getString(1), c.getString(0))) } }
    }

    private fun current(db: SQLiteDatabase, ticket: IptvGuideTicket): Boolean {
        if (db.rawQuery("SELECT 1 FROM feeds WHERE id=? AND profile=?", arrayOf(ticket.ref.feedId, ticket.ref.profileId.toString())).use { !it.moveToFirst() }) return false
        val current = feed(db, ticket.ref)
        return ticket.generation > 0 && current.configurationVersion == ticket.configurationVersion && current.requestedGeneration == ticket.generation && current.activeGeneration != ticket.generation
    }
    private fun feed(db: SQLiteDatabase, ref: IptvGuideRef): IptvGuideFeed = db.rawQuery("SELECT label,version,requested,active_generation,refreshed_at FROM feeds WHERE id=? AND profile=?", arrayOf(ref.feedId, ref.profileId.toString())).use {
        require(it.moveToFirst()) { "Unknown IPTV guide feed" }
        IptvGuideFeed(ref, it.getString(0), it.getLong(1), it.getLong(2), if (it.isNull(3)) null else it.getLong(3), if (it.isNull(4)) null else it.getLong(4))
    }
    private fun endpoint(db: SQLiteDatabase, ref: IptvGuideRef): String = db.rawQuery("SELECT endpoint FROM feeds WHERE id=? AND profile=?", arrayOf(ref.feedId, ref.profileId.toString())).use {
        require(it.moveToFirst()); secrets.open(aad(ref, "endpoint"), it.getBlob(0))
    }
    private fun <T> read(block: (SQLiteDatabase) -> T): T = block(helper.writableDatabase)
    private fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = helper.writableDatabase
        db.beginTransactionNonExclusive()
        var failure: Throwable? = null
        return try {
            db.setMaximumSize(capBytes)
            block(db).also { db.setTransactionSuccessful() }
        } catch (error: Throwable) { failure = error; throw error } finally {
            try { db.endTransaction() } catch (cleanup: Throwable) { if (failure == null) throw cleanup else failure.addSuppressed(cleanup) }
        }
    }
    override fun close() = helper.close()
    private class StaleImport : RuntimeException()
    private class ProgrammeRow(val externalId: String, val start: Long, val stop: Long?, val precise: Boolean, val payload: String, val searchTitle: String?, val sport: Boolean)
    private class Database(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 6) {
        init { require(name.matches(Regex("[A-Za-z0-9_.-]+"))); setWriteAheadLoggingEnabled(true) }
        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
            try {
                db.execSQL("PRAGMA auto_vacuum=INCREMENTAL")
                db.execSQL("PRAGMA synchronous=NORMAL")
                db.rawQuery("PRAGMA journal_size_limit=$JOURNAL_LIMIT_BYTES", null).use { it.moveToFirst() }
            } catch (error: Exception) { IptvLog.failure("guide configure", error) }
        }
        override fun onCreate(db: SQLiteDatabase) = createSchema(db)
        private fun createSchema(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE feeds(id TEXT PRIMARY KEY, profile INTEGER NOT NULL, label TEXT NOT NULL, endpoint BLOB NOT NULL, version INTEGER NOT NULL, requested INTEGER NOT NULL, active_stage INTEGER, active_generation INTEGER, active_version INTEGER, validators BLOB, window_from INTEGER, window_until INTEGER, refreshed_at INTEGER, filter_key TEXT)")
            db.execSQL("CREATE TABLE stages(id INTEGER PRIMARY KEY AUTOINCREMENT, feed TEXT NOT NULL REFERENCES feeds(id), generation INTEGER NOT NULL, UNIQUE(feed,generation))")
            db.execSQL("CREATE TABLE channels(stage INTEGER NOT NULL REFERENCES stages(id) ON DELETE CASCADE, external_id TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(stage,external_id))")
            db.execSQL("CREATE TABLE channel_names(stage INTEGER NOT NULL REFERENCES stages(id) ON DELETE CASCADE, external_id TEXT NOT NULL, name TEXT NOT NULL, PRIMARY KEY(stage,name,external_id))")
            db.execSQL("CREATE TABLE programmes(id INTEGER PRIMARY KEY, stage INTEGER NOT NULL REFERENCES stages(id) ON DELETE CASCADE, external_id TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER, precise INTEGER NOT NULL, payload TEXT NOT NULL, search_title TEXT, sport INTEGER, UNIQUE(stage,external_id,start))")
            db.execSQL("CREATE INDEX guide_sport ON programmes(stage,start,stop) WHERE sport=1")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            check(oldVersion in 1..5 && newVersion == 6) { "Missing guide database migration" }
            for (table in listOf("programmes", "channel_names", "channels", "stages")) db.execSQL("DROP TABLE IF EXISTS $table")
            db.execSQL("ALTER TABLE feeds RENAME TO legacy_feeds")
            createSchema(db)
            db.execSQL("INSERT INTO feeds(id,profile,label,endpoint,version,requested) SELECT id,profile,label,endpoint,version,requested FROM legacy_feeds")
            db.execSQL("DROP TABLE legacy_feeds")
        }
        override fun onOpen(db: SQLiteDatabase) {
            if (db.isReadOnly) return
            try {
                if (db.rawQuery("PRAGMA auto_vacuum", null).use { it.moveToFirst() && it.getInt(0) != 2 }) {
                    db.execSQL("PRAGMA auto_vacuum=INCREMENTAL")
                    db.execSQL("VACUUM")
                }
            } catch (error: Exception) { IptvLog.failure("guide vacuum", error) }
        }
    }
    private companion object {
        const val BATCH_ROWS = 5_000
        const val BATCH_CHARACTERS = 2_000_000L
        const val PURGE_ROWS = 5_000
        const val VACUUM_PAGES = 2_048
        const val MAX_VACUUM_STEPS = 256
        const val JOURNAL_LIMIT_BYTES = 8L * 1024 * 1024
        const val REJECTED_LOOKBACK_MILLIS = 86_400_000L
        fun aad(ref: IptvGuideRef, field: String) = "iptv.guide.v1:${ref.profileId}:${ref.feedId}:$field"
        fun JSONObject.optional(key: String): String? = if (isNull(key)) null else getString(key)
        fun count(db: SQLiteDatabase, sql: String, args: Array<String>): Long = db.rawQuery(sql, args).use { check(it.moveToFirst()); it.getLong(0) }
        fun validate(label: String, endpoint: String) {
            require(label.isNotBlank() && label.length <= 240 && endpoint.length <= 16_384)
            if (XtreamGuideReference.sourceId(endpoint) != null) return
            val uri = try { URI(endpoint) } catch (_: Exception) { throw IllegalArgumentException("Invalid guide endpoint") }
            require(((uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) ||
                (uri.scheme == "content" && !uri.rawAuthority.isNullOrBlank()) ||
                (uri.scheme == "file" && uri.rawAuthority == null && uri.rawQuery == null && uri.path?.startsWith("/") == true)) &&
                uri.rawUserInfo == null && uri.rawFragment == null) { "Invalid guide endpoint" }
        }
    }
}
