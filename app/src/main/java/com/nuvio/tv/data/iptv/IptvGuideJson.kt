package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.GuideChannel
import com.nuvio.tv.core.iptv.GuideProgramme
import com.nuvio.tv.core.iptv.GuideTimestamp
import com.nuvio.tv.core.iptv.LocalizedGuideText
import com.nuvio.tv.core.iptv.guideIconUrl
import org.json.JSONArray
import org.json.JSONObject

internal object IptvGuideJson {
    private const val FULL_PRECISION = 14

    fun channel(value: GuideChannel): String = JSONObject().put("n", compact(value.names)).toString()
    fun channel(value: String, externalId: String): GuideChannel = JSONObject(value).let {
        if (it.has("names")) GuideChannel(it.optString("id", externalId), texts(it.getJSONArray("names"))) else GuideChannel(externalId, compact(it.getJSONArray("n")))
    }
    fun programme(value: GuideProgramme): String = JSONObject().put("s", value.start.epochMillis)
        .apply {
            if (value.start.precisionDigits != FULL_PRECISION) put("p", value.start.precisionDigits)
            value.stop?.let { stop -> put("e", stop.epochMillis); if (stop.precisionDigits != FULL_PRECISION) put("q", stop.precisionDigits) }
            put("t", compact(value.titles))
            if (value.descriptions.isNotEmpty()) put("d", compact(value.descriptions))
            if (value.categories.isNotEmpty()) put("c", JSONArray(value.categories))
            guideIconUrl(value.icon)?.let { put("i", it) }
        }.toString()
    fun programme(value: String, externalId: String): GuideProgramme = JSONObject(value).let {
        if (it.has("start")) return legacy(it, externalId)
        GuideProgramme(externalId, GuideTimestamp(it.getLong("s"), it.optInt("p", FULL_PRECISION)),
            if (it.has("e")) GuideTimestamp(it.getLong("e"), it.optInt("q", FULL_PRECISION)) else null,
            compact(it.getJSONArray("t")), it.optJSONArray("d")?.let { values -> compact(values) }.orEmpty(), strings(it.optJSONArray("c")),
            if (it.isNull("i")) null else guideIconUrl(it.optString("i")))
    }
    private fun legacy(value: JSONObject, externalId: String) = GuideProgramme(value.optString("channel", externalId), timestamp(value.getJSONObject("start")),
        value.optJSONObject("stop")?.let(::timestamp), texts(value.getJSONArray("titles")), texts(value.getJSONArray("descriptions")),
        strings(value.optJSONArray("categories")), if (value.isNull("icon")) null else guideIconUrl(value.optString("icon")))
    private fun strings(values: JSONArray?): List<String> = values?.let { (0 until it.length()).map(it::getString) }.orEmpty()
    private fun timestamp(value: JSONObject) = GuideTimestamp(value.getLong("ms"), value.getInt("precision"))
    private fun compact(values: List<LocalizedGuideText>) = JSONArray().apply {
        values.forEach { text -> put(JSONArray().put(text.text).apply { text.language?.let { language -> put(language) } }) }
    }
    private fun compact(values: JSONArray): List<LocalizedGuideText> = (0 until values.length()).map { index ->
        values.getJSONArray(index).let { LocalizedGuideText(it.getString(0), if (it.length() > 1 && !it.isNull(1)) it.getString(1) else null) }
    }
    private fun texts(values: JSONArray): List<LocalizedGuideText> = (0 until values.length()).map { index ->
        values.getJSONObject(index).let { LocalizedGuideText(it.getString("text"), if (it.isNull("language")) null else it.getString("language")) }
    }
}
