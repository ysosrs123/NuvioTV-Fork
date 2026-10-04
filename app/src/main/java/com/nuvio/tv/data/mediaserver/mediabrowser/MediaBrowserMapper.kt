package com.nuvio.tv.data.mediaserver.mediabrowser

import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.parseTrackingExternalIds
import com.nuvio.tv.data.mediaserver.ServerCandidate
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.data.mediaserver.ServerMediaKind
import com.nuvio.tv.data.mediaserver.ServerPlaybackTarget
import com.nuvio.tv.data.mediaserver.ServerTitle
import com.nuvio.tv.data.mediaserver.ServerUserState
import com.nuvio.tv.data.mediaserver.domainType
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaCastMember
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.Video
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.roundToInt

internal class MediaBrowserMapper(
    private val baseUrl: String,
    private val connectionId: String
) {
    fun ref(itemId: String): String = ServerItemRef(connectionId, itemId).encode()

    fun preview(item: BaseItem): MetaPreview? {
        val kind = item.mediaKind() ?: return null
        return MetaPreview(
            id = ref(item.id),
            type = kind.domainType(),
            rawType = kind.contentType,
            name = item.name.orEmpty(),
            poster = item.primaryImage(),
            posterShape = PosterShape.POSTER,
            background = item.backdropImage(),
            logo = item.logoImage(),
            description = item.overview,
            releaseInfo = item.productionYear?.toString(),
            imdbRating = item.communityRating?.formatRating(),
            genres = item.genres,
            released = item.premiereDate?.take(10)
        )
    }

    fun title(item: BaseItem): ServerTitle? = preview(item)?.let { ServerTitle(it, item.externalIds()) }

    fun resumeTitle(item: BaseItem): ServerTitle? {
        val preview = resumePreview(item) ?: return null
        val isEpisode = item.type.equals("Episode", ignoreCase = true)
        return ServerTitle(preview, if (isEpisode) TrackingExternalIds() else item.externalIds())
    }

    private fun resumePreview(item: BaseItem): MetaPreview? {
        if (!item.type.equals("Episode", ignoreCase = true)) return preview(item)
        val seriesId = item.seriesId ?: return null
        return MetaPreview(
            id = ref(seriesId),
            type = ContentType.SERIES,
            name = item.seriesName ?: item.name.orEmpty(),
            poster = item.seriesPrimaryImageTag?.let { image(seriesId, "Primary", it, maxHeight = 600) },
            posterShape = PosterShape.POSTER,
            background = item.backdropImage(),
            logo = item.logoImage(),
            description = item.overview,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList()
        )
    }

    fun details(item: BaseItem, episodes: List<BaseItem>): Meta {
        val kind = item.mediaKind() ?: ServerMediaKind.MOVIE
        val cast = item.people.filter { it.type.equals("Actor", ignoreCase = true) && !it.name.isNullOrBlank() }
        return Meta(
            id = ref(item.id),
            type = kind.domainType(),
            rawType = kind.contentType,
            name = item.name.orEmpty(),
            poster = item.primaryImage(),
            posterShape = PosterShape.POSTER,
            background = item.backdropImage(),
            logo = item.logoImage(),
            description = item.overview,
            releaseInfo = item.releaseInfo(kind),
            status = item.status,
            imdbRating = item.communityRating?.formatRating(),
            genres = item.genres,
            runtime = item.runTimeTicks?.let { "${ticksToMinutes(it)} min" },
            director = item.people.filter { it.type.equals("Director", ignoreCase = true) }.mapNotNull { it.name },
            writer = item.people.filter { it.type.equals("Writer", ignoreCase = true) }.mapNotNull { it.name },
            cast = cast.mapNotNull { it.name },
            castMembers = cast.map { person ->
                MetaCastMember(
                    name = person.name.orEmpty(),
                    character = person.role,
                    photo = person.id?.let { id -> person.primaryImageTag?.let { image(id, "Primary", it, maxHeight = 300) } }
                )
            },
            videos = episodes.map(::video),
            ageRating = item.officialRating,
            country = null,
            awards = null,
            language = null,
            links = emptyList(),
            imdbId = item.externalIds().imdb,
            released = item.premiereDate?.take(10)
        )
    }

    fun userState(item: BaseItem): ServerUserState? {
        val data = item.userData ?: return null
        return ServerUserState(
            videoId = ref(item.id),
            positionMs = (data.playbackPositionTicks ?: 0L) / TICKS_PER_MS,
            durationMs = (item.runTimeTicks ?: 0L) / TICKS_PER_MS,
            played = data.played,
            lastPlayedEpochMs = data.lastPlayedDate?.let(::serverDateEpochMs),
            season = item.parentIndexNumber,
            episode = item.indexNumber,
            title = item.name
        )
    }

    fun video(item: BaseItem): Video = Video(
        id = ref(item.id),
        title = item.name.orEmpty(),
        released = item.premiereDate,
        thumbnail = item.imageTags["Primary"]?.let { image(item.id, "Primary", it, maxWidth = 640) },
        season = item.parentIndexNumber,
        episode = item.indexNumber,
        overview = item.overview,
        runtime = item.runTimeTicks?.let(::ticksToMinutes),
        rating = item.communityRating,
        available = !item.isMissing
    )

    fun candidates(item: BaseItem): List<ServerCandidate> {
        if (item.isMissing) return emptyList()
        val itemRef = ServerItemRef(connectionId, item.id)
        return item.mediaSources.map { source ->
            val video = source.mediaStreams.firstOrNull { it.type.equals("Video", ignoreCase = true) }
            val filename = source.path?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() }
            ServerCandidate(
                target = ServerPlaybackTarget(item = itemRef, mediaSourceId = source.id),
                title = video?.let(::resolutionLabel) ?: source.name.orEmpty(),
                filename = filename,
                sizeBytes = source.size,
                bitrateBps = sourceBitrate(source, item.runTimeTicks),
                video = video?.let(::videoLabel),
                audio = source.defaultAudio()?.let(::audioLabel),
                container = containerLabel(source.container, filename),
                versionName = source.name?.trim()?.takeIf { it.isNotEmpty() }
            )
        }
    }

    fun image(itemId: String, type: String, tag: String, maxWidth: Int? = null, maxHeight: Int? = null): String =
        buildUrl(
            baseUrl,
            "/Items/${pathSegment(itemId)}/Images/$type",
            mapOf(
                "tag" to tag,
                "maxWidth" to maxWidth?.toString(),
                "maxHeight" to maxHeight?.toString(),
                "quality" to "90"
            )
        )

    private fun BaseItem.primaryImage(): String? =
        imageTags["Primary"]?.let { image(id, "Primary", it, maxHeight = 600) }

    private fun BaseItem.backdropImage(): String? =
        backdropImageTags.firstOrNull()?.let { image(id, "Backdrop/0", it, maxWidth = 1920) }
            ?: parentBackdropItemId?.let { parentId ->
                parentBackdropImageTags.firstOrNull()?.let { image(parentId, "Backdrop/0", it, maxWidth = 1920) }
            }

    private fun BaseItem.logoImage(): String? =
        imageTags["Logo"]?.let { image(id, "Logo", it, maxWidth = 800) }
            ?: parentLogoItemId?.let { parentId -> parentLogoImageTag?.let { image(parentId, "Logo", it, maxWidth = 800) } }

    private fun BaseItem.releaseInfo(kind: ServerMediaKind): String? {
        val start = productionYear ?: return null
        if (kind != ServerMediaKind.SERIES) return start.toString()
        val end = endDate?.take(4)?.toIntOrNull()
        return when {
            status.equals("Continuing", ignoreCase = true) -> "$start-"
            end != null && end != start -> "$start-$end"
            else -> start.toString()
        }
    }
}

