package com.nuvio.tv.core.iptv

import java.text.Normalizer
import java.util.Locale

data class VodTitle(val display: String, val key: String, val year: Int?) {
    val matchKey: String get() = VodTitles.compact(key)
}

data class VodEpisodeMarker(val series: String, val season: Int, val episode: Int, val title: String?)

data class VodTitleCandidate<T>(val item: T, val name: String, val year: Int?, val tmdbId: String? = null, val sourcePosition: Int = 0)

object VodTitles {
    fun parse(raw: String, year: Int? = null): VodTitle {
        var text = Normalizer.normalize(raw, Normalizer.Form.NFKC).replace(CONTROL, " ").trim()
        text = stripPrefixes(text)
        var found = year?.takeIf(::plausibleYear)
        text = BRACKETED.replace(text) { match ->
            val inner = match.groupValues[2].trim()
            val bracketYear = YEAR_ONLY.matchEntire(inner)?.value?.toInt()?.takeIf(::plausibleYear)
            when {
                bracketYear != null -> { if (found == null) found = bracketYear; " " }
                decoration(inner) -> " "
                else -> match.value
            }
        }
        text = text.split(SPACES).filterNot { it.isEmpty() || qualityToken(it) }.joinToString(" ")
        text = text.trim(*TRIM)
        val trailing = TRAILING_YEAR.find(text)
        if (trailing != null) {
            val head = text.substring(0, trailing.range.first).trim(*TRIM)
            val value = trailing.groupValues[1].toInt()
            if (head.isNotEmpty() && plausibleYear(value)) {
                if (found == null) found = value
                if (found == value) text = head
            }
        }
        text = text.trim(*TRIM).ifEmpty { raw.trim() }
        return VodTitle(text, normalise(text), found)
    }

    fun normalise(raw: String): String {
        var text = fold(raw)
        ARTICLE_SUFFIX.matchEntire(text)?.let { text = "${it.groupValues[2]} ${it.groupValues[1]}" }
        text = text.replace("&", " and ").replace("+", " plus ").replace(APOSTROPHES, "")
        val words = text.split(NON_WORD).filter { it.isNotEmpty() }
        val trimmed = if (words.size > 1 && words.first() in ARTICLES) words.drop(1) else words
        return trimmed.joinToString(" ")
    }

    fun compact(key: String): String = key.replace(" ", "")

