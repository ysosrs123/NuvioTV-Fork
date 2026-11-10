package com.nuvio.tv.data.mediaserver.plex

import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.data.mediaserver.DrawnArtworkWidths
import com.nuvio.tv.data.mediaserver.ServerIndexEntry
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.data.mediaserver.ServerMediaKind
import com.nuvio.tv.data.mediaserver.ServerTitle
import com.nuvio.tv.data.mediaserver.ServerUserState
import com.nuvio.tv.data.mediaserver.domainType
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaCastMember
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.Video
import kotlin.math.roundToInt
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class ContainerReply(@SerialName("MediaContainer") val container: MediaContainerWire = MediaContainerWire())

@Serializable
internal data class MediaContainerWire(
    val size: Int? = null,
    val totalSize: Int? = null,
    val machineIdentifier: String? = null,
    val friendlyName: String? = null,
    val version: String? = null,
    @SerialName("generalDecisionCode") val generalDecisionCode: Int? = null,
    @SerialName("generalDecisionText") val generalDecisionText: String? = null,
    @SerialName("transcodeDecisionCode") val transcodeDecisionCode: Int? = null,
    @SerialName("transcodeDecisionText") val transcodeDecisionText: String? = null,
    @SerialName("directPlayDecisionText") val directPlayDecisionText: String? = null,
    @SerialName("Directory") val directories: List<DirectoryWire> = emptyList(),
    @SerialName("Metadata") val metadata: List<MetadataWire> = emptyList(),
    @SerialName("Hub") val hubs: List<HubWire> = emptyList()
)

@Serializable
internal data class DirectoryWire(
    val key: String = "",
    val title: String = "",
    val type: String = "",
    val agent: String? = null
)

@Serializable
internal data class HubWire(
    val key: String? = null,
    val title: String = "",
    val type: String = "",
    @SerialName("hubIdentifier") val hubIdentifier: String = "",
    val context: String? = null,
    val size: Int? = null,
    @SerialName("Metadata") val metadata: List<MetadataWire> = emptyList()
)

@Serializable
internal data class MetadataWire(
    @SerialName("ratingKey") val ratingKey: String = "",
    val key: String = "",
    val type: String = "",
    val title: String = "",
    @SerialName("originalTitle") val originalTitle: String? = null,
    val year: Int? = null,
    val summary: String? = null,
    val tagline: String? = null,
    val thumb: String? = null,
    val art: String? = null,
    @SerialName("grandparentThumb") val grandparentThumb: String? = null,
    @SerialName("grandparentArt") val grandparentArt: String? = null,
    @SerialName("grandparentTitle") val grandparentTitle: String? = null,
    @SerialName("grandparentRatingKey") val grandparentRatingKey: String? = null,
    @SerialName("parentIndex") val parentIndex: Int? = null,
    val index: Int? = null,
    val duration: Long? = null,
    @SerialName("viewOffset") val viewOffset: Long? = null,
    @SerialName("viewCount") val viewCount: Int? = null,
    @SerialName("lastViewedAt") val lastViewedAt: Long? = null,
    @SerialName("addedAt") val addedAt: Long? = null,
    @SerialName("originallyAvailableAt") val originallyAvailableAt: String? = null,
    @SerialName("contentRating") val contentRating: String? = null,
    @SerialName("audienceRating") val audienceRating: Double? = null,
    val rating: Double? = null,
    val studio: String? = null,
    @SerialName("leafCount") val leafCount: Int? = null,
    @SerialName("viewedLeafCount") val viewedLeafCount: Int? = null,
    @SerialName("librarySectionID") val librarySectionId: Long? = null,
    val guid: String? = null,
    @SerialName("Guid") val guids: List<TagWire> = emptyList(),
    @SerialName("Genre") val genres: List<TagWire> = emptyList(),
    @SerialName("Country") val countries: List<TagWire> = emptyList(),
    @SerialName("Director") val directors: List<TagWire> = emptyList(),
    @SerialName("Writer") val writers: List<TagWire> = emptyList(),
    @SerialName("Role") val roles: List<RoleWire> = emptyList(),
    @SerialName("Image") val images: List<ImageWire> = emptyList(),
    @SerialName("Media") val media: List<MediaWire> = emptyList(),
    @SerialName("Marker") val markers: List<MarkerWire> = emptyList(),
    @SerialName("Chapter") val chapters: List<ChapterWire> = emptyList()
)

