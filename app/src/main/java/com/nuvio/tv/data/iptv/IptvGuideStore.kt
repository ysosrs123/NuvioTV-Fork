package com.nuvio.tv.data.iptv

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.nuvio.tv.core.iptv.GuideChannel
import com.nuvio.tv.core.iptv.GuideProgramme
import com.nuvio.tv.core.iptv.GuideParseLimits
import com.nuvio.tv.core.iptv.GuideProgrammeCap
import com.nuvio.tv.core.iptv.GuideStorageCaps
import com.nuvio.tv.core.iptv.GUIDE_AIRING_LOOKBACK_MILLIS
import com.nuvio.tv.core.iptv.GuideFeedIndex
import com.nuvio.tv.core.iptv.GuideKey
import com.nuvio.tv.core.iptv.GuideNameIndex
import com.nuvio.tv.core.iptv.guideAiringAt
import com.nuvio.tv.core.iptv.guideChannelNameKeys
import com.nuvio.tv.core.iptv.guideMatchName
import com.nuvio.tv.core.iptv.guideSearchQuery
import com.nuvio.tv.core.iptv.guideSearchTitle
import com.nuvio.tv.core.iptv.RefreshDecision
import com.nuvio.tv.core.iptv.SPORTS_OPEN_ENDED_MILLIS
import com.nuvio.tv.core.iptv.SportsGuide
import com.nuvio.tv.core.iptv.XtreamGuideReference
import com.nuvio.tv.core.iptv.guideQuarantineAccepted
import com.nuvio.tv.core.iptv.mergeGuideChannel
import com.nuvio.tv.core.iptv.parseGuideInput
import java.io.Closeable
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

