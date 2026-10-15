package com.nuvio.tv.ui.screens.calendar

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Immutable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** Dates and times in the app's language and the box's 12 or 24 hour choice. */
@Immutable
class CalendarFormats(
    val locale: Locale,
    timePattern: String,
    shortDatePattern: String,
    longDatePattern: String,
    yearDatePattern: String,
    monthPattern: String
) {
    private val time = DateTimeFormatter.ofPattern(timePattern, locale)
    private val shortDate = DateTimeFormatter.ofPattern(shortDatePattern, locale)
    private val longDate = DateTimeFormatter.ofPattern(longDatePattern, locale)
    private val yearDate = DateTimeFormatter.ofPattern(yearDatePattern, locale)
    private val month = DateTimeFormatter.ofPattern(monthPattern, locale)

    val firstDayOfWeek: DayOfWeek = WeekFields.of(locale).firstDayOfWeek

    fun time(value: LocalTime): String = time.format(value)
    fun shortDate(value: LocalDate): String = shortDate.format(value)
    fun longDate(value: LocalDate): String = longDate.format(value)
    fun yearDate(value: LocalDate): String = yearDate.format(value)
    fun month(value: YearMonth): String = month.format(value).replaceFirstChar { it.titlecase(locale) }
    fun weekday(day: DayOfWeek): String = day.getDisplayName(TextStyle.SHORT, locale)

    companion object {
        fun timeSkeleton(is24Hour: Boolean): String = if (is24Hour) "Hm" else "hm"

        /** Plain patterns for when the platform cannot pick the best one for the locale. */
        fun plain(locale: Locale, is24Hour: Boolean) = CalendarFormats(
            locale = locale,
            timePattern = if (is24Hour) "HH:mm" else "h:mm a",
            shortDatePattern = "EEE d MMM",
            longDatePattern = "EEEE d MMMM",
            yearDatePattern = "d MMM y",
            monthPattern = "LLLL y"
        )

        fun forDevice(context: Context, locale: Locale): CalendarFormats {
            val is24Hour = DateFormat.is24HourFormat(context)
            return runCatching {
                fun best(skeleton: String) = DateFormat.getBestDateTimePattern(locale, skeleton)
                CalendarFormats(
                    locale = locale,
                    timePattern = best(timeSkeleton(is24Hour)),
                    shortDatePattern = best("EEEdMMM"),
                    longDatePattern = best("EEEEdMMMM"),
                    yearDatePattern = best("dMMMy"),
                    monthPattern = best("LLLLy")
                )
            }.getOrElse { plain(locale, is24Hour) }
        }
    }
}

/** The place and the zone's short name, for "Times in Sydney (AEDT)". [city] is null for zones without a place. */
data class CalendarZoneName(val city: String?, val short: String)

fun calendarZoneName(zone: ZoneId, now: Instant, locale: Locale, exemplarCity: (String) -> String? = { null }): CalendarZoneName {
    val id = zone.id
    val short = runCatching { DateTimeFormatter.ofPattern("zzz", locale).format(now.atZone(zone)) }.getOrNull()
        ?.takeIf(String::isNotBlank)
        ?: zone.rules.getOffset(now).let { if (it == ZoneOffset.UTC) "UTC" else "GMT${it.id}" }
    val city = if (zone is ZoneOffset || '/' !in id || id.startsWith("Etc/")) null
    else exemplarCity(id)?.takeIf(String::isNotBlank) ?: id.substringAfterLast('/').replace('_', ' ')
    return CalendarZoneName(city?.takeIf { it != short }, short)
}

fun androidExemplarCity(locale: Locale): (String) -> String? = { id ->
    runCatching { android.icu.text.TimeZoneNames.getInstance(locale).getExemplarLocationName(id) }.getOrNull()
}

/** Episode runtime from the "%1$dh %2$dm", "%1$dh" and "%1$dm" patterns of the app's language. */
fun calendarRuntime(minutes: Int?, hoursMinutes: String, hoursOnly: String, minutesOnly: String): String? {
    val total = minutes?.takeIf { it > 0 } ?: return null
    val hours = total / 60
    val rest = total % 60
    return when {
        hours > 0 && rest > 0 -> hoursMinutes.format(hours, rest)
        hours > 0 -> hoursOnly.format(hours)
        else -> minutesOnly.format(rest)
    }
}

/** "A, B and C" in the app's language; [join] is the plain fallback for boxes without ICU list patterns. */
fun calendarNameList(names: List<String>, locale: java.util.Locale, join: (List<String>) -> String): String {
    if (names.size <= 1) return names.firstOrNull().orEmpty()
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        runCatching { android.icu.text.ListFormatter.getInstance(locale).format(names) }.getOrNull()
            ?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return join(names)
}

/** "A, B and C" from a separator and a "%1$s and %2$s" pattern. */
fun calendarJoinPlain(names: List<String>, separator: String, lastPair: String): String = when (names.size) {
    0 -> ""
    1 -> names.single()
    else -> lastPair.format(names.dropLast(1).joinToString(separator), names.last())
}

private val networkAbbreviations = setOf("inc", "co", "corp", "ltd", "bros", "jr", "sr", "st", "intl", "ent")

/** "Life Style." as "Life Style"; a closing abbreviation such as "Inc." or "U.S." keeps its full stop. */
fun calendarNetworkName(name: String?): String? {
    val trimmed = name?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (!trimmed.endsWith('.') || trimmed.endsWith("..")) return trimmed
    val lastWord = trimmed.substringAfterLast(' ').dropLast(1)
    if ('.' in lastWord || lastWord.lowercase() in networkAbbreviations) return trimmed
    return trimmed.dropLast(1).trimEnd().takeIf(String::isNotEmpty)
}