@Serializable
internal data class TagWire(val id: String? = null, val tag: String? = null)

@Serializable
internal data class RoleWire(val tag: String = "", val role: String? = null, val thumb: String? = null)

@Serializable
internal data class ImageWire(val type: String = "", val url: String = "")

@Serializable
internal data class MediaWire(
    val id: Long = 0,
    val duration: Long? = null,
    val bitrate: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("videoResolution") val videoResolution: String? = null,
    @SerialName("videoCodec") val videoCodec: String? = null,
    @SerialName("videoProfile") val videoProfile: String? = null,
    @SerialName("videoDynamicRange") val videoDynamicRange: String? = null,
    @SerialName("audioCodec") val audioCodec: String? = null,
    @SerialName("audioChannels") val audioChannels: Int? = null,
    val container: String? = null,
    @SerialName("editionTitle") val editionTitle: String? = null,
    val title: String? = null,
    @SerialName("Part") val parts: List<PartWire> = emptyList()
)

@Serializable
internal data class PartWire(
    val id: Long = 0,
    val key: String = "",
    val file: String? = null,
    val size: Long? = null,
    val duration: Long? = null,
    val container: String? = null,
    val indexes: String? = null,
    @SerialName("Stream") val streams: List<StreamWire> = emptyList()
)

@Serializable
internal data class StreamWire(
    val id: Long = 0,
    @SerialName("streamType") val streamType: Int = 0,
    val codec: String? = null,
    val index: Int? = null,
    val key: String? = null,
    val language: String? = null,
    @SerialName("languageCode") val languageCode: String? = null,
    @SerialName("languageTag") val languageTag: String? = null,
    @SerialName("displayTitle") val displayTitle: String? = null,
    @SerialName("extendedDisplayTitle") val extendedDisplayTitle: String? = null,
    val title: String? = null,
    val selected: Boolean = false,
    val default: Boolean = false,
    val forced: Boolean = false,
    val channels: Int? = null,
    @SerialName("DOVIPresent") val dolbyVision: Boolean = false
)

@Serializable
internal data class MarkerWire(
    val type: String = "",
    @SerialName("startTimeOffset") val startTimeOffset: Long = 0,
    @SerialName("endTimeOffset") val endTimeOffset: Long = 0
)

@Serializable
internal data class ChapterWire(
    val tag: String? = null,
    @SerialName("startTimeOffset") val startTimeOffset: Long? = null
)

internal const val STREAM_VIDEO = 1
internal const val STREAM_AUDIO = 2
internal const val STREAM_SUBTITLE = 3

internal fun plexKind(type: String): ServerMediaKind? = when (type.trim().lowercase()) {
    "movie" -> ServerMediaKind.MOVIE
    "show" -> ServerMediaKind.SERIES
    else -> null
}

/** Plex's list type number for a library kind. */
internal fun plexTypeNumber(kind: ServerMediaKind): String? = when (kind) {
    ServerMediaKind.MOVIE -> "1"
    ServerMediaKind.SERIES -> "2"
    else -> null
}

/** Turns Plex Media Server answers into the app's titles; artwork links carry a mark the image loader signs. */
internal class PlexMapper(private val address: String, private val connectionId: String) {
    fun ref(ratingKey: String): String = ServerItemRef(connectionId, ratingKey).encode()

    fun artwork(path: String?, width: Int, height: Int): String? {
        val value = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return PlexArtworkLink.picture(address, connectionId, value, width, height)
    }

    /** Row art at the size Home draws it; a title's own page keeps the larger sizes. */
    private fun rowPoster(path: String?): String? {
        val width = DrawnArtworkWidths.current?.poster ?: POSTER_WIDTH
        return artwork(path, width, width * POSTER_HEIGHT / POSTER_WIDTH)
    }

    private fun rowLogo(path: String?): String? {
        val width = DrawnArtworkWidths.current?.logo ?: LOGO_WIDTH
        return artwork(path, width, width * LOGO_HEIGHT / LOGO_WIDTH)
    }

