package com.nuvio.tv.core.scout

/** A language choice in Leave out languages. Indian languages and Chinese cover several original languages. */
data class ScoutLanguageChoice(val id: String, val codes: Set<String>)

const val SCOUT_LANGUAGE_INDIAN = "indian"

val SCOUT_LEAVE_OUT_LANGUAGES: List<ScoutLanguageChoice> = listOf(
    ScoutLanguageChoice(SCOUT_LANGUAGE_INDIAN, setOf("hi", "ta", "te", "ml", "kn", "bn", "mr", "pa", "gu", "ur")),
    ScoutLanguageChoice("ko", setOf("ko")),
    ScoutLanguageChoice("zh", setOf("zh", "cn")),
    ScoutLanguageChoice("ja", setOf("ja")),
    ScoutLanguageChoice("th", setOf("th")),
    ScoutLanguageChoice("tl", setOf("tl")),
    ScoutLanguageChoice("id", setOf("id")),
    ScoutLanguageChoice("tr", setOf("tr")),
    ScoutLanguageChoice("ar", setOf("ar")),
    ScoutLanguageChoice("ru", setOf("ru")),
    ScoutLanguageChoice("es", setOf("es")),
    ScoutLanguageChoice("pt", setOf("pt")),
    ScoutLanguageChoice("fr", setOf("fr")),
    ScoutLanguageChoice("it", setOf("it")),
    ScoutLanguageChoice("de", setOf("de"))
)

fun scoutLeaveOutLanguageCodes(chosen: Set<String>): Set<String> =
    SCOUT_LEAVE_OUT_LANGUAGES.filter { it.id in chosen }.flatMapTo(HashSet()) { it.codes }
