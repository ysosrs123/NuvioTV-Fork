package com.nuvio.tv.data.iptv

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement
import com.nuvio.tv.core.iptv.VodCategory
import com.nuvio.tv.core.iptv.VodEpisode
import com.nuvio.tv.core.iptv.VodKind
import com.nuvio.tv.core.iptv.VodMovie
import com.nuvio.tv.core.iptv.VodRef
import com.nuvio.tv.core.iptv.VodSeries
import com.nuvio.tv.core.iptv.VodTitles
import java.io.Closeable
import org.json.JSONObject

data class IptvVodSourceState(
    val ref: IptvSourceRef, val enabled: Boolean?, val detected: Boolean, val refreshedAtMillis: Long?, val movies: Int, val series: Int,
)

data class IptvVodTitle(
    val ref: VodRef, val name: String, val title: String, val year: Int?, val categoryId: String?, val artwork: String?,
    val rating: Double?, val addedSeconds: Long?, val extension: String?, val tmdbId: String?, val imdbId: String?,
) {
    override fun toString(): String = "IptvVodTitle(ref=$ref)"
}

data class IptvVodEpisode(
    val ref: VodRef, val season: Int, val episode: Int, val title: String?, val extension: String?, val durationSeconds: Int?,
    val plot: String?, val still: String?, val tmdbId: String?,
) {
    override fun toString(): String = "IptvVodEpisode(ref=$ref)"
}

data class IptvVodCategory(val ref: IptvSourceRef, val kind: VodKind, val id: String, val name: String, val count: Int)
data class IptvVodPage(val items: List<IptvVodTitle>, val nextOffset: Int?)
data class IptvVodEpisodes(val episodes: List<IptvVodEpisode>, val fetchedAtMillis: Long?)
data class IptvVodLocator(val url: String, val headers: Map<String, String>) {
    override fun toString(): String = "IptvVodLocator(withheld)"
}
data class IptvVodResume(val ref: VodRef, val positionMillis: Long, val durationMillis: Long, val updatedAtMillis: Long)

