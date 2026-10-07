package com.nuvio.tv.data.iptv

import android.content.Context
import com.nuvio.tv.core.iptv.ArchiveAvailability
import com.nuvio.tv.core.iptv.CatchupWindow
import com.nuvio.tv.core.iptv.XtreamCatchupStyle
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
        nowMillis: Long, zone: TimeZone = TimeZone.getDefault()): String? =
        locators(kind, connection, item, startMillis, endMillis, nowMillis, zone).firstOrNull()?.url

    fun locators(kind: IptvSourceKind, connection: IptvSourceConnection?, item: IptvCatalogueItem, startMillis: Long, endMillis: Long,
        nowMillis: Long, zone: TimeZone = TimeZone.getDefault(), remembered: XtreamCatchupStyle? = null): List<IptvCatchupLocator> {
        if (!reaches(kind, item.attributes, startMillis, nowMillis) || endMillis <= startMillis) return emptyList()
        return when (kind) {
            IptvSourceKind.XTREAM -> {
                val account = connection ?: return emptyList()
                val stream = item.channel.data.locator.toHttpUrlOrNull()?.pathSegments?.lastOrNull()
                    ?.let { XTREAM_STREAM.matchEntire(it)?.groupValues?.get(1) } ?: return emptyList()
                val window = CatchupWindow(startMillis, endMillis, nowMillis, zone.getOffset(startMillis) / 60_000)
                XtreamCatchupStyle.order(remembered).map { style -> IptvCatchupLocator(xtreamUrl(style, account, stream, window), when (style) {
                    XtreamCatchupStyle.TIMESHIFT_HLS -> IptvStreamFormat.HLS
                    else -> IptvStreamFormat.MPEG_TS
                }, style) }
            }
            IptvSourceKind.M3U -> {
                val type = catchupType(item.attributes["catchup"], item.attributes["catchup-source"]) ?: return emptyList()
                val correction = item.attributes["catchup-correction"]?.trim()?.toDoubleOrNull()?.let { (it * 60).toInt() }?.coerceIn(-1440, 1440) ?: 0
                if (startMillis + correction * 60_000L >= nowMillis) return emptyList()
                val window = CatchupWindow(startMillis, endMillis, nowMillis, zone.getOffset(startMillis) / 60_000)
                val shifted = CatchupWindow(startMillis + correction * 60_000L, endMillis + correction * 60_000L, nowMillis, window.offsetMinutes)
                listOfNotNull(catchupUrl(type, item.channel.data.locator, item.attributes["catchup-source"], shifted)
                    ?.takeIf { it.toHttpUrlOrNull() != null }?.let { IptvCatchupLocator(it, null) })
            }
            IptvSourceKind.STALKER -> emptyList()
        }
    }

    fun xtreamUrl(style: XtreamCatchupStyle, account: IptvSourceConnection, stream: String, window: CatchupWindow): String {
        val (minutes, start) = xtreamTimeshiftPath(window)
        val base = IptvXtreamClient.serverBase(account).newBuilder()
        val username = requireNotNull(account.username)
        val password = requireNotNull(account.password)
        return when (style) {
            XtreamCatchupStyle.TIMESHIFT_TS, XtreamCatchupStyle.TIMESHIFT_HLS -> base.addPathSegment("timeshift").addPathSegment(username)
                .addPathSegment(password).addPathSegment(minutes.toString()).addPathSegment(start)
                .addPathSegment(stream + if (style == XtreamCatchupStyle.TIMESHIFT_HLS) ".m3u8" else ".ts")
            XtreamCatchupStyle.TIMESHIFT_PHP -> base.addPathSegment("streaming").addPathSegment("timeshift.php")
                .addQueryParameter("username", username).addQueryParameter("password", password).addQueryParameter("stream", stream)
                .addEncodedQueryParameter("start", start).addQueryParameter("duration", minutes.toString())
        }.build().toString()
    }
}

data class IptvCatchupLocator(val url: String, val format: IptvStreamFormat?, val style: XtreamCatchupStyle? = null) {
    override fun toString(): String = "IptvCatchupLocator(format=$format, style=$style)"
}

class IptvCatchupStyles(private val memory: IptvHostMemory) {
    fun remembered(connection: IptvSourceConnection): XtreamCatchupStyle? = XtreamCatchupStyle.parse(key(connection)?.let(memory::get))

    fun worked(connection: IptvSourceConnection, style: XtreamCatchupStyle?) {
        val next = XtreamCatchupStyle.worked(remembered(connection), style ?: return) ?: return
        key(connection)?.let { memory.put(it, next.name) }
    }

    private fun key(connection: IptvSourceConnection): String? = runCatching { IptvXtreamClient.serverBase(connection).toString() }.getOrNull()

    companion object {
        private var shared: IptvCatchupStyles? = null

        @Synchronized fun shared(context: Context): IptvCatchupStyles =
            shared ?: IptvCatchupStyles(IptvHostMemory("catchup-", PreferencesHostStore.of(context))).also { shared = it }
    }
}
