package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.GuideChannel
import com.nuvio.tv.core.iptv.GuideProgramme
import com.nuvio.tv.core.iptv.GuideTimestamp
import com.nuvio.tv.core.iptv.LocalizedGuideText
import org.json.JSONArray
import org.json.JSONObject

internal object IptvGuideJson {
    fun channel(value: GuideChannel): String = JSONObject().put("id", value.externalId).put("names", texts(value.names)).toString()
    fun channel(value: String): GuideChannel = JSONObject(value).let { GuideChannel(it.getString("id"), texts(it.getJSONArray("names"))) }
    fun programme(value: GuideProgramme): String = JSONObject().put("channel", value.channelExternalId)
        .put("start", timestamp(value.start)).put("stop", value.stop?.let(::timestamp))
        .put("titles", texts(value.titles)).put("descriptions", texts(value.descriptions)).toString()
    fun programme(value: String): GuideProgramme = JSONObject(value).let {
        GuideProgramme(it.getString("channel"), timestamp(it.getJSONObject("start")), it.optJSONObject("stop")?.let(::timestamp),
            texts(it.getJSONArray("titles")), texts(it.getJSONArray("descriptions")))
    }
    private fun timestamp(value: GuideTimestamp) = JSONObject().put("ms", value.epochMillis).put("precision", value.precisionDigits).put("raw", value.raw)
    private fun timestamp(value: JSONObject) = GuideTimestamp(value.getLong("ms"), value.getInt("precision"), value.getString("raw"))
    private fun texts(values: List<LocalizedGuideText>) = JSONArray().apply { values.forEach { put(JSONObject().put("text", it.text).put("language", it.language)) } }
    private fun texts(values: JSONArray): List<LocalizedGuideText> = (0 until values.length()).map { index ->
        values.getJSONObject(index).let { LocalizedGuideText(it.getString("text"), if (it.isNull("language")) null else it.getString("language")) }
    }
}
