package com.nuvio.tv.core.iptv

import java.text.Normalizer
import java.util.Locale

private val regionPrefix = Regex("^[\\p{L}\\p{N}]{1,6}\\s*[:|]\\s*")
private val bracketed = Regex("[\\[(][^\\])]*[\\])]")
private val separators = Regex("[^\\p{L}\\p{N}]+")
private val marks = Regex("\\p{M}+")
private val countrySuffix = Regex("\\.[A-Za-z]{2,3}$")
private val qualityTags = setOf("hd", "sd", "fhd", "uhd", "qhd", "hdr", "4k", "8k", "hevc", "h264", "h265", "x264", "x265",
    "720p", "1080p", "1080i", "2160p", "50fps", "60fps", "raw", "backup", "hq", "lq")

fun guideMatchName(value: String): String {
    var text = marks.replace(Normalizer.normalize(value, Normalizer.Form.NFKD), "").lowercase(Locale.ROOT).trim()
    repeat(2) { text = regionPrefix.replaceFirst(text, "") }
    text = bracketed.replace(text, " ").replace("+", " plus ").replace("&", " ")
    val tokens = text.split(separators).filter { it.isNotEmpty() && it != "and" }.toMutableList()
    while (tokens.size > 1 && tokens.last() in qualityTags) tokens.removeAt(tokens.lastIndex)
    return tokens.joinToString("").take(240)
}

fun guideIdMatchName(externalId: String): String = guideMatchName(countrySuffix.replace(externalId.trim(), ""))

fun uniqueNameMatch(name: String, feeds: List<GuideNameIndex>): GuideKey? {
    val key = guideMatchName(name).takeIf { it.length >= 2 } ?: return null
    for (feed in feeds) {
        val ids = feed.names[key] ?: continue
        if (ids.size == 1) return GuideKey(feed.feedId, ids.single())
        return null
    }
    return null
}

data class GuideNameIndex(val feedId: String, val names: Map<String, Set<String>>)
