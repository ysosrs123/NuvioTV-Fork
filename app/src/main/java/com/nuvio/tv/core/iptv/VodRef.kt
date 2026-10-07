package com.nuvio.tv.core.iptv

enum class VodKind(val wire: String) {
    MOVIE("movie"), SERIES("series"), EPISODE("episode");
    companion object { fun of(wire: String): VodKind? = entries.firstOrNull { it.wire == wire } }
}

data class VodRef(val profileId: Int, val sourceId: String, val kind: VodKind, val id: String) {
    init {
        require(profileId >= 0 && sourceId.matches(ID)) { "Invalid VOD reference" }
        require(if (kind == VodKind.EPISODE) id.matches(EPISODE_ID) else id.matches(ID)) { "Invalid VOD reference" }
    }

    val seriesId: String? get() = if (kind == VodKind.EPISODE) id.substringBefore('.') else null
    val itemId: String get() = if (kind == VodKind.EPISODE) id.substringAfter('.') else id
    val series: VodRef? get() = seriesId?.let { VodRef(profileId, sourceId, VodKind.SERIES, it) }

    fun format(): String = "$SCHEME$profileId:$sourceId:${kind.wire}:$id"
    override fun toString(): String = format()

    companion object {
        const val SCHEME = "iptv-vod:"
        private val ID = Regex("[A-Za-z0-9_-]{1,80}")
        private val EPISODE_ID = Regex("[A-Za-z0-9_-]{1,80}\\.[A-Za-z0-9_-]{1,80}")

        fun episode(series: VodRef, episodeId: String): VodRef {
            require(series.kind == VodKind.SERIES)
            return VodRef(series.profileId, series.sourceId, VodKind.EPISODE, "${series.id}.$episodeId")
        }

        fun isVod(value: String?): Boolean = value?.startsWith(SCHEME) == true

        fun parse(value: String?): VodRef? {
            if (value == null || value.length > 300 || !value.startsWith(SCHEME)) return null
            val parts = value.removePrefix(SCHEME).split(':')
            if (parts.size != 4) return null
            val profile = parts[0].takeIf { it.isNotEmpty() && it.length <= 9 && it.all(Char::isDigit) }?.toIntOrNull() ?: return null
            val kind = VodKind.of(parts[2]) ?: return null
            return runCatching { VodRef(profile, parts[1], kind, parts[3]) }.getOrNull()
        }
    }
}
