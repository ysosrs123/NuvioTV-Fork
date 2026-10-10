package com.nuvio.tv.core.iptv

data class VodMetaAddon(val types: List<String>, val idPrefixes: List<String>) {
    fun serves(type: String, id: String): Boolean =
        (types.isEmpty() || types.any { it.trim().equals(type, ignoreCase = true) }) &&
            (idPrefixes.isEmpty() || idPrefixes.any { it.isNotBlank() && id.startsWith(it.trim(), ignoreCase = true) })
}

object VodDetailRoute {
    private const val IMDB = "tt"
    private const val TMDB = "tmdb:"

    fun type(kind: VodKind): String? = when (kind) { VodKind.MOVIE -> "movie"; VodKind.SERIES -> "series"; VodKind.EPISODE -> null }

    fun candidates(kind: VodKind, tmdbId: String?, imdbId: String?, addons: List<VodMetaAddon>): List<VodDetailTarget> {
        val type = type(kind) ?: return emptyList()
        return listOfNotNull(XtreamVodParser.imdbId(imdbId), XtreamVodParser.tmdbId(tmdbId)?.let { TMDB + it })
            .filter { id -> addons.any { it.serves(type, id) } }.map { VodDetailTarget(it, type) }
    }

    fun wantsTmdb(kind: VodKind, addons: List<VodMetaAddon>): Boolean = type(kind)?.let { type -> addons.any { it.idPrefixes.isNotEmpty() && it.serves(type, TMDB) } } == true

    fun wantsImdb(kind: VodKind, addons: List<VodMetaAddon>): Boolean = type(kind)?.let { type -> addons.any { it.serves(type, IMDB) } } == true

    fun offered(kind: VodKind, tmdbId: String?, imdbId: String?, addons: List<VodMetaAddon>, tmdbKey: Boolean): Boolean {
        if (candidates(kind, tmdbId, imdbId, addons).isNotEmpty()) return true
        if (!tmdbKey) return false
        if (tmdbMovie(kind, tmdbId) != null) return true
        val tmdb = XtreamVodParser.tmdbId(tmdbId)
        val imdb = XtreamVodParser.imdbId(imdbId)
        return (tmdb != null && imdb == null && wantsImdb(kind, addons)) || (imdb != null && tmdb == null && wantsTmdb(kind, addons))
    }

    fun tmdbMovie(kind: VodKind, tmdbId: String?): VodDetailTarget? =
        if (kind == VodKind.MOVIE) XtreamVodParser.tmdbId(tmdbId)?.let { VodDetailTarget(TMDB + it, "movie") } else null

    fun own(ref: VodRef): VodDetailTarget? = type(ref.kind)?.let { VodDetailTarget(ref.format(), it) }

    fun ownTitle(id: String?): VodRef? = VodRef.parse(id)?.takeIf { it.kind != VodKind.EPISODE }

    fun playable(id: String?): VodRef? = VodRef.parse(id)?.takeIf { it.kind != VodKind.SERIES }

    fun names(text: String?, limit: Int = 24): List<String> = text.orEmpty().split(',', '/', '|', ';').map { it.trim() }
        .filter { it.isNotEmpty() && it.length <= 80 }.distinctBy { it.lowercase() }.take(limit)

    fun minutes(seconds: Int?): Int? = seconds?.takeIf { it > 0 }?.let { (it + 59) / 60 }

    fun rating(value: Double?): Float? = value?.takeIf { it.isFinite() && it > 0 && it <= 10 }?.toFloat()

    suspend fun open(kind: VodKind, tmdbId: String?, imdbId: String?, addons: List<VodMetaAddon>, loads: suspend (VodDetailTarget) -> Boolean): VodDetailTarget? =
        candidates(kind, tmdbId, imdbId, addons).firstOrNull { loads(it) }
}
