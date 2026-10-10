package com.nuvio.tv.core.iptv

import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

enum class LiveWidgetKind { CLOCKS, SPORT, UP_NEXT, RECORDINGS, STREAM, EMPTY }

enum class LiveWidgetShape { NARROW, WIDE, SQUARE }

data class LiveWidgetPart(val shape: LiveWidgetShape, val tiles: Int = 1)

enum class LiveWidgetLayout(vararg val parts: LiveWidgetPart) {
    TALL(LiveWidgetPart(LiveWidgetShape.NARROW)),
    SQUARE(LiveWidgetPart(LiveWidgetShape.SQUARE)),
    ONE(LiveWidgetPart(LiveWidgetShape.WIDE)),
    STACKED(LiveWidgetPart(LiveWidgetShape.WIDE, 2)),
    TWO(LiveWidgetPart(LiveWidgetShape.NARROW), LiveWidgetPart(LiveWidgetShape.NARROW)),
    THREE(LiveWidgetPart(LiveWidgetShape.NARROW), LiveWidgetPart(LiveWidgetShape.NARROW, 2)),
    SQUARES(LiveWidgetPart(LiveWidgetShape.SQUARE), LiveWidgetPart(LiveWidgetShape.SQUARE, 2)),
    FOUR(LiveWidgetPart(LiveWidgetShape.SQUARE, 2), LiveWidgetPart(LiveWidgetShape.SQUARE, 2));

    val slots: Int get() = parts.sumOf { it.tiles }
}

data class LiveWidgetColumn(val width: Int, val slots: List<Int>, val tileHeight: Int)

data class LiveStreamFacts(
    val width: Int = 0, val height: Int = 0, val frameRate: Float = 0f, val videoMime: String? = null, val videoCodecs: String? = null,
    val transfer: Int = 0, val videoBitrate: Int = 0, val audioMime: String? = null, val channels: Int = 0, val language: String? = null,
    val container: String? = null, val bufferMs: Long = -1, val dropped: Int = -1,
)

data class WidgetCity(val name: String, val zone: String, val listed: Boolean = true) {
    val id: String get() = if (listed) name else zone
}

data class WidgetClock(val city: WidgetCity, val time: String, val dayOffset: Int, val day: Boolean)

object LiveWidgets {
    private val MARKS = Regex("\\p{Mn}+")
    const val GAP = 12
    const val SPACING = 20
    const val SLOT_MIN = 140
    const val SQUARE_MIN = 100
    const val SHORT_MIN = 90
    const val INFO_MIN = 380
    const val MAX_CITIES = 4
    const val MAX_SLOTS = 4
    const val WIDE = 200
    const val NARROW_WIDTH = 220
    const val WIDE_WIDTH = 360
    val DEFAULT_LAYOUT = LiveWidgetLayout.TWO
    val DEFAULT_KINDS = listOf(LiveWidgetKind.CLOCKS, LiveWidgetKind.UP_NEXT, LiveWidgetKind.RECORDINGS, LiveWidgetKind.STREAM)
    private val FALLBACKS = listOf("London", "New York", "Tokyo", "Sydney")

    fun tileHeight(height: Int, tiles: Int): Int = (height - GAP * (tiles - 1)) / tiles

    fun columns(width: Int, height: Int, layout: LiveWidgetLayout): List<LiveWidgetColumn> {
        val room = width - INFO_MIN - SPACING
        if (height <= 0) return emptyList()
        var first = 0
        val planned = layout.parts.map { part ->
            val tiles = if (part.tiles > 1 && tileHeight(height, part.tiles) >= SHORT_MIN) part.tiles else 1
            val tile = tileHeight(height, tiles)
            val (preferred, least) = when (part.shape) {
                LiveWidgetShape.NARROW -> NARROW_WIDTH to SLOT_MIN
                LiveWidgetShape.WIDE -> WIDE_WIDTH to SLOT_MIN
                LiveWidgetShape.SQUARE -> tile to minOf(tile, SLOT_MIN).coerceAtLeast(SQUARE_MIN)
            }
            Triple(List(tiles) { first + it }, tile, preferred.coerceAtLeast(least) to least).also { first += part.tiles }
        }
        for (count in planned.size downTo 1) {
            val parts = planned.take(count)
            val space = room - GAP * (count - 1)
            val preferred = parts.sumOf { it.third.first }
            val least = parts.sumOf { it.third.second }
            if (least > space) continue
            val give = preferred - least
            val extra = minOf(space - least, give)
            return parts.map { (slots, tile, size) ->
                val width = if (give == 0) size.first else size.second + ((size.first - size.second).toLong() * extra / give).toInt()
                LiveWidgetColumn(width, slots, tile)
            }
        }
        return emptyList()
    }