    fun fold(raw: String): String {
        val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFKD)
        val text = StringBuilder(decomposed.length)
        for (char in decomposed) {
            if (Character.getType(char) == Character.NON_SPACING_MARK.toInt()) continue
            when (char) {
                'ß' -> text.append("ss")
                'æ', 'Æ' -> text.append("ae")
                'œ', 'Œ' -> text.append("oe")
                'ø', 'Ø' -> text.append('o')
                'ł', 'Ł' -> text.append('l')
                'đ', 'Đ', 'ð', 'Ð' -> text.append('d')
                'þ', 'Þ' -> text.append("th")
                else -> text.append(char)
            }
        }
        return text.toString().lowercase(Locale.ROOT)
    }

    fun yearOf(value: String?): Int? = value?.let { YEAR_ANYWHERE.find(it)?.value?.toInt()?.takeIf(::plausibleYear) }

    fun episode(raw: String): VodEpisodeMarker? {
        val text = Normalizer.normalize(raw, Normalizer.Form.NFKC).trim()
        val match = EPISODE.find(text) ?: CROSS_EPISODE.find(text) ?: return null
        val season = match.groupValues[1].toInt()
        val episode = match.groupValues[2].toInt()
        if (season > 300 || episode > 5000) return null
        val series = parse(text.substring(0, match.range.first).trim(*TRIM, ' ')).display.takeIf { it.isNotBlank() } ?: return null
        val title = text.substring(match.range.last + 1).trim(*TRIM, ' ').takeIf { it.isNotEmpty() }?.let { parse(it).display }
        return VodEpisodeMarker(series, season, episode, title)
    }

    fun <T> rank(title: String, year: Int?, candidates: List<VodTitleCandidate<T>>, tmdbId: String? = null): List<T> {
        val wanted = compact(normalise(parse(title).display))
        val wantedYear = year ?: parse(title).year
        if (wanted.isEmpty()) return emptyList()
        return candidates.mapNotNull { candidate ->
            val parsed = parse(candidate.name, candidate.year)
            if (parsed.matchKey != wanted) return@mapNotNull null
            if (tmdbId != null && candidate.tmdbId != null && candidate.tmdbId != tmdbId) return@mapNotNull null
            val yearScore = when {
                wantedYear == null -> if (parsed.year == null) 0 else 1
                parsed.year == null -> 2
                parsed.year == wantedYear -> 0
                kotlin.math.abs(parsed.year - wantedYear) == 1 -> 1
                else -> return@mapNotNull null
            }
            val tmdbScore = if (tmdbId != null && candidate.tmdbId == tmdbId) 0 else 1
            Ranked(candidate, intArrayOf(tmdbScore, yearScore, candidate.sourcePosition, decorationCount(candidate.name)))
        }.sortedWith { a, b ->
            a.score.indices.asSequence().map { a.score[it].compareTo(b.score[it]) }.firstOrNull { it != 0 } ?: 0
        }.map { it.candidate.item }
    }

    private class Ranked<T>(val candidate: VodTitleCandidate<T>, val score: IntArray)

    private fun decorationCount(name: String): Int {
        val clean = parse(name).display
        return (name.length - clean.length).coerceAtLeast(0)
    }

    private fun stripPrefixes(value: String): String {
        var text = value
        repeat(4) {
            val next = BRACKET_PREFIX.find(text)?.takeIf { it.range.first == 0 && bracketPrefix(it.groupValues[1], it.groupValues[2]) }?.let { text.substring(it.range.last + 1) }
                ?: CODE_PREFIX.find(text)?.takeIf { it.range.first == 0 && codePrefix(it.groupValues[1], it.groupValues[2]) }?.let { text.substring(it.range.last + 1) }
                ?: return text
            if (next.isBlank()) return text
            text = next.trimStart()
        }
        return text
    }

    private fun bracketPrefix(open: String, code: String): Boolean {
        val upper = code.trim().uppercase(Locale.ROOT)
        if (open == "|" && code.trim() == upper && upper.length in 2..5) return true
        val parts = upper.split('-', '/', ' ', '+').filter { it.isNotEmpty() }
        return parts.isNotEmpty() && parts.all { it in KNOWN_CODES || qualityToken(it) || (it.length == 2 && it.all(Char::isLetter)) }
    }

    private fun codePrefix(code: String, separator: String): Boolean {
        val upper = code.uppercase(Locale.ROOT)
        if (code != upper && code.length > 3) return false
        val parts = upper.split('-', '/', ' ').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return false
        if (parts.all { it in KNOWN_CODES || qualityToken(it) }) return true
        return separator.trim() == "-" && code == upper && parts.all { it.length in 2..3 && it.all(Char::isLetter) }
    }

    private fun decoration(inner: String): Boolean {
        val words = inner.split(DECORATION_SPLIT).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        return words.all { word ->
            val upper = word.uppercase(Locale.ROOT)
            qualityToken(word) || upper in DECORATION_WORDS || upper in KNOWN_CODES || (word.length == 2 && word == upper && word.all(Char::isLetter))
        }
    }

    private fun qualityToken(word: String): Boolean = word.uppercase(Locale.ROOT).trim(*TRIM) in QUALITY
    private fun plausibleYear(year: Int) = year in 1888..2100

    private val TRIM = charArrayOf(' ', '-', '|', ':', '.', ',', '_', '*', '•', '·', '–', '—')
    private val CONTROL = Regex("[\\p{Cc}\\u200B-\\u200F\\uFEFF]")
    private val SPACES = Regex("\\s+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val APOSTROPHES = Regex("['’`´]")
    private val BRACKETED = Regex("([(\\[{])([^()\\[\\]{}]{0,40})[)\\]}]")
    private val YEAR_ONLY = Regex("(18|19|20)[0-9]{2}")
    private val YEAR_ANYWHERE = Regex("(?<![0-9])(18|19|20)[0-9]{2}(?![0-9])")
    private val TRAILING_YEAR = Regex("(?:^|\\s|[-–—:|.,])((?:18|19|20)[0-9]{2})$")
    private val BRACKET_PREFIX = Regex("^\\s*([|\\[(])\\s*([A-Za-z0-9+ /-]{1,12})\\s*[|\\])]\\s*[-:|]?\\s*")
    private val CODE_PREFIX = Regex("^\\s*([A-Za-z0-9+]{2,5}(?:[-/][A-Za-z0-9+]{2,5})?)(\\s*[-:|]\\s*|\\s+[-–—]\\s+)")
    private val ARTICLE_SUFFIX = Regex("^(.*\\S)\\s*,\\s*(the|a|an)$")
    private val EPISODE = Regex("(?i)(?<![A-Za-z0-9])S(?:eason)?\\s*([0-9]{1,3})\\s*[._ -]?\\s*E(?:p(?:isode)?)?\\s*([0-9]{1,4})(?![0-9])")
    private val CROSS_EPISODE = Regex("(?<![A-Za-z0-9])([0-9]{1,2})x([0-9]{1,3})(?![0-9])")
    private val DECORATION_SPLIT = Regex("[\\s/+,&|-]+")
    private val ARTICLES = setOf("the", "a", "an")
    private val QUALITY = setOf("4K", "8K", "UHD", "FHD", "HD", "SD", "HQ", "LQ", "HEVC", "H265", "H.265", "H264", "H.264", "X265", "X264", "AVC",
        "1080P", "720P", "2160P", "480P", "576P", "HDR", "HDR10", "HDR10+", "DV", "60FPS", "50FPS", "10BIT",
        "BLURAY", "BLU-RAY", "BDRIP", "BRRIP", "WEBRIP", "WEB-DL", "WEBDL", "DVDRIP", "HDRIP", "HDTV", "REMUX", "IMAX", "ATMOS", "DDP5.1", "AC3", "AAC")
    private val DECORATION_WORDS = setOf("MULTI", "MULTISUB", "MULTI-SUB", "MULTI-SUBS", "MULTISUBS", "MULTI-AUDIO", "SUB", "SUBS", "SUBBED", "SUBTITLED",
        "DUB", "DUBBED", "AUDIO", "VOSTFR", "VOST", "VOSE", "VO", "VF", "VFF", "VFQ", "TRUEFRENCH", "LATINO", "CASTELLANO", "DUAL", "ESP", "ENG", "ITA", "GER",
        "LEG", "LEGENDADO", "DUBLADO", "NEW", "UPDATED", "EXTENDED", "UNCUT", "REMASTERED", "DIRECTORS", "CUT", "MOVIE", "FILM", "SERIES", "VOD", "EN", "NL",
        "NF", "AMZ", "DSNP", "HMAX", "ATVP", "PCOK", "HULU", "STAN", "BINGE")
    private val KNOWN_CODES = setOf("EN", "ENG", "UK", "US", "USA", "AU", "AUS", "NZ", "CA", "IE", "FR", "DE", "GER", "ES", "ESP", "IT", "ITA", "PT", "BR",
        "NL", "BE", "PL", "TR", "AR", "IN", "HI", "RU", "SE", "NO", "DK", "FI", "GR", "RO", "HU", "CZ", "SK", "HR", "RS", "BG", "AL", "EX-YU", "EXYU", "LAT",
        "LATAM", "MX", "JP", "KR", "CN", "PH", "MULTI", "VOD", "VOSTFR", "NF", "AMZ", "DSNP", "HMAX", "ATV", "ATVP", "HBO", "STAN", "BINGE", "TOP", "KIDS")
}