    fun preview(item: MetadataWire): MetaPreview? {
        val kind = plexKind(item.type) ?: return null
        if (item.ratingKey.isBlank()) return null
        return MetaPreview(
            id = ref(item.ratingKey),
            type = kind.domainType(),
            rawType = kind.contentType,
            name = item.title,
            poster = rowPoster(item.thumb),
            posterShape = PosterShape.POSTER,
            background = artwork(item.art, BACKDROP_WIDTH, BACKDROP_HEIGHT),
            logo = rowLogo(item.logo()),
            description = item.summary,
            releaseInfo = item.year?.toString(),
            imdbRating = item.audienceRating?.oneDecimal() ?: item.rating?.oneDecimal(),
            genres = item.genres.mapNotNull { it.tag },
            released = item.originallyAvailableAt
        )
    }

    fun title(item: MetadataWire): ServerTitle? = preview(item)?.let { ServerTitle(it, item.externalIds()) }

    fun details(item: MetadataWire, episodes: List<MetadataWire>): Meta {
        val kind = plexKind(item.type) ?: ServerMediaKind.MOVIE
        return Meta(
            id = ref(item.ratingKey),
            type = kind.domainType(),
            rawType = kind.contentType,
            name = item.title,
            poster = artwork(item.thumb, POSTER_WIDTH, POSTER_HEIGHT),
            posterShape = PosterShape.POSTER,
            background = artwork(item.art, BACKDROP_WIDTH, BACKDROP_HEIGHT),
            logo = artwork(item.logo(), LOGO_WIDTH, LOGO_HEIGHT),
            description = item.summary,
            releaseInfo = item.year?.toString(),
            status = null,
            imdbRating = item.audienceRating?.oneDecimal() ?: item.rating?.oneDecimal(),
            genres = item.genres.mapNotNull { it.tag },
            runtime = item.duration?.takeIf { it > 0 }?.let { "${(it / 60_000L).coerceAtLeast(1)} min" },
            director = item.directors.mapNotNull { it.tag },
            writer = item.writers.mapNotNull { it.tag },
            cast = item.roles.map { it.tag }.filter { it.isNotBlank() },
            castMembers = item.roles.filter { it.tag.isNotBlank() }.map {
                MetaCastMember(name = it.tag, character = it.role, photo = it.thumb?.takeIf { url -> url.startsWith("http") })
            },
            videos = episodes.mapNotNull(::video),
            ageRating = item.contentRating,
            country = item.countries.firstOrNull()?.tag,
            awards = null,
            language = null,
            links = emptyList(),
            imdbId = item.externalIds().imdb,
            released = item.originallyAvailableAt
        )
    }

    fun video(episode: MetadataWire): Video? {
        if (episode.ratingKey.isBlank()) return null
        return Video(
            id = ref(episode.ratingKey),
            title = episode.title,
            released = episode.originallyAvailableAt,
            thumbnail = artwork(episode.thumb, STILL_WIDTH, STILL_HEIGHT),
            season = episode.parentIndex,
            episode = episode.index,
            overview = episode.summary,
            runtime = episode.duration?.takeIf { it > 0 }?.let { (it / 60_000L).toInt().coerceAtLeast(1) },
            rating = null,
            available = true
        )
    }

    fun userState(item: MetadataWire): ServerUserState {
        val duration = item.duration ?: item.media.firstOrNull()?.duration ?: 0L
        return ServerUserState(
            videoId = ref(item.ratingKey),
            positionMs = item.viewOffset?.coerceAtLeast(0L) ?: 0L,
            durationMs = duration.coerceAtLeast(0L),
            played = (item.viewCount ?: 0) > 0 && (item.viewOffset ?: 0L) == 0L,
            lastPlayedEpochMs = item.lastViewedAt?.takeIf { it > 0 }?.times(1_000L),
            season = item.parentIndex.takeIf { item.type == "episode" },
            episode = item.index.takeIf { item.type == "episode" },
            title = item.title
        )
    }