    fun fits(width: Int, height: Int, layout: LiveWidgetLayout): Boolean = columns(width, height, layout).sumOf { it.slots.size } == layout.slots

    fun kinds(saved: List<String?>): List<LiveWidgetKind> = List(MAX_SLOTS) { index ->
        saved.getOrNull(index)?.let { name -> LiveWidgetKind.entries.firstOrNull { it.name == name } } ?: DEFAULT_KINDS[index]
    }

    val cities: List<WidgetCity> = listOf(
        "Abu Dhabi" to "Asia/Dubai", "Accra" to "Africa/Accra", "Adelaide" to "Australia/Adelaide", "Amsterdam" to "Europe/Amsterdam",
        "Anchorage" to "America/Anchorage", "Athens" to "Europe/Athens", "Atlanta" to "America/New_York", "Auckland" to "Pacific/Auckland",
        "Bangkok" to "Asia/Bangkok", "Barcelona" to "Europe/Madrid", "Beijing" to "Asia/Shanghai", "Berlin" to "Europe/Berlin",
        "Bogotá" to "America/Bogota", "Boston" to "America/New_York", "Brisbane" to "Australia/Brisbane", "Brussels" to "Europe/Brussels",
        "Bucharest" to "Europe/Bucharest", "Budapest" to "Europe/Budapest", "Buenos Aires" to "America/Argentina/Buenos_Aires",
        "Cairo" to "Africa/Cairo", "Canberra" to "Australia/Sydney", "Cape Town" to "Africa/Johannesburg", "Caracas" to "America/Caracas",
        "Casablanca" to "Africa/Casablanca", "Chicago" to "America/Chicago", "Colombo" to "Asia/Colombo", "Copenhagen" to "Europe/Copenhagen",
        "Dallas" to "America/Chicago", "Darwin" to "Australia/Darwin", "Delhi" to "Asia/Kolkata", "Denver" to "America/Denver",
        "Dhaka" to "Asia/Dhaka", "Doha" to "Asia/Qatar", "Dubai" to "Asia/Dubai", "Dublin" to "Europe/Dublin", "Halifax" to "America/Halifax",
        "Hanoi" to "Asia/Bangkok", "Havana" to "America/Havana", "Helsinki" to "Europe/Helsinki", "Hobart" to "Australia/Hobart",
        "Ho Chi Minh City" to "Asia/Ho_Chi_Minh", "Hong Kong" to "Asia/Hong_Kong", "Honolulu" to "Pacific/Honolulu", "Houston" to "America/Chicago",
        "Istanbul" to "Europe/Istanbul", "Jakarta" to "Asia/Jakarta", "Jerusalem" to "Asia/Jerusalem", "Johannesburg" to "Africa/Johannesburg",
        "Karachi" to "Asia/Karachi", "Kathmandu" to "Asia/Kathmandu", "Kolkata" to "Asia/Kolkata", "Kuala Lumpur" to "Asia/Kuala_Lumpur",
        "Kyiv" to "Europe/Kiev", "Lagos" to "Africa/Lagos", "Las Vegas" to "America/Los_Angeles", "Lima" to "America/Lima",
        "Lisbon" to "Europe/Lisbon", "London" to "Europe/London", "Los Angeles" to "America/Los_Angeles", "Madrid" to "Europe/Madrid",
        "Manila" to "Asia/Manila", "Melbourne" to "Australia/Melbourne", "Mexico City" to "America/Mexico_City", "Miami" to "America/New_York",
        "Milan" to "Europe/Rome", "Montreal" to "America/Toronto", "Moscow" to "Europe/Moscow", "Mumbai" to "Asia/Kolkata",
        "Munich" to "Europe/Berlin", "Nairobi" to "Africa/Nairobi", "New York" to "America/New_York", "Oslo" to "Europe/Oslo",
        "Panama City" to "America/Panama", "Paris" to "Europe/Paris", "Perth" to "Australia/Perth", "Phoenix" to "America/Phoenix",
        "Prague" to "Europe/Prague", "Reykjavík" to "Atlantic/Reykjavik", "Rio de Janeiro" to "America/Sao_Paulo", "Riyadh" to "Asia/Riyadh",
        "Rome" to "Europe/Rome", "San Francisco" to "America/Los_Angeles", "Santiago" to "America/Santiago", "São Paulo" to "America/Sao_Paulo",
        "Seattle" to "America/Los_Angeles", "Seoul" to "Asia/Seoul", "Shanghai" to "Asia/Shanghai", "Singapore" to "Asia/Singapore",
        "Stockholm" to "Europe/Stockholm", "Suva" to "Pacific/Fiji", "Sydney" to "Australia/Sydney", "Taipei" to "Asia/Taipei",
        "Tehran" to "Asia/Tehran", "Tokyo" to "Asia/Tokyo", "Toronto" to "America/Toronto", "Vancouver" to "America/Vancouver",
        "Vienna" to "Europe/Vienna", "Warsaw" to "Europe/Warsaw", "Washington" to "America/New_York", "Wellington" to "Pacific/Auckland",
        "Zurich" to "Europe/Zurich",
    ).map { (name, zone) -> WidgetCity(name, zone) }.sortedBy { fold(it.name) }