internal fun BaseItem.mediaKind(): ServerMediaKind? = when {
    type.equals("Movie", ignoreCase = true) -> ServerMediaKind.MOVIE
    type.equals("Series", ignoreCase = true) -> ServerMediaKind.SERIES
    type.equals("BoxSet", ignoreCase = true) || type.equals("Folder", ignoreCase = true) -> ServerMediaKind.COLLECTION
    else -> null
}

internal fun BaseItem.externalIds(): TrackingExternalIds {
    val ids = providerIds.entries.fold(TrackingExternalIds()) { ids, (key, value) ->
        val id = value?.trim()?.takeIf { it.isNotEmpty() } ?: return@fold ids
        ids.mergeMissing(parseTrackingExternalIds("${providerNamespace(key)}:$id"))
    }
    return ids.copy(imdb = ids.imdb?.takeIf { it.startsWith("tt") })
}

private fun providerNamespace(key: String): String = when (val name = key.trim().lowercase()) {
    "myanimelist" -> "mal"
    else -> name
}

internal fun libraryKind(collectionType: String?): ServerMediaKind? = when (collectionType?.lowercase()) {
    "movies" -> ServerMediaKind.MOVIE
    "tvshows" -> ServerMediaKind.SERIES
    "boxsets" -> ServerMediaKind.COLLECTION
    else -> null
}