class IptvGuideStore(
    context: Context, databaseName: String = "iptv-guide.db",
    private val secrets: IptvSecretBox = EnvelopeIptvSecretBox(AndroidIptvSecretBox()),
    private val maxDatabaseBytes: Long = 256L * 1024 * 1024,
    private val releaseDocument: (String) -> Unit = { uri ->
        context.applicationContext.contentResolver.releasePersistableUriPermission(android.net.Uri.parse(uri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    },
    private val now: () -> Long = System::currentTimeMillis,
) : Closeable {
    init { require(maxDatabaseBytes >= 64 * 1024) }
    private val helper = Database(context.applicationContext, databaseName)

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
    }.let(::release)

    fun endpoint(ref: IptvGuideRef): String = transaction { db -> feed(db, ref); endpoint(db, ref) }
    fun feed(ref: IptvGuideRef): IptvGuideFeed = transaction { db -> feed(db, ref) }

    fun removeProfile(profileId: Int) = transaction { db ->
        require(profileId >= 0)
        val args = arrayOf(profileId.toString())
        val removed = documents(db, profileId)
        db.delete("stages", "feed IN (SELECT id FROM feeds WHERE profile=?)", args)
        db.delete("feeds", "profile=?", args)
        removed - documents(db)
    }.let(::release)

    fun clearAllProfiles() = transaction { db ->
        val removed = documents(db)
        db.delete("stages", null, null); db.delete("feeds", null, null)
        removed
    }.let(::release)

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

    fun feeds(profileId: Int, offset: Int = 0, limit: Int = 100): List<IptvGuideFeed> = transaction { db ->
        require(profileId >= 0 && offset >= 0 && limit in 1..200)
        db.rawQuery("SELECT id,label,version,requested,active_generation,refreshed_at FROM feeds WHERE profile=? ORDER BY label COLLATE NOCASE,id LIMIT ? OFFSET ?",
            arrayOf(profileId.toString(), limit.toString(), offset.toString())).use { c -> buildList {
                while (c.moveToNext()) add(IptvGuideFeed(IptvGuideRef(profileId, c.getString(0)), c.getString(1), c.getLong(2), c.getLong(3),
                    if (c.isNull(4)) null else c.getLong(4), if (c.isNull(5)) null else c.getLong(5)))
            } }
    }

    fun matchingIndexes(profileId: Int, feedIds: List<String>, externalIds: Set<String>): List<GuideFeedIndex> = transaction { db ->
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

    fun nameIndexes(profileId: Int, feedIds: List<String>, names: Set<String>): List<GuideNameIndex> = transaction { db ->
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

    fun searchChannels(ref: IptvGuideRef, query: String, limit: Int = 60): List<GuideChannel> = transaction { db ->
        require(limit in 1..200 && query.length <= 256)
        feed(db, ref)
        val name = guideMatchName(query)
        val sql = if (name.isEmpty()) "SELECT c.payload FROM channels c JOIN feeds f ON c.stage=f.active_stage WHERE f.id=? AND f.profile=? AND f.version=f.active_version ORDER BY c.external_id LIMIT ?"
            else "SELECT c.payload FROM channels c JOIN feeds f ON c.stage=f.active_stage WHERE f.id=? AND f.profile=? AND f.version=f.active_version AND c.external_id IN (SELECT n.external_id FROM channel_names n WHERE n.stage=f.active_stage AND instr(n.name,?)>0) ORDER BY c.external_id LIMIT ?"
        val args = if (name.isEmpty()) arrayOf(ref.feedId, ref.profileId.toString(), limit.toString()) else arrayOf(ref.feedId, ref.profileId.toString(), name, limit.toString())
        db.rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) add(IptvGuideJson.channel(c.getString(0))) } }
    }

    fun airingMatches(profileId: Int, feedIds: List<String>, query: String, nowMillis: Long, limit: Int = 200): List<IptvAiringMatch> = transaction { db ->
        require(profileId >= 0 && feedIds.size <= 16 && feedIds.distinct().size == feedIds.size && limit in 1..500)
        feedIds.forEach { IptvGuideRef(profileId, it) }
        val text = guideSearchQuery(query) ?: return@transaction emptyList()
        val now = nowMillis.toString(); val since = (nowMillis - GUIDE_AIRING_LOOKBACK_MILLIS).toString()
        val found = mutableListOf<IptvAiringMatch>()
        for (feed in feedIds) {
            if (found.size >= limit) break
            db.rawQuery("SELECT c.payload,p.payload FROM feeds f CROSS JOIN channels c CROSS JOIN programmes p WHERE f.id=? AND f.profile=? AND f.version=f.active_version AND c.stage=f.active_stage AND p.stage=c.stage AND p.external_id=c.external_id AND p.start<=? AND p.start>? AND (p.stop>? OR (p.stop IS NULL AND NOT EXISTS (SELECT 1 FROM programmes q WHERE q.stage=p.stage AND q.external_id=p.external_id AND q.start>p.start AND q.start<=?))) AND instr(p.search_title,?)>0 ORDER BY c.external_id,p.start,p.id LIMIT ?",
                arrayOf(feed, profileId.toString(), now, since, now, now, text, (limit - found.size).toString())).use { c ->
                while (c.moveToNext()) {
                    val channel = IptvGuideJson.channel(c.getString(0)); val programme = IptvGuideJson.programme(c.getString(1))
                    if (guideAiringAt(programme, nowMillis)) found += IptvAiringMatch(GuideKey(feed, channel.externalId), channel, programme)
                }
            }
        }
        found
    }

    fun sportsMatches(profileId: Int, feedIds: List<String>, nowMillis: Long, untilMillis: Long, limit: Int = 400): List<IptvAiringMatch> = transaction { db ->
        require(profileId >= 0 && feedIds.size <= 16 && feedIds.distinct().size == feedIds.size && limit in 1..1000 && untilMillis >= nowMillis)
        feedIds.forEach { IptvGuideRef(profileId, it) }
        val found = mutableListOf<IptvAiringMatch>()
        for (feed in feedIds) {
            if (found.size >= limit) break
            db.rawQuery("SELECT c.payload,p.payload FROM feeds f CROSS JOIN programmes p CROSS JOIN channels c WHERE f.id=? AND f.profile=? AND f.version=f.active_version AND p.stage=f.active_stage AND p.sport=1 AND p.start<=? AND p.start>? AND (p.stop>? OR (p.stop IS NULL AND p.start>?)) AND c.stage=p.stage AND c.external_id=p.external_id ORDER BY p.start,p.id LIMIT ?",
                arrayOf(feed, profileId.toString(), untilMillis.toString(), (nowMillis - GUIDE_AIRING_LOOKBACK_MILLIS).toString(), nowMillis.toString(),
                    (nowMillis - SPORTS_OPEN_ENDED_MILLIS).toString(), (limit - found.size).toString())).use { c ->
                while (c.moveToNext()) {
                    val channel = IptvGuideJson.channel(c.getString(0)); val programme = IptvGuideJson.programme(c.getString(1))
                    if (programme.start.precise) found += IptvAiringMatch(GuideKey(feed, channel.externalId), channel, programme)
                }
            }
        }
        found
    }

    fun beginRefresh(ref: IptvGuideRef): IptvGuideTicket = transaction { db -> beginRefresh(db, ref) }

    fun prepareRefresh(ref: IptvGuideRef, window: IptvGuideWindow): IptvGuideRefreshRequest = transaction { db ->
        val ticket = beginRefresh(db, ref)
        IptvGuideRefreshRequest(ticket, endpoint(db, ref), if (covers(db, ref, window)) validators(db, ref) else null)
    }

    fun acceptNotModified(ticket: IptvGuideTicket, window: IptvGuideWindow): Boolean = transaction { db ->
        if (!current(db, ticket)) return@transaction false
        val cache = validators(db, ticket.ref)
        (current(db, ticket) && covers(db, ticket.ref, window) && cache != null && (cache.etag != null || cache.lastModified != null)).also { accepted ->
            if (accepted) db.update("feeds", ContentValues().apply { put("refreshed_at", now()) }, "id=?", arrayOf(ticket.ref.feedId))
        }
    }

    private fun covers(db: SQLiteDatabase, ref: IptvGuideRef, window: IptvGuideWindow): Boolean =
        db.rawQuery("SELECT 1 FROM feeds WHERE id=? AND profile=? AND version=active_version AND window_from<=? AND window_until>=?",
            arrayOf(ref.feedId, ref.profileId.toString(), window.fromMillis.toString(), window.untilMillis.toString())).use { it.moveToFirst() }

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
        channelFilter: Set<String>? = null,
        checkCancellation: () -> Unit = {},
        caps: GuideStorageCaps = GuideStorageCaps(),
    ): RefreshDecision {
        require(listOf(validators.etag, validators.lastModified).all { it == null || (it.length <= 4096 && '\r' !in it && '\n' !in it) })
        val stage = UUID.randomUUID().toString()
        val claimed = transaction { db ->
            if (!current(db, ticket)) return@transaction false

            db.insertWithOnConflict("stages", null, ContentValues().apply { put("id", stage); put("feed", ticket.ref.feedId); put("generation", ticket.generation) }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        }
        if (!claimed) return RefreshDecision.STALE
        val channels = mutableListOf<GuideChannel>()
        val programmes = mutableListOf<GuideProgramme>()
        val cap = GuideProgrammeCap(caps)
        val sportsChannels = HashSet<String>()
        var bufferedCharacters = 0
        fun flush() {
            if (channels.isEmpty() && programmes.isEmpty()) return
            checkCancellation()
            transaction { db ->
                if (!current(db, ticket)) throw StaleImport()
                for (channel in channels) {
                    checkCancellation()
                    guideChannelNameKeys(channel).forEach { name ->
                        db.insertWithOnConflict("channel_names", null, ContentValues().apply { put("stage", stage); put("external_id", channel.externalId); put("name", name) }, SQLiteDatabase.CONFLICT_IGNORE)
                    }
                    val payload = IptvGuideJson.channel(channel)
                    val existing = db.rawQuery("SELECT payload FROM channels WHERE stage=? AND external_id=?", arrayOf(stage, channel.externalId)).use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }
                    if (existing == null) db.insertOrThrow("channels", null, ContentValues().apply { put("stage", stage); put("external_id", channel.externalId); put("payload", payload) })
                    else if (existing != payload) {
                        val merged = IptvGuideJson.channel(mergeGuideChannel(IptvGuideJson.channel(existing), channel))
                        if (merged != existing) db.update("channels", ContentValues().apply { put("payload", merged) },
                            "stage=? AND external_id=?", arrayOf(stage, channel.externalId)).also { check(it == 1) }
                    }
                }
                for (programme in programmes) {
                    checkCancellation()
                    val payload = IptvGuideJson.programme(programme)
                    db.insertWithOnConflict("programmes", null, ContentValues().apply {
                        put("stage", stage); put("id", digest(payload)); put("external_id", programme.channelExternalId)
                        put("start", programme.start.epochMillis); put("stop", programme.stop?.epochMillis)
                        put("precise", if (programme.canSchedulePrecisely) 1 else 0); put("payload", payload)
                        put("search_title", guideSearchTitle(programme.titles))
                        if (SportsGuide.isSportsProgramme(programme.titles, programme.categories, programme.channelExternalId in sportsChannels)) put("sport", 1)
                    }, SQLiteDatabase.CONFLICT_IGNORE).also { check(it != -1L || db.rawQuery("SELECT 1 FROM programmes WHERE stage=? AND id=?", arrayOf(stage, digest(payload))).use { c -> c.moveToFirst() }) }
                }
            }
            channels.clear(); programmes.clear(); bufferedCharacters = 0
        }
        var importFailure: Throwable? = null
        try {
            checkCancellation()
            val summary = parseGuideInput(input, channel = channel@{ channel ->
                if (channelFilter != null && channel.externalId !in channelFilter) return@channel
                channels += channel
                if (SportsGuide.isSportsChannel(channel.names)) sportsChannels += channel.externalId
                bufferedCharacters += channel.names.sumOf { it.text.length } + channel.externalId.length
                if (channels.size + programmes.size >= 100 || bufferedCharacters >= 256 * 1024) flush()
            }, programme = programme@{ parsed ->
                if ((channelFilter == null || parsed.channelExternalId in channelFilter) && parsed.start.epochMillis < window.untilMillis && (parsed.stop?.epochMillis?.let { it > window.fromMillis } ?: (parsed.start.epochMillis >= window.fromMillis))) {
                    val programme = cap.admit(parsed) ?: return@programme
                    programmes += programme
                    bufferedCharacters += programme.titles.sumOf { it.text.length } + programme.descriptions.sumOf { it.text.length }
                    if (channels.size + programmes.size >= 100 || bufferedCharacters >= 256 * 1024) flush()
                }
            }, limits = limits, checkCancellation = checkCancellation)
            flush()
            checkCancellation()
            return transaction { db ->
                if (!current(db, ticket)) return@transaction RefreshDecision.STALE
                val orphans = db.delete("programmes", "stage=? AND NOT EXISTS (SELECT 1 FROM channels c WHERE c.stage=programmes.stage AND c.external_id=programmes.external_id)", arrayOf(stage)).toLong()
                if (!guideQuarantineAccepted(summary.programmes.toLong(), summary.rejectedProgrammes + orphans)) return@transaction RefreshDecision.INVALID
                val candidateCount = count(db, "SELECT COUNT(*) FROM programmes WHERE stage=?", arrayOf(stage))
                if (candidateCount == 0L || summary.channels == 0) return@transaction RefreshDecision.EMPTY_REQUIRES_REVIEW
                val previous = count(db, "SELECT COUNT(*) FROM programmes p JOIN feeds f ON f.active_stage=p.stage WHERE f.id=? AND f.version=f.active_version AND p.start<? AND (p.stop>? OR (p.stop IS NULL AND p.start>=?))", arrayOf(ticket.ref.feedId, window.untilMillis.toString(), window.fromMillis.toString(), window.fromMillis.toString()))
                IptvLog.info("guide import channels=${summary.channels} programmes=$candidateCount capped=${cap.dropped} previous=$previous")
                if (previous > 0 && candidateCount * 2 < previous) return@transaction RefreshDecision.SHRINK_REQUIRES_REVIEW
                checkCancellation()
                db.update("feeds", ContentValues().apply {
                    put("active_stage", stage); put("active_generation", ticket.generation); put("active_version", ticket.configurationVersion); put("refreshed_at", now())
                    put("window_from", window.fromMillis); put("window_until", window.untilMillis)
                    put("validators", secrets.seal(aad(ticket.ref, "validators"), JSONObject().put("etag", validators.etag).put("lastModified", validators.lastModified).toString()))
                }, "id=? AND version=? AND requested=?", arrayOf(ticket.ref.feedId, ticket.configurationVersion.toString(), ticket.generation.toString())).also { check(it == 1) }
                db.delete("stages", "feed=? AND id<>?", arrayOf(ticket.ref.feedId, stage))
                RefreshDecision.PUBLISH
            }
        } catch (_: StaleImport) {
            return RefreshDecision.STALE
        } catch (error: Throwable) {
            importFailure = error
            throw error
        } finally {
            try { transaction { db -> db.delete("stages", "id=? AND id NOT IN (SELECT active_stage FROM feeds WHERE active_stage IS NOT NULL)", arrayOf(stage)) } }
            catch (cleanup: Throwable) { if (importFailure == null) throw cleanup else importFailure.addSuppressed(cleanup) }
        }
    }

    fun programmes(ref: IptvGuideRef, externalId: String, window: IptvGuideWindow, offset: Int = 0, limit: Int = 100): IptvProgrammePage = transaction { db ->
        require(offset >= 0 && limit in 1..200 && externalId.isNotBlank())
        feed(db, ref)
        val items = db.rawQuery("SELECT p.payload FROM programmes p JOIN feeds f ON p.stage=f.active_stage WHERE f.id=? AND f.profile=? AND f.version=f.active_version AND p.external_id=? AND p.start<? AND (p.stop>? OR (p.stop IS NULL AND p.start>=?)) ORDER BY p.start,p.id LIMIT ? OFFSET ?",
            arrayOf(ref.feedId, ref.profileId.toString(), externalId, window.untilMillis.toString(), window.fromMillis.toString(), window.fromMillis.toString(), (limit + 1).toString(), offset.toString())).use { c ->
            buildList { while (c.moveToNext()) add(IptvGuideJson.programme(c.getString(0))) }
        }
        IptvProgrammePage(items.take(limit), items.size > limit)
    }

    fun channelPage(ref: IptvGuideRef, offset: Int = 0, limit: Int = 200): List<GuideChannel> = transaction { db ->
        require(offset >= 0 && limit in 1..500)
        feed(db, ref)
        db.rawQuery("SELECT c.payload FROM channels c JOIN feeds f ON c.stage=f.active_stage WHERE f.id=? AND f.profile=? AND f.version=f.active_version ORDER BY c.external_id LIMIT ? OFFSET ?",
            arrayOf(ref.feedId, ref.profileId.toString(), limit.toString(), offset.toString())).use { c -> buildList { while (c.moveToNext()) add(IptvGuideJson.channel(c.getString(0))) } }
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
    private fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = helper.writableDatabase
        db.beginTransactionNonExclusive()
        var failure: Throwable? = null
        return try {
            db.setMaximumSize(maxDatabaseBytes)
            block(db).also { db.setTransactionSuccessful() }
        } catch (error: Throwable) { failure = error; throw error } finally {
            try { db.endTransaction() } catch (cleanup: Throwable) { if (failure == null) throw cleanup else failure.addSuppressed(cleanup) }
        }
    }
    override fun close() = helper.close()
    private class StaleImport : RuntimeException()
    private class Database(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 5) {
        init { require(name.matches(Regex("[A-Za-z0-9_.-]+"))); setWriteAheadLoggingEnabled(true) }
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE feeds(id TEXT PRIMARY KEY, profile INTEGER NOT NULL, label TEXT NOT NULL, endpoint BLOB NOT NULL, version INTEGER NOT NULL, requested INTEGER NOT NULL, active_stage TEXT, active_generation INTEGER, active_version INTEGER, validators BLOB, window_from INTEGER, window_until INTEGER, refreshed_at INTEGER)")
            db.execSQL("CREATE TABLE stages(id TEXT PRIMARY KEY, feed TEXT NOT NULL REFERENCES feeds(id), generation INTEGER NOT NULL, UNIQUE(feed,generation))")
            db.execSQL("CREATE TABLE channels(stage TEXT NOT NULL REFERENCES stages(id) ON DELETE CASCADE, external_id TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(stage,external_id))")
            db.execSQL("CREATE TABLE programmes(stage TEXT NOT NULL REFERENCES stages(id) ON DELETE CASCADE, id TEXT NOT NULL, external_id TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER, precise INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(stage,id))")
            db.execSQL("CREATE INDEX guide_window ON programmes(stage,external_id,start,stop)")
            addNameSchema(db)
            addSearchSchema(db)
            addSportsSchema(db)
        }
        private fun addNameSchema(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE channel_names(stage TEXT NOT NULL REFERENCES stages(id) ON DELETE CASCADE, external_id TEXT NOT NULL, name TEXT NOT NULL, PRIMARY KEY(stage,name,external_id))")
        }
        private fun addSearchSchema(db: SQLiteDatabase) {
            db.execSQL("ALTER TABLE programmes ADD COLUMN search_title TEXT")
        }
        private fun addSportsSchema(db: SQLiteDatabase) {
            db.execSQL("ALTER TABLE programmes ADD COLUMN sport INTEGER")
            db.execSQL("CREATE INDEX guide_sport ON programmes(stage,start) WHERE sport=1")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            check(oldVersion in 1..4 && newVersion == 5) { "Missing guide database migration" }
            if (oldVersion == 1) {
                db.execSQL("ALTER TABLE feeds ADD COLUMN window_from INTEGER")
                db.execSQL("ALTER TABLE feeds ADD COLUMN window_until INTEGER")
            }
            if (oldVersion <= 2) {
                db.execSQL("ALTER TABLE feeds ADD COLUMN refreshed_at INTEGER")
                addNameSchema(db)
            }
            if (oldVersion <= 3) addSearchSchema(db)
            addSportsSchema(db)
        }
    }
    private companion object {
        fun aad(ref: IptvGuideRef, field: String) = "iptv.guide.v1:${ref.profileId}:${ref.feedId}:$field"
        fun JSONObject.optional(key: String): String? = if (isNull(key)) null else getString(key)
        fun count(db: SQLiteDatabase, sql: String, args: Array<String>): Long = db.rawQuery(sql, args).use { check(it.moveToFirst()); it.getLong(0) }
        fun digest(value: String): String = buildString(64) {
            for (byte in MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))) {
                val number = byte.toInt() and 255
                append("0123456789abcdef"[number ushr 4]); append("0123456789abcdef"[number and 15])
            }
        }
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