    fun city(id: String?): WidgetCity? {
        if (id.isNullOrBlank() || id.length > 64) return null
        cities.firstOrNull { it.name == id }?.let { return it }
        if ('/' !in id) return null
        val zone = runCatching { ZoneId.of(id) }.getOrNull() ?: return null
        return WidgetCity(zoneName(zone.id), zone.id, listed = false)
    }

    fun deviceCity(zone: String): WidgetCity? =
        cities.firstOrNull { it.zone == zone && it.name == zoneName(zone) } ?: cities.firstOrNull { it.zone == zone } ?: city(zone)

    fun zoneName(zone: String): String = zone.substringAfterLast('/').replace('_', ' ')

    fun defaultCities(deviceZone: String): List<String> {
        val device = deviceCity(deviceZone)
        val picked = listOfNotNull(device).toMutableList()
        for (name in FALLBACKS) {
            if (picked.size >= 3) break
            val city = city(name) ?: continue
            if (picked.none { it.zone == city.zone }) picked += city
        }
        return picked.map { it.id }
    }

    fun savedCities(saved: List<String>?, deviceZone: String): List<String> =
        (saved ?: defaultCities(deviceZone)).filter { city(it) != null }.distinct().take(MAX_CITIES)

    fun search(query: String, exclude: Collection<String> = emptyList()): List<WidgetCity> {
        val wanted = fold(query.trim())
        val left = cities.filter { it.id !in exclude }
        if (wanted.isEmpty()) return left
        val (starts, contains) = left.filter { fold(it.name).contains(wanted) }.partition { fold(it.name).startsWith(wanted) }
        return starts + contains
    }

    fun dayOffset(now: Instant, zone: ZoneId, home: ZoneId): Int =
        ChronoUnit.DAYS.between(now.atZone(home).toLocalDate(), now.atZone(zone).toLocalDate()).toInt()

    fun daytime(now: Instant, zone: ZoneId): Boolean = now.atZone(zone).hour in 6..17

    fun time(now: Instant, zone: ZoneId): String = now.atZone(zone).let { String.format(Locale.ROOT, "%02d:%02d", it.hour, it.minute) }

    fun clocks(ids: List<String>, now: Long, home: String): List<WidgetClock> {
        val instant = Instant.ofEpochMilli(now)
        val homeZone = runCatching { ZoneId.of(home) }.getOrDefault(ZoneId.of("UTC"))
        return ids.mapNotNull(::city).map { city ->
            val zone = ZoneId.of(city.zone)
            WidgetClock(city, time(instant, zone), dayOffset(instant, zone, homeZone), daytime(instant, zone))
        }
    }

    fun nextMinute(now: Long): Long = (Math.floorDiv(now, 60_000L) + 1) * 60_000L - now

    fun bitrate(bitsPerSecond: Int): String? = when {
        bitsPerSecond <= 0 -> null
        bitsPerSecond >= 1_000_000 -> String.format(Locale.ROOT, "%.1f Mb/s", bitsPerSecond / 1_000_000.0)
        else -> "${(bitsPerSecond + 500) / 1000} kb/s"
    }

