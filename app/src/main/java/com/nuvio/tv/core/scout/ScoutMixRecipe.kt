package com.nuvio.tv.core.scout

import androidx.annotation.StringRes
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.ScoutPick
import com.nuvio.tv.domain.model.TmdbCollectionFilters
import java.time.LocalDate

private const val COMEDY = "35"
private const val ANIMATION_GENRE = "16"
private const val CRIME_GENRE = "80"
private const val MYSTERY = "9648"
private const val SCI_FI_GENRE = "10765"
private const val DOCUMENTARY_GENRE = "99"
private const val FAMILY_GENRE = "10751"
private const val KIDS = "10762"
private const val ACTION = "10759"

/** Kinds of show where any episode works on its own, which is what a shuffle needs. */
private const val EPISODIC = "$COMEDY|$ANIMATION_GENRE|$CRIME_GENRE|$SCI_FI_GENRE|$MYSTERY|$ACTION|$FAMILY_GENRE|$KIDS"
private const val PEOPLE = "10763,10764,10766,10767"
private const val DRAMA = "18"
private const val SITCOM_WORDS = "193171|381587|210605"
private const val POLICE_WORDS = "268067|15167"
private const val POLICE_WORDS_WIDE = "268067|15167|6149|703"
private const val SPACE_WORDS = "281358|9882|3801"
private const val ADULT_CARTOON_WORDS = "161919|315444"
private const val RECIPE_PREFIX = "r"
private const val ANY = "x"

enum class ScoutMixDecade(val code: String, @StringRes val labelRes: Int, val from: Int?, val to: Int?) {
    ANY("x", R.string.scout_premix_any, null, null),
    SEVENTIES("70", R.string.scout_premix_70s, 1970, 1979),
    EIGHTIES("80", R.string.scout_premix_80s, 1980, 1989),
    NINETIES("90", R.string.scout_premix_90s, 1990, 1999),
    NOUGHTIES("00", R.string.scout_premix_00s, 2000, 2009),
    TENS("10", R.string.scout_premix_2010s, 2010, 2019),
    TWENTIES("20", R.string.scout_premix_2020s, 2020, null)
}

/**
 * A TMDB genre tag says little on its own (Wednesday carries Comedy), so a kind can also ask for TMDB keywords.
 * When the keywords leave too few shows, the loose form drops them and leaves out the genres that mark a show as
 * something else.
 */
enum class ScoutMixKind(
    val code: String,
    @StringRes val labelRes: Int,
    val genres: String?,
    val leaveOut: String? = null,
    val countries: String? = null,
    val keywords: String? = null,
    val looseKeywords: String? = null,
    val looseLeaveOut: String? = null,
    val leaveOutWords: String? = null
) {
    ANY("x", R.string.scout_premix_any, null),
    SITCOMS(
        "s", R.string.scout_premix_sitcoms, COMEDY, ANIMATION_GENRE, keywords = SITCOM_WORDS,
        looseLeaveOut = "$ANIMATION_GENRE,$DRAMA,$SCI_FI_GENRE,$ACTION,$CRIME_GENRE,$MYSTERY"
    ),
    CARTOONS("c", R.string.scout_premix_cartoons, ANIMATION_GENRE, countries = "US|GB|CA|AU|FR|IE", leaveOutWords = ADULT_CARTOON_WORDS),
    CRIME(
        "k", R.string.scout_premix_crime, CRIME_GENRE, "$ANIMATION_GENRE,$SCI_FI_GENRE", keywords = POLICE_WORDS,
        looseKeywords = POLICE_WORDS_WIDE
    ),
    SCI_FI("f", R.string.scout_premix_scifi, SCI_FI_GENRE, ANIMATION_GENRE, keywords = SPACE_WORDS),
    ANIME("a", R.string.scout_premix_anime, ANIMATION_GENRE, countries = "JP"),
    DOCS("d", R.string.scout_premix_docs, DOCUMENTARY_GENRE)
}