    /** A Continue Watching card: an episode shows its show, a movie itself. */
    fun resumeTitle(item: MetadataWire): ServerTitle? {
        if (item.type != "episode") return title(item)
        val showKey = item.grandparentRatingKey?.takeIf { it.isNotBlank() } ?: return null
        val preview = MetaPreview(
            id = ref(showKey),
            type = ContentType.SERIES,
            rawType = ServerMediaKind.SERIES.contentType,
            name = item.grandparentTitle ?: item.title,
            poster = rowPoster(item.grandparentThumb ?: item.thumb),
            posterShape = PosterShape.POSTER,
            background = artwork(item.grandparentArt ?: item.art, BACKDROP_WIDTH, BACKDROP_HEIGHT),
            logo = null,
            description = item.summary,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList()
        )
        return ServerTitle(preview)
    }

    fun indexEntry(item: MetadataWire): ServerIndexEntry =
        ServerIndexEntry(itemId = item.ratingKey, ids = item.externalIds(), year = item.year)

    private fun MetadataWire.logo(): String? = images.firstOrNull { it.type == "clearLogo" }?.url

    private fun Double.oneDecimal(): Float = ((this * 10).roundToInt() / 10.0).toFloat()

    companion object {
        const val POSTER_WIDTH = 400
        const val POSTER_HEIGHT = 600
        const val BACKDROP_WIDTH = 1920
        const val BACKDROP_HEIGHT = 1080
        const val LOGO_WIDTH = 800
        const val LOGO_HEIGHT = 310
        const val STILL_WIDTH = 640
        const val STILL_HEIGHT = 360
    }
}

/**
 * imdb://tt0133093, tmdb://603 and tvdb://121361 from the item's Guid list (Plex sends them with includeGuids=1).
 * Libraries on the older agents have no such list, only the guid field: com.plexapp.agents.imdb://tt0133093?lang=en,
 * com.plexapp.agents.themoviedb://603, com.plexapp.agents.thetvdb://121361, or HAMA's anidb-23 / tvdb-81797 for anime.
 */
internal fun MetadataWire.externalIds(): TrackingExternalIds {
    fun id(scheme: String) = guids.firstNotNullOfOrNull { guid ->
        guid.id?.takeIf { it.startsWith("$scheme://", ignoreCase = true) }?.substringAfter("://")?.trim()?.takeIf { it.isNotEmpty() }
    }
    val fromList = TrackingExternalIds(
        imdb = id("imdb")?.lowercase()?.takeIf { IMDB_ID.matches(it) },
        tmdb = id("tmdb")?.let { LEADING_DIGITS.find(it)?.value?.toLongOrNull() },
        tvdb = id("tvdb")?.let { LEADING_DIGITS.find(it)?.value }
    )
    return fromList.mergeMissing(legacyGuidIds(guid))
}

internal fun legacyGuidIds(guid: String?): TrackingExternalIds {
    val match = LEGACY_GUID.find(guid?.trim().orEmpty()) ?: return TrackingExternalIds()
    val agent = match.groupValues[1].lowercase()
    val value = match.groupValues[2]
    fun digits(text: String) = LEADING_DIGITS.find(text)?.value
    return when (agent) {
        "imdb" -> TrackingExternalIds(imdb = value.lowercase().takeIf { IMDB_ID.matches(it) })
        "themoviedb", "tmdb" -> TrackingExternalIds(tmdb = digits(value)?.toLongOrNull())
        "thetvdb", "tvdb", "thetvdbdvdorder" -> TrackingExternalIds(tvdb = digits(value))
        "hama" -> {
            val source = value.substringBefore('-').lowercase()
            val id = value.substringAfter('-', "")
            when {
                source == "anidb" || source == "anidb2" -> TrackingExternalIds(anidb = digits(id)?.toLongOrNull())
                source.startsWith("tvdb") -> TrackingExternalIds(tvdb = digits(id))
                source == "tmdb" -> TrackingExternalIds(tmdb = digits(id)?.toLongOrNull())
                source == "imdb" -> TrackingExternalIds(imdb = id.lowercase().takeIf { IMDB_ID.matches(it) })
                else -> TrackingExternalIds()
            }
        }
        else -> TrackingExternalIds()
    }
}

private val LEGACY_GUID = Regex("""^com\.plexapp\.agents\.([a-z0-9]+)://([^/?]+)""", RegexOption.IGNORE_CASE)
private val IMDB_ID = Regex("""tt\d+""")
private val LEADING_DIGITS = Regex("""^\d+""")
