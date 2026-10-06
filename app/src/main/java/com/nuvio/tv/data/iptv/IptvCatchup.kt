package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.ArchiveAvailability
import com.nuvio.tv.core.iptv.CatchupWindow
import com.nuvio.tv.core.iptv.catchupType
import com.nuvio.tv.core.iptv.catchupUrl
import com.nuvio.tv.core.iptv.xtreamTimeshiftPath
import java.util.TimeZone
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object IptvCatchup {
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    private val XTREAM_STREAM = Regex("([0-9]+)\\.[A-Za-z0-9]{1,8}")

    fun supported(kind: IptvSourceKind, attributes: Map<String, String>): Boolean = when (kind) {
        IptvSourceKind.XTREAM -> attributes["archive-availability"] == ArchiveAvailability.ADVERTISED.name
        IptvSourceKind.M3U -> catchupType(attributes["catchup"], attributes["catchup-source"]) != null
        IptvSourceKind.STALKER -> false
    }

    fun reaches(kind: IptvSourceKind, attributes: Map<String, String>, startMillis: Long, nowMillis: Long): Boolean {
        if (!supported(kind, attributes) || startMillis >= nowMillis) return false
        val days = (if (kind == IptvSourceKind.XTREAM) attributes["archive-days"] else attributes["catchup-days"])?.trim()?.toIntOrNull()
        return days == null || days <= 0 || nowMillis - startMillis <= days * DAY_MILLIS
    }

    fun locator(kind: IptvSourceKind, connection: IptvSourceConnection?, item: IptvCatalogueItem, startMillis: Long, endMillis: Long,
        nowMillis: Long, zone: TimeZone = TimeZone.getDefault()): String? {
        if (!reaches(kind, item.attributes, startMillis, nowMillis) || endMillis <= startMillis) return null
        return when (kind) {
            IptvSourceKind.XTREAM -> {
                val account = connection ?: return null
                val stream = item.channel.data.locator.toHttpUrlOrNull()?.pathSegments?.lastOrNull()
                    ?.let { XTREAM_STREAM.matchEntire(it)?.groupValues?.get(1) } ?: return null
                val window = CatchupWindow(startMillis, endMillis, nowMillis, zone.getOffset(startMillis) / 60_000)
                val (minutes, start) = xtreamTimeshiftPath(window)
                IptvXtreamClient.serverBase(account).newBuilder().addPathSegment("timeshift")
                    .addPathSegment(requireNotNull(account.username)).addPathSegment(requireNotNull(account.password))
                    .addPathSegment(minutes.toString()).addPathSegment(start).addPathSegment("$stream.ts").build().toString()
            }
            IptvSourceKind.M3U -> {
                val type = catchupType(item.attributes["catchup"], item.attributes["catchup-source"]) ?: return null
                val correction = item.attributes["catchup-correction"]?.trim()?.toDoubleOrNull()?.let { (it * 60).toInt() }?.coerceIn(-1440, 1440) ?: 0
                if (startMillis + correction * 60_000L >= nowMillis) return null
                val window = CatchupWindow(startMillis, endMillis, nowMillis, zone.getOffset(startMillis) / 60_000)
                val shifted = CatchupWindow(startMillis + correction * 60_000L, endMillis + correction * 60_000L, nowMillis, window.offsetMinutes)
                catchupUrl(type, item.channel.data.locator, item.attributes["catchup-source"], shifted)
                    ?.takeIf { it.toHttpUrlOrNull() != null }
            }
            IptvSourceKind.STALKER -> null
        }
    }
}