/** The moods a mix can take, each the same as the mood tab of the same name. */
enum class ScoutMixFeel(val code: String, val mood: ScoutMood?) {
    ANY("x", null),
    FEEL_GOOD("g", ScoutMood.FEEL_GOOD),
    LAUGH("l", ScoutMood.LAUGH),
    EDGE("e", ScoutMood.EDGE),
    WEIRD("w", ScoutMood.WEIRD),
    DARK("d", ScoutMood.DARK);

    @get:StringRes
    val labelRes: Int get() = mood?.labelRes ?: R.string.scout_premix_any
}

/** Small neighbours share a tile: alone they have too few shows for a mix. */
enum class ScoutMixFrom(val code: String, @StringRes val labelRes: Int, val countries: String?) {
    ANYWHERE("x", R.string.scout_premix_anywhere, null),
    US("us", R.string.scout_premix_from_us, "US"),
    UK_IRELAND("uk", R.string.scout_premix_from_uk, "GB|IE"),
    CANADA("ca", R.string.scout_premix_from_canada, "CA"),
    AUSTRALIA_NZ("au", R.string.scout_premix_from_australia, "AU|NZ"),
    KOREA("kr", R.string.scout_premix_from_korea, "KR"),
    JAPAN("jp", R.string.scout_premix_from_japan, "JP"),
    CHINESE("zh", R.string.scout_premix_from_chinese, "CN|TW|HK"),
    SPAIN("es", R.string.scout_premix_from_spain, "ES"),
    FRANCE("fr", R.string.scout_premix_from_france, "FR"),
    GERMANY("de", R.string.scout_premix_from_germany, "DE"),
    ITALY("it", R.string.scout_premix_from_italy, "IT"),
    NORDIC("no", R.string.scout_premix_from_nordic, "DK|SE|NO|FI|IS"),
    LATIN_AMERICA("la", R.string.scout_premix_from_latin, "MX|CO|AR|CL|BR"),
    TURKEY("tr", R.string.scout_premix_from_turkey, "TR"),
    INDIA("in", R.string.scout_premix_from_india, "IN"),
    THAILAND("th", R.string.scout_premix_from_thailand, "TH")
}

enum class ScoutMixBlend(val code: String, @StringRes val labelRes: Int) {
    CLOSE("c", R.string.scout_premix_blend_close),
    BALANCED("b", R.string.scout_premix_blend_balanced),
    SURPRISE("s", R.string.scout_premix_blend_surprise)
}

/** The choices behind a mix Scout fills by itself. */
data class ScoutMixRecipe(
    val decade: ScoutMixDecade = ScoutMixDecade.ANY,
    val kind: ScoutMixKind = ScoutMixKind.ANY,
    val feel: ScoutMixFeel = ScoutMixFeel.ANY,
    val from: ScoutMixFrom = ScoutMixFrom.ANYWHERE,
    val blend: ScoutMixBlend = ScoutMixBlend.BALANCED,
    val family: Boolean = false
) {
    fun encode(): String = listOf(RECIPE_PREFIX, decade.code, kind.code, feel.code, from.code, blend.code, if (family) "f" else ANY)
        .joinToString(".")

    companion object {
        fun decode(raw: String?): ScoutMixRecipe? {
            val parts = raw?.split('.') ?: return null
            if (parts.size != 7 || parts[0] != RECIPE_PREFIX) return null
            return ScoutMixRecipe(
                decade = ScoutMixDecade.entries.firstOrNull { it.code == parts[1] } ?: return null,
                kind = ScoutMixKind.entries.firstOrNull { it.code == parts[2] } ?: return null,
                feel = ScoutMixFeel.entries.firstOrNull { it.code == parts[3] } ?: return null,
                from = ScoutMixFrom.entries.firstOrNull { it.code == parts[4] } ?: return null,
                blend = ScoutMixBlend.entries.firstOrNull { it.code == parts[5] } ?: return null,
                family = parts[6] == "f"
            )
        }
    }
}