class IptvVodStore(
    context: Context,
    databaseName: String = "iptv-vod.db",
    private val secrets: IptvSecretBox = EnvelopeIptvSecretBox(AndroidIptvSecretBox()),
    private val maxDatabaseBytes: Long = 512L * 1024 * 1024,
    private val now: () -> Long = System::currentTimeMillis,
    private val chunkRows: Int = 2_000,
) : Closeable, IptvSourceRemovalListener {
    init { require(maxDatabaseBytes >= 64 * 1024 && chunkRows > 0) }
    private val helper = Database(context.applicationContext, databaseName)

    fun state(ref: IptvSourceRef): IptvVodSourceState? = transaction { db ->
        db.query("vod_sources", null, "source=? AND profile=?", arrayOf(ref.sourceId, ref.profileId.toString()), null, null, null).use {
            if (it.moveToFirst()) readState(it) else null
        }
    }

    fun states(profileId: Int): List<IptvVodSourceState> = transaction { db ->
        require(profileId >= 0)
        db.query("vod_sources", null, "profile=?", arrayOf(profileId.toString()), null, null, null).use { c ->
            buildList { while (c.moveToNext()) add(readState(c)) }
        }
    }

    fun setEnabled(ref: IptvSourceRef, enabled: Boolean?) = transaction { db ->
        ensureSource(db, ref)
        db.update("vod_sources", ContentValues().apply { if (enabled == null) putNull("enabled") else put("enabled", if (enabled) 1 else 0) },
            "source=?", arrayOf(ref.sourceId))
        if (enabled == false) clearRows(db, ref.sourceId)
        Unit
    }

    fun setDetected(ref: IptvSourceRef, detected: Boolean) = transaction { db ->
        ensureSource(db, ref)
        db.update("vod_sources", ContentValues().apply { put("detected", if (detected) 1 else 0) }, "source=?", arrayOf(ref.sourceId))
        Unit
    }

    fun beginImport(ref: IptvSourceRef): Import = transaction { db ->
        ensureSource(db, ref)
        db.execSQL("UPDATE vod_sources SET pending=pending+1 WHERE source=?", arrayOf(ref.sourceId))
        val generation = db.rawQuery("SELECT pending FROM vod_sources WHERE source=?", arrayOf(ref.sourceId)).use { check(it.moveToFirst()); it.getLong(0) }
        val args = arrayOf(ref.sourceId, generation.toString(), ref.sourceId)
        for (table in listOf("titles", "categories")) db.delete(table, "source=? AND generation<? AND generation<>COALESCE((SELECT active FROM vod_sources WHERE source=?),-1)", args)
        db.delete("episodes", "source=? AND generation IS NOT NULL AND generation<? AND generation<>COALESCE((SELECT active FROM vod_sources WHERE source=?),-1)", args)
        Import(ref, generation)
    }

    inner class Import internal constructor(val ref: IptvSourceRef, private val generation: Long) {
        private val titles = ArrayList<TitleRow>()
        private val episodes = ArrayList<EpisodeRow>()
        private val categoryRows = ArrayList<VodCategory>()
        private var movieCount = 0
        private var seriesCount = 0
        private var position = 0

        fun categories(values: List<VodCategory>) { categoryRows += values }

        fun movie(movie: VodMovie, locator: IptvVodLocator? = null) {
            movieCount++
            titles += TitleRow(VodKind.MOVIE, movie.providerId, movie.name, movie.year, movie.categoryId, movie.poster, movie.rating, movie.addedSeconds,
                movie.extension, movie.tmdbId, movie.imdbId, locator, position++)
            if (titles.size >= chunkRows) flush()
        }

        fun series(series: VodSeries) {
            seriesCount++
            titles += TitleRow(VodKind.SERIES, series.providerId, series.name, series.year, series.categoryId, series.cover, series.rating, series.addedSeconds,
                null, series.tmdbId, series.imdbId, null, position++)
            if (titles.size >= chunkRows) flush()
        }

        fun episode(seriesId: String, episode: VodEpisode, locator: IptvVodLocator?) {
            episodes += EpisodeRow(seriesId, episode, locator)
            if (episodes.size >= chunkRows) flush()
        }

        val count: Int get() = movieCount + seriesCount

        fun flush() {
            if (titles.isEmpty() && episodes.isEmpty() && categoryRows.isEmpty()) return
            val sealedTitles = titles.map { row -> row.locator?.let { seal(VodRef(ref.profileId, ref.sourceId, row.kind, row.id), it) } }
            val sealedEpisodes = episodes.map { row -> row.locator?.let { seal(VodRef(ref.profileId, ref.sourceId, VodKind.EPISODE, "${row.seriesId}.${row.episode.providerId}"), it) } }
            transaction { db ->
                check(pending(db)) { "VOD import replaced" }
                if (categoryRows.isNotEmpty()) db.compileStatement("INSERT OR IGNORE INTO categories(source,generation,kind,id,name,position) VALUES(?,?,?,?,?,?)").use { statement ->
                    for ((index, category) in categoryRows.withIndex()) {
                        statement.clearBindings()
                        statement.bindString(1, ref.sourceId); statement.bindLong(2, generation); statement.bindString(3, category.kind.wire)
                        statement.bindString(4, category.id); statement.bindString(5, category.name.take(240)); statement.bindLong(6, index.toLong())
                        statement.executeInsert()
                    }
                }
                if (titles.isNotEmpty()) db.compileStatement("INSERT OR IGNORE INTO titles(source,generation,kind,id,name,title,title_key,search_name,year,category,artwork,rating,added,ext,tmdb,imdb,locator,position) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)").use { statement ->
                    for ((index, row) in titles.withIndex()) {
                        val parsed = VodTitles.parse(row.name, row.year)
                        statement.clearBindings()
                        statement.bindString(1, ref.sourceId); statement.bindLong(2, generation); statement.bindString(3, row.kind.wire)
                        statement.bindString(4, row.id); statement.bindString(5, row.name); statement.bindString(6, parsed.display.take(1024))
                        statement.bindString(7, parsed.matchKey); statement.bindString(8, " ${parsed.key} ")
                        bindLong(statement, 9, parsed.year?.toLong()); bindString(statement, 10, row.category); bindString(statement, 11, row.artwork)
                        row.rating?.let { statement.bindDouble(12, it) } ?: statement.bindNull(12)
                        bindLong(statement, 13, row.added); bindString(statement, 14, row.extension); bindString(statement, 15, row.tmdb)
                        bindString(statement, 16, row.imdb)
                        sealedTitles[index]?.let { statement.bindBlob(17, it) } ?: statement.bindNull(17)
                        statement.bindLong(18, row.position.toLong())
                        statement.executeInsert()
                    }
                }
                if (episodes.isNotEmpty()) db.compileStatement(EPISODE_INSERT).use { statement ->
                    for ((index, row) in episodes.withIndex()) bindEpisode(statement, ref.sourceId, row.seriesId, row.episode, generation, sealedEpisodes[index])
                }
            }
            titles.clear(); episodes.clear(); categoryRows.clear()
        }

        fun publish(detected: Boolean? = null): Boolean {
            if (!transaction { db -> pending(db) }) { titles.clear(); episodes.clear(); categoryRows.clear(); return false }
            flush()
            return transaction { db ->
                if (!pending(db)) return@transaction false
                val values = ContentValues().apply {
                    put("active", generation); put("refreshed_at", now()); put("movies", movieCount); put("series", seriesCount)
                    detected?.let { put("detected", if (it) 1 else 0) }
                }
                if (db.update("vod_sources", values, "source=? AND pending=?", arrayOf(ref.sourceId, generation.toString())) != 1) return@transaction false
                val args = arrayOf(ref.sourceId, generation.toString())
                db.delete("titles", "source=? AND generation<>?", args)
                db.delete("categories", "source=? AND generation<>?", args)
                db.delete("episodes", "source=? AND generation IS NOT NULL AND generation<>?", args)
                db.delete("episodes", "source=? AND generation IS NULL AND series NOT IN (SELECT id FROM titles WHERE source=? AND generation=? AND kind='series')",
                    arrayOf(ref.sourceId, ref.sourceId, generation.toString()))
                db.delete("episode_fetch", "source=? AND series NOT IN (SELECT id FROM titles WHERE source=? AND generation=? AND kind='series')",
                    arrayOf(ref.sourceId, ref.sourceId, generation.toString()))
                true
            }.also { IptvLog.info("vod save movies=$movieCount series=$seriesCount published=$it") }
        }

        fun discard() {
            titles.clear(); episodes.clear(); categoryRows.clear()
            transaction { db ->
                val args = arrayOf(ref.sourceId, generation.toString(), ref.sourceId)
                for (table in listOf("titles", "categories")) db.delete(table, "source=? AND generation=? AND generation<>COALESCE((SELECT active FROM vod_sources WHERE source=?),-1)", args)
                db.delete("episodes", "source=? AND generation=? AND generation<>COALESCE((SELECT active FROM vod_sources WHERE source=?),-1)", args)
            }
        }

        private fun pending(db: SQLiteDatabase): Boolean =
            db.rawQuery("SELECT 1 FROM vod_sources WHERE source=? AND profile=? AND pending=?", arrayOf(ref.sourceId, ref.profileId.toString(), generation.toString())).use { it.moveToFirst() }
    }

    fun byTmdb(profileId: Int, kind: VodKind, tmdbId: String): List<IptvVodTitle> = titles(profileId, kind, "t.tmdb=?", tmdbId, 50)
    fun byImdb(profileId: Int, kind: VodKind, imdbId: String): List<IptvVodTitle> = titles(profileId, kind, "t.imdb=?", imdbId, 50)
    fun byMatchKey(profileId: Int, kind: VodKind, matchKey: String): List<IptvVodTitle> =
        if (matchKey.isEmpty()) emptyList() else titles(profileId, kind, "t.title_key=?", matchKey, 100)

    fun search(profileId: Int, kind: VodKind?, text: String, limit: Int = 100): List<IptvVodTitle> {
        require(limit in 1..200)
        val key = VodTitles.normalise(text.trim().take(200))
        if (key.isEmpty()) return emptyList()
        return transaction { db ->
            val kindFilter = if (kind == null) "" else " AND t.kind=?"
            val args = listOfNotNull(profileId.toString(), key, kind?.wire, key, limit.toString())
            db.rawQuery("SELECT t.* FROM titles t JOIN vod_sources s ON s.source=t.source AND t.generation=s.active WHERE s.profile=? AND instr(t.search_name,?)>0$kindFilter ORDER BY CASE WHEN t.search_name LIKE ' ' || ? || ' %' THEN 0 ELSE 1 END, t.title COLLATE NOCASE, t.id LIMIT ?",
                args.toTypedArray()).use { c -> buildList { while (c.moveToNext()) add(readTitle(c, profileId)) } }
        }
    }

    fun title(ref: VodRef): IptvVodTitle? = transaction { db ->
        require(ref.kind != VodKind.EPISODE)
        db.rawQuery("SELECT t.* FROM titles t JOIN vod_sources s ON s.source=t.source AND t.generation=s.active WHERE s.profile=? AND t.source=? AND t.kind=? AND t.id=?",
            arrayOf(ref.profileId.toString(), ref.sourceId, ref.kind.wire, ref.id)).use { if (it.moveToFirst()) readTitle(it, ref.profileId) else null }
    }

    fun categories(ref: IptvSourceRef, kind: VodKind): List<IptvVodCategory> = transaction { db ->
        db.rawQuery("SELECT c.id,c.name,(SELECT COUNT(*) FROM titles t WHERE t.source=c.source AND t.generation=c.generation AND t.kind=c.kind AND t.category=c.id) FROM categories c JOIN vod_sources s ON s.source=c.source AND c.generation=s.active WHERE s.profile=? AND c.source=? AND c.kind=? ORDER BY c.position LIMIT 5000",
            arrayOf(ref.profileId.toString(), ref.sourceId, kind.wire)).use { c ->
            buildList { while (c.moveToNext()) if (c.getInt(2) > 0) add(IptvVodCategory(ref, kind, c.getString(0), c.getString(1), c.getInt(2))) }
        }
    }

    fun page(ref: IptvSourceRef, kind: VodKind, categoryId: String?, offset: Int = 0, limit: Int = 100, recent: Boolean = false): IptvVodPage = transaction { db ->
        require(limit in 1..200 && offset in 0..MAX_OFFSET && kind != VodKind.EPISODE)
        val args = mutableListOf(ref.profileId.toString(), ref.sourceId, kind.wire)
        val category = categoryId?.let { args += it; " AND t.category=?" }.orEmpty()
        val order = if (recent) "t.added IS NULL, t.added DESC, t.position" else "t.position"
        args += (limit + 1).toString(); args += offset.toString()
        val items = db.rawQuery("SELECT t.* FROM titles t JOIN vod_sources s ON s.source=t.source AND t.generation=s.active WHERE s.profile=? AND t.source=? AND t.kind=?$category ORDER BY $order LIMIT ? OFFSET ?",
            args.toTypedArray()).use { c -> buildList { while (c.moveToNext()) add(readTitle(c, ref.profileId)) } }
        IptvVodPage(items.take(limit), if (items.size > limit) offset + limit else null)
    }

    fun episodes(series: VodRef): IptvVodEpisodes = transaction { db ->
        require(series.kind == VodKind.SERIES)
        val fetched = db.rawQuery("SELECT fetched_at FROM episode_fetch WHERE source=? AND series=?", arrayOf(series.sourceId, series.id)).use {
            if (it.moveToFirst()) it.getLong(0) else null
        }
        val episodes = db.rawQuery("SELECT e.* FROM episodes e JOIN vod_sources s ON s.source=e.source WHERE s.profile=? AND e.source=? AND e.series=? ORDER BY e.season,e.episode,e.id LIMIT 20000",
            arrayOf(series.profileId.toString(), series.sourceId, series.id)).use { c -> buildList { while (c.moveToNext()) add(readEpisode(c, series)) } }
        IptvVodEpisodes(episodes, fetched)
    }

    fun episode(series: VodRef, season: Int, episode: Int): IptvVodEpisode? = transaction { db ->
        require(series.kind == VodKind.SERIES)
        db.rawQuery("SELECT e.* FROM episodes e JOIN vod_sources s ON s.source=e.source WHERE s.profile=? AND e.source=? AND e.series=? AND e.season=? AND e.episode=? ORDER BY e.id LIMIT 1",
            arrayOf(series.profileId.toString(), series.sourceId, series.id, season.toString(), episode.toString())).use { if (it.moveToFirst()) readEpisode(it, series) else null }
    }

    fun episode(ref: VodRef): IptvVodEpisode? = transaction { db ->
        require(ref.kind == VodKind.EPISODE)
        val series = requireNotNull(ref.series)
        db.rawQuery("SELECT e.* FROM episodes e JOIN vod_sources s ON s.source=e.source WHERE s.profile=? AND e.source=? AND e.series=? AND e.id=?",
            arrayOf(ref.profileId.toString(), ref.sourceId, series.id, ref.itemId)).use { if (it.moveToFirst()) readEpisode(it, series) else null }
    }

    fun saveEpisodes(series: VodRef, episodes: List<VodEpisode>, info: VodSeries? = null): Boolean = transaction { db ->
        require(series.kind == VodKind.SERIES && episodes.size <= 20_000)
        val active = db.rawQuery("SELECT active FROM vod_sources WHERE source=? AND profile=?", arrayOf(series.sourceId, series.profileId.toString())).use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
        } ?: return@transaction false
        if (db.rawQuery("SELECT 1 FROM titles WHERE source=? AND generation=? AND kind='series' AND id=?", arrayOf(series.sourceId, active.toString(), series.id)).use { !it.moveToFirst() })
            return@transaction false
        db.delete("episodes", "source=? AND series=? AND generation IS NULL", arrayOf(series.sourceId, series.id))
        db.compileStatement(EPISODE_INSERT).use { statement -> for (episode in episodes) bindEpisode(statement, series.sourceId, series.id, episode, null, null) }
        db.insertWithOnConflict("episode_fetch", null, ContentValues().apply {
            put("source", series.sourceId); put("series", series.id); put("fetched_at", now())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        if (info != null) {
            info.tmdbId?.let { db.execSQL("UPDATE titles SET tmdb=? WHERE source=? AND generation=? AND kind='series' AND id=? AND tmdb IS NULL", arrayOf<Any>(it, series.sourceId, active, series.id)) }
            info.imdbId?.let { db.execSQL("UPDATE titles SET imdb=? WHERE source=? AND generation=? AND kind='series' AND id=? AND imdb IS NULL", arrayOf<Any>(it, series.sourceId, active, series.id)) }
        }
        true
    }

    fun saveIds(ref: VodRef, tmdbId: String?, imdbId: String?) = transaction { db ->
        require(ref.kind != VodKind.EPISODE)
        val args = arrayOf(ref.sourceId, ref.sourceId, ref.kind.wire, ref.id)
        val where = "source=? AND generation=(SELECT active FROM vod_sources WHERE source=?) AND kind=? AND id=?"
        tmdbId?.let { db.execSQL("UPDATE titles SET tmdb=? WHERE $where AND tmdb IS NULL", arrayOf<Any>(it, *args)) }
        imdbId?.let { db.execSQL("UPDATE titles SET imdb=? WHERE $where AND imdb IS NULL", arrayOf<Any>(it, *args)) }
        Unit
    }

    fun resume(ref: VodRef): IptvVodResume? = transaction { db ->
        require(ref.kind != VodKind.SERIES)
        db.rawQuery("SELECT position,duration,updated FROM resume WHERE source=? AND profile=? AND kind=? AND id=?",
            arrayOf(ref.sourceId, ref.profileId.toString(), ref.kind.wire, ref.id)).use {
            if (it.moveToFirst()) IptvVodResume(ref, it.getLong(0), it.getLong(1), it.getLong(2)) else null
        }
    }

    fun resumes(series: VodRef): List<IptvVodResume> = transaction { db ->
        require(series.kind == VodKind.SERIES)
        db.rawQuery("SELECT id,position,duration,updated FROM resume WHERE source=? AND profile=? AND kind='episode' AND substr(id,1,?)=? ORDER BY updated DESC LIMIT 2000",
            arrayOf(series.sourceId, series.profileId.toString(), (series.id.length + 1).toString(), "${series.id}.")).use { c ->
            buildList { while (c.moveToNext()) add(IptvVodResume(VodRef(series.profileId, series.sourceId, VodKind.EPISODE, c.getString(0)), c.getLong(1), c.getLong(2), c.getLong(3))) }
        }
    }

    fun saveResume(ref: VodRef, positionMillis: Long, durationMillis: Long) = transaction { db ->
        require(ref.kind != VodKind.SERIES && positionMillis >= 0 && durationMillis >= 0)
        db.insertWithOnConflict("resume", null, ContentValues().apply {
            put("source", ref.sourceId); put("profile", ref.profileId); put("kind", ref.kind.wire); put("id", ref.id)
            put("position", positionMillis); put("duration", durationMillis); put("updated", now())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        db.execSQL("DELETE FROM resume WHERE profile=? AND rowid NOT IN (SELECT rowid FROM resume WHERE profile=? ORDER BY updated DESC LIMIT $MAX_RESUME)",
            arrayOf<Any>(ref.profileId, ref.profileId))
    }

    fun clearResume(ref: VodRef) = transaction { db ->
        db.delete("resume", "source=? AND profile=? AND kind=? AND id=?", arrayOf(ref.sourceId, ref.profileId.toString(), ref.kind.wire, ref.id))
        Unit
    }

    fun locator(ref: VodRef): IptvVodLocator? = transaction { db ->
        val (sql, args) = if (ref.kind == VodKind.EPISODE)
            "SELECT e.locator FROM episodes e JOIN vod_sources s ON s.source=e.source WHERE s.profile=? AND e.source=? AND e.series=? AND e.id=?" to
                arrayOf(ref.profileId.toString(), ref.sourceId, requireNotNull(ref.seriesId), ref.itemId)
        else "SELECT t.locator FROM titles t JOIN vod_sources s ON s.source=t.source AND t.generation=s.active WHERE s.profile=? AND t.source=? AND t.kind=? AND t.id=?" to
            arrayOf(ref.profileId.toString(), ref.sourceId, ref.kind.wire, ref.id)
        db.rawQuery(sql, args).use { c ->
            if (!c.moveToFirst() || c.isNull(0)) null else {
                val json = JSONObject(secrets.open(aad(ref), c.getBlob(0)))
                val headers = json.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { h.getString(it) } }.orEmpty()
                IptvVodLocator(json.getString("url"), headers)
            }
        }
    }

    override fun removeSource(ref: IptvSourceRef) {
        transaction { db -> clearRows(db, ref.sourceId); db.delete("vod_sources", "source=?", arrayOf(ref.sourceId)); db.delete("resume", "source=?", arrayOf(ref.sourceId)) }
    }

    override fun removeProfile(profileId: Int) {
        transaction { db ->
            val sources = db.query("vod_sources", arrayOf("source"), "profile=?", arrayOf(profileId.toString()), null, null, null).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
            for (source in sources) clearRows(db, source)
            db.delete("vod_sources", "profile=?", arrayOf(profileId.toString()))
            db.delete("resume", "profile=?", arrayOf(profileId.toString()))
        }
    }

    override fun clearAllProfiles() { transaction { db -> listOf("titles", "categories", "episodes", "episode_fetch", "vod_sources", "resume").forEach { db.delete(it, null, null) } } }

    private fun clearRows(db: SQLiteDatabase, source: String) {
        listOf("titles", "categories", "episodes", "episode_fetch").forEach { db.delete(it, "source=?", arrayOf(source)) }
        db.execSQL("UPDATE vod_sources SET active=NULL, movies=0, series=0, refreshed_at=NULL WHERE source=?", arrayOf(source))
    }

    private fun titles(profileId: Int, kind: VodKind, condition: String, value: String, limit: Int): List<IptvVodTitle> = transaction { db ->
        require(profileId >= 0 && kind != VodKind.EPISODE && value.length <= 200)
        db.rawQuery("SELECT t.* FROM titles t JOIN vod_sources s ON s.source=t.source AND t.generation=s.active WHERE s.profile=? AND t.kind=? AND $condition ORDER BY t.source,t.position LIMIT $limit",
            arrayOf(profileId.toString(), kind.wire, value)).use { c -> buildList { while (c.moveToNext()) add(readTitle(c, profileId)) } }
    }

    private fun ensureSource(db: SQLiteDatabase, ref: IptvSourceRef) {
        db.rawQuery("SELECT profile FROM vod_sources WHERE source=?", arrayOf(ref.sourceId)).use {
            if (it.moveToFirst()) { require(it.getInt(0) == ref.profileId) { "Unknown IPTV source" }; return }
        }
        db.insertOrThrow("vod_sources", null, ContentValues().apply {
            put("source", ref.sourceId); put("profile", ref.profileId); put("detected", 0); put("pending", 0); put("movies", 0); put("series", 0)
        })
    }

    private fun seal(ref: VodRef, locator: IptvVodLocator): ByteArray =
        secrets.seal(aad(ref), JSONObject().put("url", locator.url).put("headers", JSONObject(locator.headers)).toString())

    private fun bindEpisode(statement: SQLiteStatement, source: String, series: String, episode: VodEpisode, generation: Long?, locator: ByteArray?) {
        statement.clearBindings()
        statement.bindString(1, source); statement.bindString(2, series); statement.bindString(3, episode.providerId)
        bindLong(statement, 4, generation); statement.bindLong(5, episode.season.toLong()); statement.bindLong(6, episode.episode.toLong())
        bindString(statement, 7, episode.title); bindString(statement, 8, episode.extension); bindLong(statement, 9, episode.durationSeconds?.toLong())
        bindString(statement, 10, episode.plot); bindString(statement, 11, episode.still); bindString(statement, 12, episode.tmdbId)
        locator?.let { statement.bindBlob(13, it) } ?: statement.bindNull(13)
        statement.executeInsert()
    }

    private fun readState(c: Cursor) = IptvVodSourceState(IptvSourceRef(c.getInt(c.getColumnIndexOrThrow("profile")), c.string("source")),
        c.nullableLong("enabled")?.let { it == 1L }, c.getLong(c.getColumnIndexOrThrow("detected")) == 1L, c.nullableLong("refreshed_at"),
        c.getInt(c.getColumnIndexOrThrow("movies")), c.getInt(c.getColumnIndexOrThrow("series")))

    private fun readTitle(c: Cursor, profileId: Int) = IptvVodTitle(
        VodRef(profileId, c.string("source"), VodKind.of(c.string("kind")) ?: VodKind.MOVIE, c.string("id")), c.string("name"), c.string("title"),
        c.nullableLong("year")?.toInt(), c.nullableString("category"), c.nullableString("artwork"),
        c.getColumnIndexOrThrow("rating").let { if (c.isNull(it)) null else c.getDouble(it) }, c.nullableLong("added"), c.nullableString("ext"),
        c.nullableString("tmdb"), c.nullableString("imdb"))

    private fun readEpisode(c: Cursor, series: VodRef) = IptvVodEpisode(VodRef.episode(series, c.string("id")),
        c.getInt(c.getColumnIndexOrThrow("season")), c.getInt(c.getColumnIndexOrThrow("episode")), c.nullableString("title"), c.nullableString("ext"),
        c.nullableLong("duration")?.toInt(), c.nullableString("plot"), c.nullableString("still"), c.nullableString("tmdb"))

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

    private class TitleRow(
        val kind: VodKind, val id: String, val name: String, val year: Int?, val category: String?, val artwork: String?, val rating: Double?,
        val added: Long?, val extension: String?, val tmdb: String?, val imdb: String?, val locator: IptvVodLocator?, val position: Int,
    )
    private class EpisodeRow(val seriesId: String, val episode: VodEpisode, val locator: IptvVodLocator?)

    private class Database(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 2) {
        init { require(name.matches(Regex("[A-Za-z0-9_.-]+"))); setWriteAheadLoggingEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE vod_sources (source TEXT PRIMARY KEY, profile INTEGER NOT NULL, enabled INTEGER, detected INTEGER NOT NULL, pending INTEGER NOT NULL, active INTEGER, refreshed_at INTEGER, movies INTEGER NOT NULL, series INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX vod_source_profile ON vod_sources(profile)")
            db.execSQL("CREATE TABLE categories (source TEXT NOT NULL, generation INTEGER NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL, position INTEGER NOT NULL, PRIMARY KEY(source,generation,kind,id))")
            db.execSQL("CREATE TABLE titles (source TEXT NOT NULL, generation INTEGER NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL, title TEXT NOT NULL, title_key TEXT NOT NULL, search_name TEXT NOT NULL, year INTEGER, category TEXT, artwork TEXT, rating REAL, added INTEGER, ext TEXT, tmdb TEXT, imdb TEXT, locator BLOB, position INTEGER NOT NULL, PRIMARY KEY(source,generation,kind,id))")
            db.execSQL("CREATE INDEX titles_order ON titles(source,generation,kind,category,position)")
            db.execSQL("CREATE INDEX titles_key ON titles(title_key)")
            db.execSQL("CREATE INDEX titles_tmdb ON titles(tmdb) WHERE tmdb IS NOT NULL")
            db.execSQL("CREATE INDEX titles_imdb ON titles(imdb) WHERE imdb IS NOT NULL")
            db.execSQL("CREATE TABLE episodes (source TEXT NOT NULL, series TEXT NOT NULL, id TEXT NOT NULL, generation INTEGER, season INTEGER NOT NULL, episode INTEGER NOT NULL, title TEXT, ext TEXT, duration INTEGER, plot TEXT, still TEXT, tmdb TEXT, locator BLOB, PRIMARY KEY(source,series,id))")
            db.execSQL("CREATE INDEX episodes_number ON episodes(source,series,season,episode)")
            db.execSQL("CREATE TABLE episode_fetch (source TEXT NOT NULL, series TEXT NOT NULL, fetched_at INTEGER NOT NULL, PRIMARY KEY(source,series))")
            createResume(db)
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            check(newVersion == 2) { "Missing IPTV VOD database migration" }
            if (oldVersion < 2) createResume(db)
        }
        private fun createResume(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE resume (source TEXT NOT NULL, profile INTEGER NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL, position INTEGER NOT NULL, duration INTEGER NOT NULL, updated INTEGER NOT NULL, PRIMARY KEY(source,kind,id))")
            db.execSQL("CREATE INDEX resume_updated ON resume(profile,updated)")
        }
    }

    private companion object {
        const val MAX_OFFSET = 250_000
        const val MAX_RESUME = 1_000
        const val EPISODE_INSERT = "INSERT OR REPLACE INTO episodes(source,series,id,generation,season,episode,title,ext,duration,plot,still,tmdb,locator) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)"
        fun aad(ref: VodRef) = "iptv-vod.v1:${ref.profileId}:${ref.sourceId}:${ref.kind.wire}:${ref.id}"
        fun bindString(statement: SQLiteStatement, index: Int, value: String?) { if (value == null) statement.bindNull(index) else statement.bindString(index, value) }
        fun bindLong(statement: SQLiteStatement, index: Int, value: Long?) { if (value == null) statement.bindNull(index) else statement.bindLong(index, value) }
        fun Cursor.string(key: String): String = getString(getColumnIndexOrThrow(key))
        fun Cursor.nullableString(key: String): String? = getColumnIndexOrThrow(key).let { if (isNull(it)) null else getString(it) }
        fun Cursor.nullableLong(key: String): Long? = getColumnIndexOrThrow(key).let { if (isNull(it)) null else getLong(it) }
    }
}
