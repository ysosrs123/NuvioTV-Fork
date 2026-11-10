package com.nuvio.tv.core.scout

private fun art(imdbId: String) = "https://images.metahub.space/background/medium/$imdbId/img"

private val SERIES_ART = mapOf(
    ScoutMood.FEEL_GOOD to "tt10986410",
    ScoutMood.LAUGH to "tt0386676",
    ScoutMood.ACTION to "tt9288030",
    ScoutMood.EDGE to "tt0903747",
    ScoutMood.MIND to "tt11280740",
    ScoutMood.DARK to "tt2356777",
    ScoutMood.SCARY to "tt6763664",
    ScoutMood.ROMANTIC to "tt8740790",
    ScoutMood.EPIC to "tt0944947",
    ScoutMood.TEARS to "tt5555260",
    ScoutMood.SLOW to "tt0804503",
    ScoutMood.WEIRD to "tt0098936"
)

private val FAMILY_SERIES_ART = mapOf(
    ScoutMood.FEEL_GOOD to "tt7678620",
    ScoutMood.LAUGH to "tt0206512",
    ScoutMood.ACTION to "tt0417299",
    ScoutMood.EDGE to "tt0063950",
    ScoutMood.MIND to "tt1865718",
    ScoutMood.DARK to "tt4834206",
    ScoutMood.MAGICAL to "tt6385540",
    ScoutMood.ROMANTIC to "tt5421602",
    ScoutMood.EPIC to "tt8050756",
    ScoutMood.TEARS to "tt0094582",
    ScoutMood.SLOW to "tt1094229",
    ScoutMood.WEIRD to "tt1305826"
)

/** The picture on a mood's tile: a well-known title of the kind being asked for, gentler on Family night. */
fun scoutMoodArt(mood: ScoutMood, watch: ScoutWatch, family: Boolean): String = art(
    (if (watch == ScoutWatch.SERIES) (if (family) FAMILY_SERIES_ART else SERIES_ART)[mood] else null) ?: when (mood) {
        ScoutMood.FEEL_GOOD -> if (watch == ScoutWatch.ANIME) "tt0097814" else "tt4468740"
        ScoutMood.LAUGH -> when {
            watch == ScoutWatch.ANIME -> "tt4508902"
            family -> "tt0114709"
            else -> "tt0829482"
        }
        ScoutMood.ACTION -> if (family) "tt0317705" else "tt1392190"
        ScoutMood.EDGE -> if (family) "tt0107290" else "tt1392214"
        ScoutMood.MIND -> if (family) "tt2096673" else "tt1375666"
        ScoutMood.DARK -> if (family) "tt1201607" else "tt0114369"
        ScoutMood.SCARY -> "tt7784604"
        ScoutMood.ROMANTIC -> when {
            watch == ScoutWatch.ANIME -> "tt5311514"
            family -> "tt1049413"
            else -> "tt0338013"
        }
        ScoutMood.EPIC -> "tt0167260"
        ScoutMood.TEARS -> if (family) "tt2380307" else "tt0120689"
        ScoutMood.SLOW -> if (family) "tt0096283" else "tt0477348"
        ScoutMood.WEIRD -> if (family) "tt0367594" else "tt2278388"
        ScoutMood.SHOUNEN -> "tt9335498"
        ScoutMood.ISEKAI -> "tt2250192"
        ScoutMood.SLICE_OF_LIFE -> "tt0096283"
        ScoutMood.MECHA -> "tt0112159"
        ScoutMood.PSYCHOLOGICAL -> "tt0877057"
        ScoutMood.MAGICAL_GIRL -> "tt1773185"
        ScoutMood.SPORT_ANIME -> "tt3398540"
        ScoutMood.DARK_FANTASY -> "tt2560140"
        ScoutMood.SAMURAI -> "tt0423731"
        ScoutMood.TRUE_CRIME -> "tt5189670"
        ScoutMood.NATURE -> "tt0795176"
        ScoutMood.HISTORY -> "tt0098769"
        ScoutMood.SPACE -> "tt2395695"
        ScoutMood.FOOD -> "tt4295140"
        ScoutMood.MUSIC -> "tt2870648"
        ScoutMood.SPORT -> "tt8420184"
        ScoutMood.WAR -> "tt7905466"
        ScoutMood.SCIENCE -> "tt0383126"
        ScoutMood.TRAVEL -> "tt2845786"
        ScoutMood.ART -> "tt1587707"
        ScoutMood.POLITICS -> "tt5895028"
        ScoutMood.MAGICAL -> "tt0241527"
    }
)