/**
 * The TMDB search for a recipe's shows. A mood brings its own genres and keywords, as on the mood tabs; the kind then
 * narrows to its first genre, since TMDB cannot ask for "this and (that or that)".
 * [years] widens the decade by that many years each side, [votes] lowers the vote floor, when too few shows fit.
 */
internal fun ScoutMixRecipe.filters(
    country: String,
    today: LocalDate = LocalDate.now(),
    years: Int = 0,
    votes: Int = 50,
    strict: Boolean = true
): TmdbCollectionFilters {
    val mood = feel.mood
    var f = if (mood != null) {
        ScoutAnswers(ScoutWatch.SERIES, mood, ScoutTime.ANY, family).slice(today, country).tv ?: TmdbCollectionFilters()
    } else if (family) {
        ScoutAnswers(ScoutWatch.SERIES, null, ScoutTime.ANY, true).slice(today, country).tv ?: TmdbCollectionFilters()
    } else TmdbCollectionFilters()
    val genres = kind.genres
    f = when {
        genres == null && f.withGenres == null && f.withKeywords == null -> f.copy(withGenres = EPISODIC)
        genres == null -> f
        f.withGenres == null -> f.copy(withGenres = genres)
        else -> f.copy(withGenres = scoutAllGenres(genres.substringBefore('|'), f.withGenres))
    }
    val words = if (strict) kind.keywords else kind.looseKeywords
    if (words != null && f.withKeywords == null) f = f.copy(withKeywords = words)
    val kindLeaveOut = if (strict) kind.leaveOut else kind.looseLeaveOut ?: kind.leaveOut
    val leaveOut = listOfNotNull(f.withoutGenres, kindLeaveOut, PEOPLE).flatMap { it.split(',') }
        .filter { it.isNotBlank() && it !in f.withGenres.orEmpty().split(',', '|') }.distinct()
    f = f.copy(withoutGenres = leaveOut.joinToString(",").ifBlank { null })
    kind.leaveOutWords?.let { words -> f = f.copy(withoutKeywords = listOfNotNull(f.withoutKeywords, words).joinToString("|")) }
    decade.from?.let { f = f.copy(releaseDateGte = "${it - years}-01-01") }
    if (decade.from != null) {
        f = f.copy(releaseDateLte = "${minOf((decade.to ?: today.year) + years, today.year)}-12-31")
    }
    val countries = from.countries ?: kind.countries
    if (countries != null) f = f.copy(withOriginCountry = countries)
    val floor = if (from.countries != null || decade == ScoutMixDecade.SEVENTIES) minOf(votes, SMALL_FLOOR) else votes
    return f.copy(voteCountGte = minOf(f.voteCountGte ?: floor, floor))
}

private const val SMALL_FLOOR = 20

/** The kinds where any episode works on its own: a decade or a country with no kind chosen is built from these. */
internal val SCOUT_CLASSIC_KINDS = listOf(ScoutMixKind.SITCOMS, ScoutMixKind.CRIME, ScoutMixKind.CARTOONS, ScoutMixKind.SCI_FI)

/** Fewer than this from those kinds (Korea, Latin America) and the mix keeps the broad search for that place. */
internal const val SCOUT_CLASSIC_FEWEST = 40

/** Below this many region-only shows, co-productions that list the region first are let back in. */
internal const val SCOUT_REGION_FEWEST = 30

/**
 * Shows made in the region only, so Clickbait (Australia and the US) stays out of Australian and NZ. A region with
 * few of those is topped up with co-productions that name it first.
 */
internal fun scoutFromRegion(picks: List<ScoutPick>, from: ScoutMixFrom): List<ScoutPick> {
    val codes = from.countries?.split('|')?.toSet() ?: return picks
    val only = picks.filter { pick -> pick.origin.all { it in codes } }
    if (only.size >= SCOUT_REGION_FEWEST) return only
    return only + picks.filter { pick -> pick !in only && pick.origin.firstOrNull() in codes }
}
