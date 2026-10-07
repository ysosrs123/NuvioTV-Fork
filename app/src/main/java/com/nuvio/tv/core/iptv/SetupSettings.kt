package com.nuvio.tv.core.iptv

import java.util.Locale
import org.json.JSONObject

enum class SetupSetting(val wire: String) {
    FORMAT("format"), TIMESHIFT("timeshift"), SPORT("sport"), START_VIEW("startView"),
    LAYOUT("layout"), QUALITY("quality"), RECORD_EARLY("recordEarly"), RECORD_LATE("recordLate");
    companion object { fun of(wire: String?): SetupSetting? = entries.firstOrNull { it.wire == wire } }
}

data class SetupSettings(
    val format: String = "auto",
    val timeshift: Boolean = true,
    val sport: Boolean = true,
    val startView: String = "last",
    val layout: String = "grid",
    val quality: String = "auto",
    val recordEarly: Int = 1,
    val recordLate: Int = 2,
    val multiview: Boolean = true,
) {
    fun toJson(): String = JSONObject().put("format", format).put("timeshift", timeshift).put("sport", sport).put("startView", startView)
        .put("layout", layout).put("quality", quality).put("recordEarly", recordEarly).put("recordLate", recordLate).put("multiview", multiview).toString()

    companion object {
        val FORMATS = listOf("auto", "hls", "mpegts")
        val START_VIEWS = listOf("last", "all", "favourites", "sport")
        val LAYOUTS = listOf("grid", "focus")
        val QUALITIES = listOf("auto", "sharpest", "lightest")
        val EARLY_MINUTES = listOf(0, 1, 2, 5, 10)
        val LATE_MINUTES = listOf(0, 2, 5, 10, 15, 30)

        fun wire(value: Enum<*>): String = value.name.lowercase(Locale.ROOT).replace("_", "")
        fun <T : Enum<T>> choice(entries: List<T>, wire: String): T = entries.first { wire(it) == wire }
    }
}

class SetupSettingsChange(
    val format: String? = null,
    val timeshift: Boolean? = null,
    val sport: Boolean? = null,
    val startView: String? = null,
    val layout: String? = null,
    val quality: String? = null,
    val recordEarly: Int? = null,
    val recordLate: Int? = null,
) : SetupChange {
    override fun toString() = "SetupSettingsChange(values withheld)"

    fun applied(current: SetupSettings): SetupSettings = current.copy(format = format ?: current.format, timeshift = timeshift ?: current.timeshift,
        sport = sport ?: current.sport, startView = startView ?: current.startView, layout = layout ?: current.layout, quality = quality ?: current.quality,
        recordEarly = recordEarly ?: current.recordEarly, recordLate = recordLate ?: current.recordLate)

    fun changes(current: SetupSettings): List<SetupSetting> {
        val next = applied(current)
        return buildList {
            if (next.format != current.format) add(SetupSetting.FORMAT)
            if (next.timeshift != current.timeshift) add(SetupSetting.TIMESHIFT)
            if (next.sport != current.sport) add(SetupSetting.SPORT)
            if (next.startView != current.startView) add(SetupSetting.START_VIEW)
            if (next.layout != current.layout) add(SetupSetting.LAYOUT)
            if (next.quality != current.quality) add(SetupSetting.QUALITY)
            if (next.recordEarly != current.recordEarly) add(SetupSetting.RECORD_EARLY)
            if (next.recordLate != current.recordLate) add(SetupSetting.RECORD_LATE)
        }
    }
}

object SetupSettingsInput {
    fun parse(body: String): SetupSettingsChange {
        if (body.length > SetupDrafts.MAX_BODY_BYTES || !SetupDrafts.shallowJson(body)) throw SetupInputException("body")
        val json = try { JSONObject(body) } catch (_: Exception) { throw SetupInputException("body") }
        val keys = json.keys().asSequence().toList()
        if (keys.size > SetupSetting.entries.size || keys.any { SetupSetting.of(it) == null }) throw SetupInputException("body")
        return SetupSettingsChange(
            format = choice(json, SetupSetting.FORMAT, SetupSettings.FORMATS),
            timeshift = flag(json, SetupSetting.TIMESHIFT),
            sport = flag(json, SetupSetting.SPORT),
            startView = choice(json, SetupSetting.START_VIEW, SetupSettings.START_VIEWS),
            layout = choice(json, SetupSetting.LAYOUT, SetupSettings.LAYOUTS),
            quality = choice(json, SetupSetting.QUALITY, SetupSettings.QUALITIES),
            recordEarly = minutes(json, SetupSetting.RECORD_EARLY, SetupSettings.EARLY_MINUTES),
            recordLate = minutes(json, SetupSetting.RECORD_LATE, SetupSettings.LATE_MINUTES),
        )
    }

    fun check(change: SetupSettingsChange, current: SetupSettings): String? {
        val next = change.applied(current)
        return when {
            change.startView == "sport" && !next.sport -> SetupSetting.START_VIEW.wire
            !current.multiview && change.layout != null && change.layout != current.layout -> SetupSetting.LAYOUT.wire
            !current.multiview && change.quality != null && change.quality != current.quality -> SetupSetting.QUALITY.wire
            else -> null
        }
    }

    private fun value(json: JSONObject, setting: SetupSetting): Any? =
        if (!json.has(setting.wire) || json.isNull(setting.wire)) null else json.opt(setting.wire)

    private fun choice(json: JSONObject, setting: SetupSetting, allowed: List<String>): String? {
        val value = value(json, setting) ?: return null
        return (value as? String)?.takeIf { it in allowed } ?: throw SetupInputException(setting.wire)
    }

    private fun flag(json: JSONObject, setting: SetupSetting): Boolean? {
        val value = value(json, setting) ?: return null
        return value as? Boolean ?: throw SetupInputException(setting.wire)
    }

    private fun minutes(json: JSONObject, setting: SetupSetting, allowed: List<Int>): Int? {
        val value = value(json, setting) ?: return null
        return (value as? Int)?.takeIf { it in allowed } ?: throw SetupInputException(setting.wire)
    }
}
