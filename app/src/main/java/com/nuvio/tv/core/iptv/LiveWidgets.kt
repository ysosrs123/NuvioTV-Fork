package com.nuvio.tv.core.iptv

import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

enum class LiveWidgetKind { CLOCKS, SPORT, UP_NEXT, RECORDINGS, STREAM, EMPTY }

enum class LiveWidgetLayout(val slots: Int) { ONE(1), TWO(2), THREE(3) }

data class WidgetCity(val name: String, val zone: String, val listed: Boolean = true) {
    val id: String get() = if (listed) name else zone
}

data class WidgetClock(val city: WidgetCity, val time: String, val dayOffset: Int, val day: Boolean)

object LiveWidgets {
    private val MARKS = Regex("\\p{Mn}+")
    const val GAP = 12
    const val SPACING = 20
    const val SLOT_MIN = 140
    const val INFO_MIN = 380
    const val MAX_CITIES = 4
    const val WIDE = 200
    val DEFAULT_LAYOUT = LiveWidgetLayout.TWO
    val DEFAULT_KINDS = listOf(LiveWidgetKind.CLOCKS, LiveWidgetKind.UP_NEXT, LiveWidgetKind.RECORDINGS)
    private val FALLBACKS = listOf("London", "New York", "Tokyo", "Sydney")

    fun preferred(slots: Int): Int = when (slots) { 1 -> 360; 2 -> 240; else -> 200 }

    fun slots(width: Int, layout: LiveWidgetLayout): List<Int> {
        val room = width - INFO_MIN - SPACING
        for (count in layout.slots downTo 1) {
            val fit = (room - GAP * (count - 1)) / count
            if (fit >= SLOT_MIN) return List(count) { minOf(fit, preferred(count)) }
        }
        return emptyList()
    }

    fun kinds(saved: List<String?>): List<LiveWidgetKind> = List(LiveWidgetLayout.THREE.slots) { index ->
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
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/dolby-vision" -> "Dolby Vision"
        else -> null
    }

    fun fold(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD).replace(MARKS, "").lowercase(Locale.ROOT)
}
