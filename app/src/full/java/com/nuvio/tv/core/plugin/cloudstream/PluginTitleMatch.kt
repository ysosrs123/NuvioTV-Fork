package com.nuvio.tv.core.plugin.cloudstream

private val PART_NUMBER = Regex("\\b(?:part|parte)\\s*(\\d+)\\b")

internal fun calculateSimilarity(s1: String, s2: String): Double {
    val a = s1.lowercase().trim()
    val b = s2.lowercase().trim()
    if (a == b) return 1.0
    if (a.isEmpty() || b.isEmpty()) return 0.0

    val aNorm = normalizeTitleForMatch(a)
    val bNorm = normalizeTitleForMatch(b)
    // A title that is only a number, like "1917", normalizes to nothing.
    if (aNorm.isEmpty() || bNorm.isEmpty()) return 0.0
    if (differentPartNumbers(a, b)) return 0.0
    if (aNorm == bNorm) return 0.95

    if (aNorm.contains(bNorm) || bNorm.contains(aNorm)) {
        // Length-ratio-weighted containment. Prevents "Boys" ⊂ "Fantasy Boys" or
        // "Invincible" ⊂ "The Boys: Diábolico Latino" from scoring 0.85, since those
        // are different works. Only treat as strong match when the strings are
        // close in length (trivial punctuation/articles diff).
        val shortLen = minOf(aNorm.length, bNorm.length).toDouble()
        val longLen = maxOf(aNorm.length, bNorm.length).toDouble()
        val ratio = shortLen / longLen
        if (ratio >= 0.8) return 0.85
        // else fall through to Levenshtein, which naturally scores lower for
        // significant length mismatches.
    }

    val distance = levenshteinDistance(aNorm, bNorm)
    val maxLen = maxOf(aNorm.length, bNorm.length)
    return 1.0 - (distance.toDouble() / maxLen)
}

/**
 * Sites often list a release as the title plus quality and language tags, which scores low on edit
 * distance. The whole title appearing word for word is real evidence; a similar-looking title is not.
 */
internal fun titleContainedWholeWords(s1: String, s2: String): Boolean {
    val a = normalizeTitleForMatch(s1.lowercase().trim())
    val b = normalizeTitleForMatch(s2.lowercase().trim())
    if (a.isEmpty() || b.isEmpty()) return false
    return " $a ".contains(" $b ") || " $b ".contains(" $a ")
}

private fun differentPartNumbers(a: String, b: String): Boolean {
    val partA = PART_NUMBER.find(a)?.groupValues?.get(1) ?: return false
    val partB = PART_NUMBER.find(b)?.groupValues?.get(1) ?: return false
    return partA != partB
}

/**
 * Strip noise that provider titles commonly add: release year, season/part
 * markers, language/dub tags. Lets "Invincible S4 Castellano" normalize to
 * "Invincible" so it matches the TMDB primary title cleanly.
 */
private fun normalizeTitleForMatch(lowered: String): String {
    return lowered
        .replace(Regex("\\(\\d{4}\\)"), " ")
        .replace(Regex("\\b\\d{4}\\b"), " ") // bare year
        .replace(Regex("\\b(temporada|season)\\s*\\d+\\b"), " ")
        .replace(Regex("\\b[st]\\d{1,2}\\b"), " ") // s1, t2, s04
        .replace(Regex("\\b(part|parte)\\s*\\d+\\b"), " ")
        .replace(Regex("\\b(latino|castellano|subtitulado|sub\\s*espa(ñ|n)ol|espa(ñ|n)ol|dual|vose|vostfr|subbed|dubbed)\\b"), " ")
        .replace(Regex("[:\\-\u2013\u2014]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

private fun levenshteinDistance(s1: String, s2: String): Int {
    val m = s1.length
    val n = s2.length
    val dp = Array(m + 1) { IntArray(n + 1) }

    for (i in 0..m) dp[i][0] = i
    for (j in 0..n) dp[0][j] = j

    for (i in 1..m) {
        for (j in 1..n) {
            val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
            dp[i][j] = minOf(
                dp[i - 1][j] + 1,
                dp[i][j - 1] + 1,
                dp[i - 1][j - 1] + cost
            )
        }
    }
    return dp[m][n]
}