internal const val TICKS_PER_MS = 10_000L

private fun ticksToMinutes(ticks: Long): Int = (ticks / TICKS_PER_MS / 60_000L).toInt()

private fun Double.formatRating(): Float = ((this * 10).roundToInt() / 10.0).toFloat()

internal fun sourceBitrate(source: MediaSource, itemRunTimeTicks: Long? = null): Long? {
    source.bitrate?.takeIf(::isPlausibleBitrate)?.let { return it }
    val ticks = source.runTimeTicks ?: itemRunTimeTicks
    val size = source.size
    if (size != null && size > 0 && ticks != null && ticks > 0) {
        (size * 8.0 / (ticks / 10_000_000.0)).toLong().takeIf(::isPlausibleBitrate)?.let { return it }
    }
    val streams = source.mediaStreams.filter {
        it.type.equals("Video", ignoreCase = true) || it.type.equals("Audio", ignoreCase = true)
    }
    val video = streams.firstOrNull { it.type.equals("Video", ignoreCase = true) }?.bitRate ?: return null
    if (video <= 0) return null
    return streams.sumOf { (it.bitRate ?: 0L).coerceAtLeast(0L) }.takeIf(::isPlausibleBitrate)
}

internal fun formatBitrate(bps: Long): String = when {
    bps < 1_000_000L -> "${(bps / 1_000.0).roundToInt()} kbps"
    bps < 10_000_000L -> String.format(Locale.US, "%.1f Mbps", bps / 1_000_000.0)
    else -> "${(bps / 1_000_000.0).roundToInt()} Mbps"
}

private fun isPlausibleBitrate(bps: Long): Boolean = bps in MIN_BITRATE..MAX_BITRATE

private const val MIN_BITRATE = 50_000L
private const val MAX_BITRATE = 400_000_000L

internal fun resolutionLabel(stream: MediaStream): String? {
    val width = stream.width ?: 0
    val height = stream.height ?: 0
    return when {
        width >= 3200 || height >= 2000 -> "2160p"
        width >= 1800 || height >= 1000 -> "1080p"
        width >= 1200 || height >= 700 -> "720p"
        height >= 560 -> "576p"
        height >= 460 -> "480p"
        height > 0 -> "${height}p"
        else -> null
    }
}

private fun MediaSource.defaultAudio(): MediaStream? {
    val audio = mediaStreams.filter { it.type.equals("Audio", ignoreCase = true) }
    return audio.firstOrNull { defaultAudioStreamIndex != null && it.index == defaultAudioStreamIndex }
        ?: audio.firstOrNull { it.isDefault }
        ?: audio.firstOrNull()
}

internal fun videoLabel(stream: MediaStream): String? =
    listOfNotNull(videoCodecLabel(stream.codec), dynamicRangeLabel(stream)).joinToString(" • ").ifEmpty { null }

private fun videoCodecLabel(codec: String?): String? = when (val name = codec?.trim()?.lowercase()) {
    null, "" -> null
    "hevc", "h265" -> "HEVC"
    "h264", "avc" -> "AVC"
    "av1" -> "AV1"
    "vp9" -> "VP9"
    "vp8" -> "VP8"
    "vc1" -> "VC-1"
    "mpeg2video" -> "MPEG-2"
    "mpeg4" -> "MPEG-4"
    else -> name.uppercase()
}