    fun resolution(width: Int, height: Int): String? = if (width > 0 && height > 0) "$width×$height" else null

    fun codec(mime: String?): String? = when (mime) {
        "video/avc" -> "H.264"
        "video/hevc" -> "HEVC"
        "video/av01" -> "AV1"
        "video/mpeg2" -> "MPEG-2"
        "video/mp4v-es" -> "MPEG-4"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/dolby-vision" -> "Dolby Vision"
        else -> null
    }

    fun dolbyVision(mime: String?, codecs: String?): Boolean =
        mime == "video/dolby-vision" || codecs?.lowercase(Locale.ROOT)?.let { it.startsWith("dvh") || it.startsWith("dva") || it.startsWith("dav1") } == true

    fun range(mime: String?, codecs: String?, transfer: Int): String = when {
        dolbyVision(mime, codecs) -> "Dolby Vision"
        transfer == TRANSFER_PQ -> "HDR10"
        transfer == TRANSFER_HLG -> "HLG"
        else -> "SDR"
    }

    fun frameRate(fps: Float): String? {
        if (fps <= 0f || fps.isNaN() || fps > 1000f) return null
        val whole = kotlin.math.round(fps)
        return if (kotlin.math.abs(fps - whole) < 0.05f) "${whole.toInt()} fps" else String.format(Locale.ROOT, "%.2f fps", fps)
    }

    fun quality(width: Int, height: Int): String? = when {
        width <= 0 || height <= 0 -> null
        height >= 2000 || width >= 3800 -> "4K"
        height >= 1400 || width >= 2500 -> "1440p"
        height >= 1000 || width >= 1900 -> "1080p"
        height >= 700 || width >= 1260 -> "720p"
        else -> "SD"
    }

    fun audioCodec(mime: String?): String? = when (mime) {
        null -> null
        "audio/mp4a-latm" -> "AAC"
        "audio/ac3" -> "Dolby Digital"
        "audio/eac3" -> "Dolby Digital Plus"
        "audio/eac3-joc" -> "Dolby Atmos"
        "audio/ac4" -> "AC-4"
        "audio/mpeg" -> "MP3"
        "audio/mpeg-L2" -> "MPEG audio"
        "audio/opus" -> "Opus"
        "audio/vnd.dts", "audio/vnd.dts.hd", "audio/vnd.dts.uhd;profile=p2" -> "DTS"
        "audio/true-hd" -> "Dolby TrueHD"
        "audio/flac" -> "FLAC"
        else -> mime.substringAfter('/').uppercase(Locale.ROOT).takeIf { it.isNotBlank() && it.length <= 12 }
    }

    fun channels(count: Int): String? = when {
        count <= 0 -> null
        count == 1 -> "1.0"
        count == 2 -> "2.0"
        count == 6 -> "5.1"
        count == 8 -> "7.1"
        else -> "$count ch"
    }

    fun container(mime: String?): String? = when (mime?.lowercase(Locale.ROOT)) {
        null -> null
        "application/x-mpegurl", "application/vnd.apple.mpegurl" -> "HLS"
        "video/mp2t" -> "MPEG-TS"
        "application/dash+xml" -> "DASH"
        "video/mp4", "audio/mp4" -> "MP4"
        "video/x-matroska", "video/webm" -> "MKV"
        else -> null
    }

    fun buffer(ms: Long): String? = if (ms < 0) null else String.format(Locale.ROOT, "%.1f s", ms / 1000.0)

    fun bufferHealth(ms: Long): Int = when {
        ms < 0 -> 0
        ms >= 6_000 -> 2
        ms >= 1_500 -> 1
        else -> -1
    }

    fun badges(facts: LiveStreamFacts): List<String> = listOfNotNull(
        quality(facts.width, facts.height),
        range(facts.videoMime, facts.videoCodecs, facts.transfer).takeIf { it != "SDR" }?.let { if (it == "Dolby Vision") "DV" else it },
        facts.frameRate.takeIf { it >= 48f }?.let { "${kotlin.math.round(it).toInt()}p" },
        "Atmos".takeIf { facts.audioMime == "audio/eac3-joc" },
        channels(facts.channels)?.takeIf { facts.channels > 2 },
    )

    const val TRANSFER_PQ = 6
    const val TRANSFER_HLG = 7

    fun fold(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD).replace(MARKS, "").lowercase(Locale.ROOT)
}
