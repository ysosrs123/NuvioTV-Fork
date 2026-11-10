package com.nuvio.tv.core.scout.ask

internal const val ASK_SORT_CANDIDATES = 100
internal const val ASK_MAX_SUMMARY = 400
private val LIST_YEAR = Regex("\\s*\\(\\d{4}\\)\\s*(movie|series)?\\s*$")

data class AskCandidate(val key: String, val title: String, val year: Int?, val isSeries: Boolean)

data class AskRanked(val key: String, val reason: String)

private fun tasteLines(taste: AskTaste) = buildString {
    taste.summary?.takeIf { it.isNotBlank() }?.let { append("\nThe viewer's taste in their own words: ").append(it.take(ASK_MAX_SUMMARY)) }
    taste.likedTitles.take(ASK_MAX_LIKED).takeIf { it.isNotEmpty() }?.let {
        append("\nTitles the viewer liked: ").append(it.joinToString("; "))
    }
    taste.dislikedTitles.take(ASK_MAX_DISLIKED).takeIf { it.isNotEmpty() }?.let {
        append("\nTitles the viewer did not like: ").append(it.joinToString("; "))
    }
    taste.leaveOutGenres.takeIf { it.isNotEmpty() }?.let {
        append("\nGenres the viewer leaves out: ").append(it.joinToString(", "))
    }
}

internal fun buildSortPrompt(candidates: List<AskCandidate>, taste: AskTaste, answerLanguage: String): AskPrompt {
    val system = """
        You sort film and series suggestions for one viewer of a TV app. Answer with JSON only, in this shape:
        {"rowName":"","includeSeen":false,"titles":[{"title":"...","year":1999,"type":"movie","reason":"..."}]}
        - Choose the $ASK_MAX_TITLES titles from the list that best fit this viewer, best first.
        - Only titles from the list. title: only the name before the | sign, copied exactly. Never add a title that is not on the list.
        - reason: one short sentence, at most 15 words, in $answerLanguage, saying why it suits this viewer. Do not mention the list.
        - Prefer a varied selection over many of the same kind. Avoid what the viewer did not like.
    """.trimIndent()
    val user = buildString {
        append("The list:\n")
        candidates.take(ASK_SORT_CANDIDATES).forEach { candidate ->
            append(candidate.title).append(" | ")
            candidate.year?.let { append(it).append(" | ") }
            append(if (candidate.isSeries) "series\n" else "movie\n")
        }
        append(tasteLines(taste).trimStart())
    }
    return AskPrompt(system, user)
}

internal fun buildSummaryPrompt(taste: AskTaste, answerLanguage: String): AskPrompt {
    val system = """
        You describe one viewer's taste in films and series for a TV app. Answer with JSON only, in this shape:
        {"rowName":"...","includeSeen":false,"titles":[]}
        - rowName: the description, at most 35 words, in $answerLanguage, plain and specific, about tone, pace and themes.
        - Say what they avoid if their dislikes show it. Do not list the titles. Do not guess at anything personal about the viewer.
    """.trimIndent()
    return AskPrompt(system, tasteLines(taste).trimStart())
}

internal fun buildMoodPrompt(mood: String, candidates: List<AskCandidate>): AskPrompt {
    val system = """
        You check that films and series really fit the mood a viewer picked in a TV app. Answer with JSON only, in this shape:
        {"rowName":"1,4,7","includeSeen":false,"titles":[]}
        - rowName: the numbers of the titles to keep, separated by commas. Use only numbers on the list.
        - Keep a title only if its main feel delivers the mood for a typical viewer. Judge the whole title, not one subplot or episode.
    """.trimIndent()
    val user = buildString {
        append("Mood: ").append(mood).append('\n')
        candidates.forEachIndexed { index, candidate ->
            append(index + 1).append(". ").append(candidate.title)
            candidate.year?.let { append(" (").append(it).append(")") }
            append(if (candidate.isSeries) " - series\n" else " - movie\n")
        }
    }
    return AskPrompt(system, user)
}

internal fun parseMoodKeep(text: String, size: Int): Set<Int>? =
    parseSummary(text)?.split(',', ' ')?.mapNotNull { it.trim().toIntOrNull() }?.filter { it in 1..size }?.toSet()?.takeIf { it.isNotEmpty() }

/** Only what was on the list survives, so the AI cannot put a title on the box that Scout did not find. */
internal fun matchSorted(parsed: AskParsed, candidates: List<AskCandidate>): List<AskRanked> {
    val byName = candidates.groupBy { normaliseTitle(it.title) }
    return parsed.picks.mapNotNull { pick ->
        val same = byName[normaliseTitle(pick.title.substringBefore('|').replace(LIST_YEAR, ""))].orEmpty()
        val found = same.firstOrNull { it.year == pick.year && (pick.isSeries == null || pick.isSeries == it.isSeries) }
            ?: same.singleOrNull()
            ?: return@mapNotNull null
        AskRanked(found.key, pick.reason)
    }.distinctBy { it.key }
}

/** Works for any shape of the reply: the description travels in the same field as a row's name. */
internal fun parseSummary(text: String): String? {
    val match = Regex("\"rowName\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(text) ?: return null
    return match.groupValues[1].replace("\\\"", "\"").replace("\\n", " ").replace("\\\\", "\\")
        .trim().take(ASK_MAX_SUMMARY).ifBlank { null }
}