private fun dynamicRangeLabel(stream: MediaStream): String? {
    val rangeType = stream.videoRangeType?.trim().orEmpty()
    val embyType = stream.extendedVideoType?.trim().orEmpty()
    val embyDv = DOVI_SUBTYPE.find(stream.extendedVideoSubType.orEmpty())
    val dolbyVision = when {
        rangeType.equals("DOVIInvalid", ignoreCase = true) -> false
        rangeType.startsWith("DOVI", ignoreCase = true) -> true
        embyType.equals("DolbyVision", ignoreCase = true) -> true
        else -> rangeType.isEmpty() && (stream.dvProfile ?: 0) > 0
    }
    if (!dolbyVision) {
        return rangeName(rangeType) ?: rangeName(embyType) ?: stream.videoRange?.let(::rangeName)
    }
    val profile = stream.dvProfile ?: embyDv?.groupValues?.get(1)?.toIntOrNull()
    val compatibility = stream.dvBlSignalCompatibilityId ?: embyDv?.groupValues?.get(2)?.toIntOrNull()
    val name = when {
        profile == null -> "DV"
        compatibility != null && compatibility > 0 -> "DV P$profile.$compatibility"
        else -> "DV P$profile"
    }
    val baseLayer = rangeName(rangeType.removePrefix("DOVIWith").removePrefix("DOVI").removePrefix("EL"))
        ?: when (compatibility) {
            1, 6 -> "HDR10"
            2 -> "SDR"
            4 -> "HLG"
            else -> null
        }
    return if (baseLayer != null) "$name ($baseLayer)" else name
}

private fun rangeName(value: String): String? = when (value.filter { it.isLetterOrDigit() || it == '+' }.lowercase()) {
    "hdr10plus", "hdr10+" -> "HDR10+"
    "hdr10" -> "HDR10"
    "hlg", "hyperloggamma" -> "HLG"
    "sdr" -> "SDR"
    "hdr" -> "HDR"
    else -> null
}

internal fun audioLabel(stream: MediaStream): String? {
    val codec = audioCodecLabel(stream)
    val atmos = listOfNotNull(stream.profile, stream.displayTitle).any { it.contains("Atmos", ignoreCase = true) }
    val name = when {
        codec == null -> "Atmos".takeIf { atmos }
        atmos -> "$codec Atmos"
        else -> codec
    }
    return listOfNotNull(name, channelLabel(stream)).joinToString(" • ").ifEmpty { null }
}

private fun audioCodecLabel(stream: MediaStream): String? = when (val codec = stream.codec?.trim()?.lowercase()) {
    null, "" -> null
    "truehd", "mlp" -> "TrueHD"
    "dts", "dca" -> dtsLabel(stream.profile.orEmpty())
    "eac3", "ec3" -> "EAC3"
    "ac3", "a52" -> "AC3"
    "aac" -> "AAC"
    "flac" -> "FLAC"
    "alac" -> "ALAC"
    "opus" -> "Opus"
    "vorbis" -> "Vorbis"
    "mp3" -> "MP3"
    "mp2" -> "MP2"
    else -> if (codec.startsWith("pcm")) "PCM" else codec.uppercase()
}

private fun dtsLabel(profile: String): String = when {
    profile.contains("DTS:X", ignoreCase = true) || profile.contains("DTS-X", ignoreCase = true) -> "DTS:X"
    profile.contains("HD MA", ignoreCase = true) || profile.contains("HD-MA", ignoreCase = true) ||
        profile.equals("MA", ignoreCase = true) -> "DTS-HD MA"
    profile.contains("HRA", ignoreCase = true) -> "DTS-HD HRA"
    profile.contains("Express", ignoreCase = true) -> "DTS Express"
    else -> "DTS"
}

private fun channelLabel(stream: MediaStream): String? {
    val layout = stream.channelLayout?.trim()?.lowercase().orEmpty()
    CHANNEL_LAYOUT.find(layout)?.let { return it.value }
    if (layout.startsWith("stereo")) return "2.0"
    if (layout == "mono") return "1.0"
    return when (val channels = stream.channels ?: 0) {
        0 -> null
        1 -> "1.0"
        2 -> "2.0"
        3 -> "2.1"
        6 -> "5.1"
        7 -> "6.1"
        8 -> "7.1"
        else -> if (channels > 0) "$channels.0" else null
    }
}

internal fun containerLabel(container: String?, filename: String?): String? {
    val names = container.orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    if (names.isEmpty()) return null
    val extension = filename?.substringAfterLast('.', "")?.lowercase()
    return (names.firstOrNull { it == extension } ?: names.first()).uppercase()
}

private val DOVI_SUBTYPE = Regex("""DoviProfile(\d)(\d)""", RegexOption.IGNORE_CASE)
private val CHANNEL_LAYOUT = Regex("""^\d+\.\d""")

/** Reads a server date with a zone ("Z" or an offset such as "+00:00") or without one, which counts as UTC. */
internal fun serverDateEpochMs(value: String): Long? =
    runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
        .recoverCatching { LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli() }
        .getOrNull()
