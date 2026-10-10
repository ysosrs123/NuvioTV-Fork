package com.nuvio.tv.core.iptv

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import org.json.JSONObject

sealed interface ExpiryStatus {
    data object None : ExpiryStatus
    data class Until(val date: String) : ExpiryStatus
    data class Soon(val days: Int) : ExpiryStatus
    data class Expired(val daysAgo: Int) : ExpiryStatus
}

data class SourceExpiry(val sourceId: String, val label: String, val expiresAtSeconds: Long)
data class ExpiryWarning(val sourceId: String, val label: String, val status: ExpiryStatus)

object SourceExpiries {
    const val NONE = 0L
    const val WARNING_DAYS = 7
    private const val LATEST = 253_402_300_799L
    private val DATE = DateTimeFormatter.ofPattern("dd-MMM-yy", Locale.ENGLISH)
    private val STALKER = Regex("""(\d{4})-(\d{2})-(\d{2})(?:[ T](\d{2}):(\d{2})(?::(\d{2}))?)?""")

    fun status(expiresAtSeconds: Long, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): ExpiryStatus {
        if (expiresAtSeconds <= NONE) return ExpiryStatus.None
        val expiry = Instant.ofEpochSecond(expiresAtSeconds.coerceAtMost(LATEST)).atZone(zone)
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(today, expiry.toLocalDate()).coerceIn(-100_000L, 100_000L).toInt()
        return when {
            expiresAtSeconds <= Math.floorDiv(nowMillis, 1000L) -> ExpiryStatus.Expired((-days).coerceAtLeast(0))
            days <= WARNING_DAYS -> ExpiryStatus.Soon(days.coerceAtLeast(0))
            else -> ExpiryStatus.Until(DATE.format(expiry))
        }
    }

    fun warns(status: ExpiryStatus): Boolean = status is ExpiryStatus.Soon || status is ExpiryStatus.Expired

    fun soonest(sources: List<SourceExpiry>, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): ExpiryWarning? =
        sources.filter { it.expiresAtSeconds > NONE }.sortedBy { it.expiresAtSeconds }.firstNotNullOfOrNull { source ->
            status(source.expiresAtSeconds, nowMillis, zone).takeIf(::warns)?.let { ExpiryWarning(source.sourceId, source.label, it) }
        }

    fun stalkerProfile(json: String, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val profile = runCatching { JSONObject(json).opt("js") as? JSONObject }.getOrNull() ?: return null
        val text = (profile.opt("expire_billing_date") as? String)?.trim()?.takeIf { it.length <= 32 } ?: return null
        val match = STALKER.matchEntire(text) ?: return null
        val parts = match.groupValues.drop(1).map { it.toIntOrNull() ?: 0 }
        if (parts[0] < 2000) return null
        val portal = (profile.opt("default_timezone") as? String)?.trim()?.takeIf { it.length in 1..64 }
            ?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: zone
        return runCatching {
            LocalDateTime.of(LocalDate.of(parts[0], parts[1], parts[2]), LocalTime.of(parts[3], parts[4], parts[5])).atZone(portal).toEpochSecond()
        }.getOrNull()
    }
}
