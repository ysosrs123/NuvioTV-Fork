package com.nuvio.tv.data.iptv

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.nuvio.tv.core.iptv.ChannelCandidate
import com.nuvio.tv.core.iptv.ChannelIdentityReconciler
import com.nuvio.tv.core.iptv.GuideKey
import com.nuvio.tv.core.iptv.RefreshDecision
import com.nuvio.tv.core.iptv.RefreshTicket
import com.nuvio.tv.core.iptv.StoredChannel
import com.nuvio.tv.core.iptv.decideCatalogueRefresh
import com.nuvio.tv.core.iptv.foldSearchText
import com.nuvio.tv.core.iptv.moveItem
import com.nuvio.tv.core.iptv.retainTombstones
import java.io.Closeable
import java.io.InterruptedIOException
import java.net.URI
import java.util.UUID
import org.json.JSONObject

class IptvCatalogueStore(
    context: Context,
    databaseName: String = "iptv-catalogue.db",
    private val secrets: IptvSecretBox = AndroidIptvSecretBox(),
    private val maxDatabaseBytes: Long = 256L * 1024 * 1024,
) : Closeable {
    init { require(maxDatabaseBytes >= 64 * 1024) }
    private val helper = Database(context.applicationContext, databaseName)

    fun createSource(profileId: Int, label: String, kind: IptvSourceKind, accountId: String, connection: IptvSourceConnection): IptvSource = transaction { db ->
        validateConfiguration(label, accountId, connection)
        validateKind(kind, connection)
        val ref = IptvSourceRef(profileId, UUID.randomUUID().toString())
        db.insertOrThrow("sources", null, ContentValues().apply {
            put("id", ref.sourceId); put("profile", profileId); put("label", label); put("kind", kind.name); put("account_id", accountId)
            put("config_version", 1L); put("requested", 0L)
            put("position", db.rawQuery("SELECT COALESCE(MAX(position)+1,0) FROM sources WHERE profile=?", arrayOf(profileId.toString())).use { it.moveToFirst(); it.getLong(0) })
            put("connection", secrets.seal(aad(ref, "connection"), encodeConnection(connection)))
        })
        source(db, ref)
    }

    fun editSource(ref: IptvSourceRef, label: String, kind: IptvSourceKind, accountId: String, connection: IptvSourceConnection): IptvSource = transaction { db ->
        validateConfiguration(label, accountId, connection)
        validateKind(kind, connection)
        val old = source(db, ref)
        val changedConnection = old.kind != kind || old.accountId != accountId || readConnection(db, ref) != connection
        db.update("sources", ContentValues().apply {
            put("label", label); put("kind", kind.name); put("account_id", accountId)
            if (changedConnection) {
                put("config_version", Math.addExact(old.configurationVersion, 1)); put("requested", Math.addExact(old.requestedGeneration, 1))
                put("connection", secrets.seal(aad(ref, "connection"), encodeConnection(connection)))
                putNull("validators")
            }
        }, "id=?", arrayOf(ref.sourceId))
        source(db, ref)
    }

    fun sources(profileId: Int): List<IptvSource> = transaction { db ->
        require(profileId >= 0)
        db.query("sources", null, "profile=?", arrayOf(profileId.toString()), null, null, "position, label COLLATE NOCASE, id").use { cursor ->
            buildList { while (cursor.moveToNext()) add(readSource(cursor)) }
        }
    }

    fun removeProfile(profileId: Int) = transaction { db ->
        require(profileId >= 0)
        val args = arrayOf(profileId.toString())
        db.delete("source_guides", "source IN (SELECT id FROM sources WHERE profile=?)", args)
        db.delete("overlays", "profile=?", args)
        db.delete("catalogue", "source IN (SELECT id FROM sources WHERE profile=?)", args)
        db.delete("identities", "source IN (SELECT id FROM sources WHERE profile=?)", args)
        db.delete("sources", "profile=?", args)
        db.delete("accounts", "profile=?", args)
        Unit
    }

    fun clearAllProfiles() = transaction { db ->
        listOf("source_guides", "overlays", "catalogue", "identities", "sources", "accounts").forEach { db.delete(it, null, null) }
    }

    fun moveSource(ref: IptvSourceRef, toIndex: Int) = transaction { db ->
        source(db, ref)
        val ids = db.query("sources", arrayOf("id"), "profile=?", arrayOf(ref.profileId.toString()), null, null, "position, label COLLATE NOCASE, id").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        val ordered = moveItem(ids, ids.indexOf(ref.sourceId), toIndex)
        for ((position, id) in ordered.withIndex()) db.update("sources", ContentValues().apply { put("position", position) }, "id=?", arrayOf(id))
        Unit
    }

    fun accounts(profileId: Int): List<IptvAccountGroup> = transaction { db ->
        require(profileId >= 0)
        val sources = db.query("sources", arrayOf("id", "account_id"), "profile=?", arrayOf(profileId.toString()), null, null, "position, label COLLATE NOCASE, id").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(1) to IptvSourceRef(profileId, c.getString(0))) }
        }
        val saved = db.query("accounts", arrayOf("id", "label", "max_streams"), "profile=?", arrayOf(profileId.toString()), null, null, "label COLLATE NOCASE, id").use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1) to c.getInt(2)) }
        }
        (saved.keys + sources.map { it.first }).distinct().map { id ->
            val (label, maxStreams) = saved[id] ?: (id to 1)
            IptvAccountGroup(id, label, maxStreams, sources.filter { it.first == id }.map { it.second })
        }
    }

    fun saveAccount(profileId: Int, id: String, label: String, maxStreams: Int) = transaction { db ->
        require(profileId >= 0 && id.matches(Regex("[A-Za-z0-9_-]{1,80}")) && label.isNotBlank() && label.length <= 240 && maxStreams in 1..16)
        db.insertWithOnConflict("accounts", null, ContentValues().apply {
            put("profile", profileId); put("id", id); put("label", label); put("max_streams", maxStreams)
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
        Unit
    }

    fun assignAccount(ref: IptvSourceRef, accountId: String) = transaction { db ->
        require(accountId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        source(db, ref)
        db.update("sources", ContentValues().apply { put("account_id", accountId) }, "id=?", arrayOf(ref.sourceId)).also { check(it == 1) }
        Unit
    }

    fun connection(ref: IptvSourceRef): IptvSourceConnection = transaction { db ->
        source(db, ref)
        readConnection(db, ref)
    }

    fun beginRefresh(ref: IptvSourceRef): RefreshTicket = transaction { db -> beginRefresh(db, ref) }

    fun prepareRefresh(ref: IptvSourceRef): IptvRefreshRequest = transaction { db ->
        val ticket = beginRefresh(db, ref)
        val current = source(db, ref)
        IptvRefreshRequest(ticket, current.kind, readConnection(db, ref), cacheValidators(db, current))
    }

    fun acceptNotModified(ref: IptvSourceRef, ticket: RefreshTicket): Boolean = transaction { db ->
        val current = source(db, ref)
        val validators = cacheValidators(db, current)
        current.playbackEligible && ticket == RefreshTicket(ref.sourceId, current.configurationVersion, current.requestedGeneration) &&
            ticket.requestGeneration > 0 && validators != null && (validators.etag != null || validators.lastModified != null)
    }

    private fun beginRefresh(db: SQLiteDatabase, ref: IptvSourceRef): RefreshTicket {
        val old = source(db, ref)
        val generation = Math.addExact(old.requestedGeneration, 1)
        db.update("sources", ContentValues().apply { put("requested", generation) }, "id=?", arrayOf(ref.sourceId))
        return RefreshTicket(ref.sourceId, old.configurationVersion, generation)
    }

    fun snapshot(ref: IptvSourceRef): IptvCatalogueSnapshot = transaction { db -> snapshot(db, ref) }

    fun page(ref: IptvSourceRef, query: IptvBrowseQuery = IptvBrowseQuery(), cursor: IptvBrowseCursor? = null,
        limit: Int = 100): IptvCataloguePage = transaction { db ->
        require(limit in 1..200)
        val source = source(db, ref)
        val revision = db.rawQuery("SELECT browse_revision FROM sources WHERE id=?", arrayOf(ref.sourceId)).use {
            check(it.moveToFirst()); IptvBrowseRevision(ref, source.activeGeneration, source.configurationVersion, it.getLong(0))
        }
        if (cursor != null && (cursor.revision != revision || cursor.query != query)) throw IptvCatalogueChangedException()
        val offset = cursor?.offset ?: 0
        require(offset in 0..60_000)
        val args = mutableListOf(ref.profileId.toString(), ref.sourceId, source.activeGeneration?.toString() ?: "-1")
        val filters = buildString {
            if (!query.includeUnavailable) append(" AND c.available=1")
            if (!query.includeHidden) append(" AND COALESCE(o.hidden,0)=0")
            if (query.favouritesOnly) append(" AND o.favourite_rank IS NOT NULL")
            if (query.search.isNotBlank()) { append(" AND instr(COALESCE(o.search_name,c.search_name),?)>0"); args += searchName(query.search.trim()) }
        }
        val order = if (query.favouritesOnly) "o.favourite_rank," else ""
        args += (limit + 1).toString(); args += offset.toString()
        val selected = db.rawQuery("SELECT c.id FROM catalogue c LEFT JOIN overlays o ON c.id=o.id AND o.profile=? WHERE c.source=? AND c.generation=?$filters ORDER BY ${order}COALESCE(o.search_name,c.search_name),c.id LIMIT ? OFFSET ?", args.toTypedArray()).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

        val items = if (selected.isEmpty()) emptyList() else db.rawQuery("SELECT c.*,o.custom_name,o.favourite_rank,o.hidden,o.guide_feed,o.guide_id,o.stream_format FROM catalogue c LEFT JOIN overlays o ON c.id=o.id AND o.profile=? WHERE c.source=? AND c.generation=? AND c.id IN (${selected.joinToString(",") { "?" }}) ORDER BY ${order}COALESCE(o.search_name,c.search_name),c.id",
            arrayOf(ref.profileId.toString(), ref.sourceId, source.activeGeneration.toString(), *selected.toTypedArray())).use { c ->
            buildList { while (c.moveToNext()) add(readItem(c, ref)) }
        }
        IptvCataloguePage(source, revision, items.take(limit), guideAssociations(db, ref),
            if (items.size > limit) IptvBrowseCursor(revision, query, offset + limit) else null)
    }

    fun playbackItem(ref: IptvSourceRef, channelId: String): IptvCatalogueItem? = transaction { db ->
        val source = source(db, ref)
        if (!source.playbackEligible) return@transaction null
        db.rawQuery("SELECT c.*,o.custom_name,o.favourite_rank,o.hidden,o.guide_feed,o.guide_id,o.stream_format FROM catalogue c LEFT JOIN overlays o ON c.id=o.id AND o.profile=? WHERE c.source=? AND c.generation=? AND c.id=? AND c.available=1 AND COALESCE(o.hidden,0)=0",
            arrayOf(ref.profileId.toString(), ref.sourceId, source.activeGeneration.toString(), channelId)).use { if (it.moveToFirst()) readItem(it, ref) else null }
    }

    fun setGuideFeeds(ref: IptvSourceRef, feeds: List<IptvGuideRef>, priority: List<IptvGuideRef> = emptyList()) = transaction { db ->
        source(db, ref)
        require(feeds.size <= 16 && feeds.distinct().size == feeds.size && feeds.all { it.profileId == ref.profileId })
        require(priority.distinct().size == priority.size && priority.all { it in feeds })
        db.delete("source_guides", "source=?", arrayOf(ref.sourceId))
        for ((position, feed) in feeds.withIndex()) db.insertOrThrow("source_guides", null, ContentValues().apply {
            put("source", ref.sourceId); put("feed", feed.feedId); put("position", position)
            put("priority", priority.indexOf(feed).takeIf { it >= 0 })
        })
        bumpBrowseRevision(db, ref)
    }

    fun guideAssociations(ref: IptvSourceRef): IptvGuideAssociations = transaction { db -> source(db, ref); guideAssociations(db, ref) }

    private fun guideAssociations(db: SQLiteDatabase, ref: IptvSourceRef): IptvGuideAssociations =
        db.rawQuery("SELECT feed,priority FROM source_guides WHERE source=? ORDER BY position", arrayOf(ref.sourceId)).use { c ->
            val feeds = mutableListOf<String>(); val priority = mutableListOf<Pair<Int, String>>()
            while (c.moveToNext()) { feeds += c.getString(0); if (!c.isNull(1)) priority += c.getInt(1) to c.getString(0) }
            IptvGuideAssociations(feeds, priority.sortedBy { it.first }.map { it.second })
        }

    private fun bumpBrowseRevision(db: SQLiteDatabase, ref: IptvSourceRef) {
        db.execSQL("UPDATE sources SET browse_revision=browse_revision+1 WHERE id=?", arrayOf(ref.sourceId))
    }

    fun commitCatalogue(
        ref: IptvSourceRef, ticket: RefreshTicket, records: List<IptvCatalogueRecord>, complete: Boolean,
        validators: IptvCacheValidators = IptvCacheValidators(), acceptedLargeChange: Boolean = false,
        checkCancellation: () -> Unit = { if (Thread.currentThread().isInterrupted) throw InterruptedIOException("IPTV import cancelled") },
    ): RefreshDecision = transaction { db ->
        checkCancellation()
        val old = snapshot(db, ref, checkCancellation)
        val latest = RefreshTicket(ref.sourceId, old.source.configurationVersion, old.source.requestedGeneration)
        if (records.size > 20_000 || ticket.requestGeneration <= 0) return@transaction RefreshDecision.INVALID
        val incoming = records.distinct()
        val valid = complete && incoming.size <= 20_000 && incoming.all(::validRecord) &&
            incoming.map { it.data }.distinct().size == incoming.size && validValidators(validators)
        val previousCount = if (old.source.activeConfigurationVersion == old.source.configurationVersion) old.channels.count { it.channel.available } else 0
        val decision = decideCatalogueRefresh(ticket, latest, valid, previousCount, incoming.size, acceptedLargeChange)
        if (decision != RefreshDecision.PUBLISH) return@transaction decision

        if (old.source.activeGeneration == ticket.requestGeneration) return@transaction RefreshDecision.STALE
        val reconciliation = try { ChannelIdentityReconciler().reconcile(ref.sourceId, old.channels.map { it.channel }, incoming.map { it.data }) }
            catch (_: IllegalArgumentException) { return@transaction RefreshDecision.INVALID }
        val withOverlays = db.rawQuery("SELECT DISTINCT o.id FROM overlays o JOIN identities i ON o.id=i.id WHERE i.source=?",
            arrayOf(ref.sourceId)).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
        val tombstones = reconciliation.retainTombstones(withOverlays) ?: return@transaction RefreshDecision.INVALID
        val byData = incoming.associateBy { it.data }
        val previous = old.channels.associateBy { it.channel.id }
        val next = reconciliation.channels.map { row -> row.channel to byData.getValue(row.channel.data).attributes } +
            tombstones.retained.map { row -> row to previous.getValue(row.id).attributes }
        for ((channel, attributes) in next) {
            checkCancellation()
            if (channel.id !in previous) db.insertOrThrow("identities", null,
                ContentValues().apply { put("id", channel.id); put("source", ref.sourceId) })
            db.insertOrThrow("catalogue", null, ContentValues().apply {
                put("source", ref.sourceId); put("generation", ticket.requestGeneration); put("id", channel.id)
                put("name", channel.data.name); put("available", if (channel.available) 1 else 0)
                put("search_name", searchName(channel.data.name))
                put("payload", secrets.seal(aad(ref, "channel:${channel.id}"), encodeRecord(channel.data, attributes)))
            })
        }
        checkCancellation()
        db.update("sources", ContentValues().apply {
            put("active_generation", ticket.requestGeneration); put("active_config", ticket.configurationVersion)
            put("validators", secrets.seal(aad(ref, "validators"), JSONObject().put("etag", validators.etag).put("lastModified", validators.lastModified).toString()))
        }, "id=? AND config_version=? AND requested=?", arrayOf(ref.sourceId, ticket.configurationVersion.toString(), ticket.requestGeneration.toString())).also { check(it == 1) }
        db.delete("catalogue", "source=? AND generation<>?", arrayOf(ref.sourceId, ticket.requestGeneration.toString()))
        for (row in tombstones.dropped) {
            checkCancellation()
            db.delete("identities", "id=? AND source=?", arrayOf(row.id, ref.sourceId))
        }
        checkCancellation()
        RefreshDecision.PUBLISH
    }

    fun setOverlay(ref: IptvSourceRef, channelId: String, overlay: IptvChannelOverlay) = transaction { db ->
        source(db, ref)
        require(overlay.customName == null || overlay.customName.length <= 512)
        require(overlay.favouriteRank == null || overlay.favouriteRank >= 0)
        require(overlay.manualGuide == null || listOf(overlay.manualGuide.feedId, overlay.manualGuide.externalId).all { it.isNotBlank() && it.length <= 4096 })
        db.rawQuery("SELECT 1 FROM identities WHERE id=? AND source=?", arrayOf(channelId, ref.sourceId)).use { require(it.moveToFirst()) { "Unknown IPTV channel" } }
        db.insertWithOnConflict("overlays", null, ContentValues().apply {
            put("id", channelId); put("profile", ref.profileId); put("custom_name", overlay.customName)
            put("search_name", overlay.customName?.let(::searchName))
            put("favourite_rank", overlay.favouriteRank); put("hidden", if (overlay.hidden) 1 else 0)
            put("stream_format", overlay.streamFormat.name)
            put("guide_feed", overlay.manualGuide?.feedId); put("guide_id", overlay.manualGuide?.externalId)
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
        bumpBrowseRevision(db, ref)
        Unit
    }

    private fun snapshot(db: SQLiteDatabase, ref: IptvSourceRef, checkCancellation: () -> Unit = {}): IptvCatalogueSnapshot {
        val source = source(db, ref)
        val rows = if (source.activeGeneration == null) emptyList() else db.rawQuery(
            "SELECT c.*, o.custom_name, o.favourite_rank, o.hidden, o.guide_feed, o.guide_id, o.stream_format FROM catalogue c LEFT JOIN overlays o ON c.id=o.id AND o.profile=? WHERE c.source=? AND c.generation=? ORDER BY c.name COLLATE NOCASE, c.id",
            arrayOf(ref.profileId.toString(), ref.sourceId, source.activeGeneration.toString()),
        ).use { cursor -> buildList {
            while (cursor.moveToNext()) {
                checkCancellation()
                add(readItem(cursor, ref))
            }
        } }
        return IptvCatalogueSnapshot(source, rows, cacheValidators(db, source))
    }

    private fun readItem(cursor: Cursor, ref: IptvSourceRef): IptvCatalogueItem {
        val id = cursor.string("id")
        val record = JSONObject(secrets.open(aad(ref, "channel:$id"), cursor.getBlob(cursor.getColumnIndexOrThrow("payload"))))
        val data = ChannelCandidate(record.getString("name"), record.getString("locator"), record.optional("providerId"), record.optional("guideId"), record.optional("variant"))
        val attributes = record.getJSONObject("attributes").let { attrs -> attrs.keys().asSequence().associateWith { attrs.getString(it) } }
        val manual = cursor.nullableString("guide_feed")?.let { feed -> cursor.nullableString("guide_id")?.let { GuideKey(feed, it) } }
        return IptvCatalogueItem(StoredChannel(id, ref.sourceId, data, cursor.number("available") == 1L), attributes,
            IptvChannelOverlay(cursor.nullableString("custom_name"), cursor.nullableNumber("favourite_rank")?.toInt(), cursor.nullableNumber("hidden") == 1L, manual,
                cursor.nullableString("stream_format")?.let(IptvStreamFormat::valueOf) ?: IptvStreamFormat.AUTO))
    }

    private fun cacheValidators(db: SQLiteDatabase, source: IptvSource): IptvCacheValidators? =
        if (!source.playbackEligible) null else readProtected(db, source.ref, "validators")?.let { json ->
            JSONObject(json).let { IptvCacheValidators(it.optional("etag"), it.optional("lastModified")) }
        }

    private fun readProtected(db: SQLiteDatabase, ref: IptvSourceRef, column: String): String? =
        db.query("sources", arrayOf(column), "id=? AND profile=?", arrayOf(ref.sourceId, ref.profileId.toString()), null, null, null).use {
            check(it.moveToFirst()); if (it.isNull(0)) null else secrets.open(aad(ref, column), it.getBlob(0))
        }
    private fun readConnection(db: SQLiteDatabase, ref: IptvSourceRef): IptvSourceConnection {
        val json = JSONObject(readProtected(db, ref, "connection")!!)
        return IptvSourceConnection(json.getString("endpoint"), json.optional("username"), json.optional("password"))
    }
    private fun source(db: SQLiteDatabase, ref: IptvSourceRef): IptvSource =
        db.query("sources", null, "id=? AND profile=?", arrayOf(ref.sourceId, ref.profileId.toString()), null, null, null).use {
            require(it.moveToFirst()) { "Unknown IPTV source" }; readSource(it)
        }
    private fun readSource(c: Cursor) = IptvSource(IptvSourceRef(c.number("profile").toInt(), c.string("id")), c.string("label"),
        IptvSourceKind.valueOf(c.string("kind")), c.string("account_id"), c.number("config_version"), c.number("requested"), c.nullableNumber("active_generation"), c.nullableNumber("active_config"))

    private fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = helper.writableDatabase
        db.beginTransactionNonExclusive()
        var failure: Throwable? = null
        return try {
            db.setMaximumSize(maxDatabaseBytes)
            block(db).also { db.setTransactionSuccessful() }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try { db.endTransaction() } catch (cleanup: Throwable) {
                if (failure == null) throw cleanup else failure.addSuppressed(cleanup)
            }
        }
    }
    override fun close() = helper.close()

    private class Database(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 5) {
        init { require(name.matches(Regex("[A-Za-z0-9_.-]+"))); setWriteAheadLoggingEnabled(true) }
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE sources (id TEXT PRIMARY KEY, profile INTEGER NOT NULL, label TEXT NOT NULL, kind TEXT NOT NULL, account_id TEXT NOT NULL, config_version INTEGER NOT NULL, requested INTEGER NOT NULL, active_generation INTEGER, active_config INTEGER, connection BLOB NOT NULL, validators BLOB)")
            db.execSQL("CREATE INDEX source_profile ON sources(profile)")
            db.execSQL("CREATE TABLE identities (id TEXT PRIMARY KEY, source TEXT NOT NULL REFERENCES sources(id))")
            db.execSQL("CREATE INDEX identity_source ON identities(source)")
            db.execSQL("CREATE TABLE catalogue (source TEXT NOT NULL REFERENCES sources(id), generation INTEGER NOT NULL, id TEXT NOT NULL REFERENCES identities(id), name TEXT NOT NULL, available INTEGER NOT NULL, payload BLOB NOT NULL, PRIMARY KEY(source,generation,id))")
            db.execSQL("CREATE TABLE overlays (id TEXT NOT NULL REFERENCES identities(id), profile INTEGER NOT NULL, custom_name TEXT, favourite_rank INTEGER, hidden INTEGER NOT NULL, guide_feed TEXT, guide_id TEXT, PRIMARY KEY(id,profile))")
            addBrowseSchema(db)
            addStreamFormatSchema(db)
            addGroupingSchema(db)
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            check(oldVersion in 1..4 && newVersion == 5) { "Missing IPTV database migration" }
            if (oldVersion == 1) addBrowseSchema(db)
            if (oldVersion <= 2) addStreamFormatSchema(db)
            if (oldVersion <= 4) addGroupingSchema(db)
            if (oldVersion <= 3) reindexSearch(db)
        }
        private fun reindexSearch(db: SQLiteDatabase) {
            db.rawQuery("SELECT source,generation,id,name FROM catalogue", null).use { c ->
                while (c.moveToNext()) db.execSQL("UPDATE catalogue SET search_name=? WHERE source=? AND generation=? AND id=?", arrayOf(searchName(c.getString(3)), c.getString(0), c.getLong(1), c.getString(2)))
            }
            db.rawQuery("SELECT id,profile,custom_name FROM overlays WHERE custom_name IS NOT NULL", null).use { c ->
                while (c.moveToNext()) db.execSQL("UPDATE overlays SET search_name=? WHERE id=? AND profile=?", arrayOf(searchName(c.getString(2)), c.getString(0), c.getInt(1)))
            }
        }
        private fun addGroupingSchema(db: SQLiteDatabase) {
            db.execSQL("ALTER TABLE sources ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE TABLE accounts(profile INTEGER NOT NULL, id TEXT NOT NULL, label TEXT NOT NULL, max_streams INTEGER NOT NULL CHECK(max_streams BETWEEN 1 AND 16), PRIMARY KEY(profile,id))")
        }
        private fun addStreamFormatSchema(db: SQLiteDatabase) {
            db.execSQL("ALTER TABLE overlays ADD COLUMN stream_format TEXT NOT NULL DEFAULT 'AUTO' CHECK(stream_format IN ('AUTO','HLS','MPEG_TS'))")
        }
        private fun addBrowseSchema(db: SQLiteDatabase) {
            db.execSQL("ALTER TABLE sources ADD COLUMN browse_revision INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE catalogue ADD COLUMN search_name TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE overlays ADD COLUMN search_name TEXT")
            db.execSQL("CREATE INDEX catalogue_browse ON catalogue(source,generation,available,search_name,id)")
            db.execSQL("CREATE TABLE source_guides(source TEXT NOT NULL REFERENCES sources(id), feed TEXT NOT NULL, position INTEGER NOT NULL, priority INTEGER, PRIMARY KEY(source,feed), UNIQUE(source,position))")
        }
    }

    private companion object {
        fun aad(ref: IptvSourceRef, field: String) = "iptv.v1:${ref.profileId}:${ref.sourceId}:$field"
        fun searchName(value: String) = foldSearchText(value)
        fun Cursor.string(key: String) = getString(getColumnIndexOrThrow(key))
        fun Cursor.number(key: String) = getLong(getColumnIndexOrThrow(key))
        fun Cursor.nullableString(key: String): String? = getColumnIndexOrThrow(key).let { if (isNull(it)) null else getString(it) }
        fun Cursor.nullableNumber(key: String): Long? = getColumnIndexOrThrow(key).let { if (isNull(it)) null else getLong(it) }
        fun JSONObject.optional(key: String): String? = if (isNull(key)) null else getString(key)
        fun validHttp(value: String): Boolean = try { URI(value).let { it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() && it.rawUserInfo == null && it.rawFragment == null } } catch (_: Exception) { false }
        fun validateConfiguration(label: String, accountId: String, connection: IptvSourceConnection) {
            require(label.isNotBlank() && label.length <= 240 && accountId.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Invalid IPTV source metadata" }
            require(connection.endpoint.length <= 16_384 && validHttp(connection.endpoint)) { "Invalid IPTV source address" }
            require((connection.username?.length ?: 0) <= 4096 && (connection.password?.length ?: 0) <= 4096) { "IPTV credential limit" }
        }
        fun validateKind(kind: IptvSourceKind, connection: IptvSourceConnection) {
            when (kind) {
                IptvSourceKind.XTREAM -> IptvXtreamClient.serverBase(connection)
                IptvSourceKind.STALKER -> IptvStalkerClient.apiUrl(connection)
                IptvSourceKind.M3U -> Unit
            }
        }
        fun validRecord(row: IptvCatalogueRecord): Boolean = row.data.let {
            it.name.isNotBlank() && it.name.length <= 4096 && it.locator.length <= 16_384 && validHttp(it.locator) &&
                listOf(it.providerId, it.guideId, it.variant).all { id -> (id?.length ?: 0) <= 4096 } &&
                row.attributes.size <= 64 && row.attributes.all { (key, value) -> key.length <= 128 && value.length <= 16_384 } &&
                row.attributes.entries.sumOf { e -> e.key.length + e.value.length } <= 32_768
        }
        fun validValidators(values: IptvCacheValidators) = listOf(values.etag, values.lastModified).all { it == null || (it.length <= 4096 && '\r' !in it && '\n' !in it) }
        fun encodeConnection(c: IptvSourceConnection) = JSONObject().put("endpoint", c.endpoint).put("username", c.username).put("password", c.password).toString()
        fun encodeRecord(c: ChannelCandidate, attributes: Map<String, String>) = JSONObject().put("name", c.name).put("locator", c.locator)
            .put("providerId", c.providerId).put("guideId", c.guideId).put("variant", c.variant).put("attributes", JSONObject(attributes)).toString()
    }
}
