package com.nuvio.tv.core.scout

import androidx.annotation.StringRes
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.TmdbCollectionFilters
import java.util.Locale

enum class ScoutSliceGroup(@StringRes val titleRes: Int, val inPicker: Boolean = true) {
    MOOD(R.string.scout_group_mood),
    DECADE(R.string.scout_group_decade),
    COUNTRY(R.string.scout_group_country),
    REGION(R.string.scout_group_region),
    LANGUAGE(R.string.scout_group_language),
    LENGTH(R.string.scout_group_length),
    THEME(R.string.scout_group_theme),
    ACCLAIM(R.string.scout_group_acclaim),
    GENRE(R.string.scout_group_genre, inPicker = false)
}

/** One category on an award's page, read from these lists of the bundled awards file. */
data class ScoutAwardTab(@StringRes val titleRes: Int, val awards: List<String>)

/** Which lists of the bundled awards file a tile shows, and the categories its page offers as tabs. */
data class ScoutAwardChoice(
    val awards: List<String>,
    val winners: Boolean = true,
    val tabs: List<ScoutAwardTab> = emptyList()
)

data class ScoutAwardTabTitles(@StringRes val titleRes: Int, val series: Boolean, val ids: List<Int>)

data class ScoutAwardLists(
    val wonMovies: List<Int> = emptyList(),
    val wonSeries: List<Int> = emptyList(),
    val nominatedMovies: List<Int> = emptyList(),
    val nominatedSeries: List<Int> = emptyList()
)

data class ScoutAwardTitles(val movies: List<Int>, val series: List<Int>) {
    val count: Int get() = movies.size + series.size
}

/** Tiles that show one chosen title's TMDB backdrop. */
val SCOUT_PINNED_ART: Map<String, String> = mapOf(
    "theme_time" to "/5bzPWQ2dFUl2aZKkp7ILJVVkRed.jpg",
    "theme_heist" to "/ncoqdHs1poUaBqyKic9YI8ai7MP.jpg",
    "theme_space" to "/8sNiAPPYU14PUepFNeSNGUTiHW.jpg",
    "theme_superhero" to "/gHLs7Fy3DzLmLsD4lmfqL55KGcl.jpg",
    "theme_zombie" to "/lY2DhbA7Hy44fAKddr06UrXWWaQ.jpg",
    "theme_spy" to "/klJMCIblHLFwCuGjKz7tyOpekIC.jpg",
    "theme_survival" to "/bdO24JwOiv1r0WV7VPyM1ZnI4Q.jpg",
    "theme_court" to "/cwKuMNndfjSl8iQIcAISL0C1tDZ.jpg",
    "theme_whodunit" to "/4HWAQu28e2yaWrtupFPGFkdNU7V.jpg",
    "theme_dystopia" to "/gqrnQA6Xppdl8vIb2eJc58VC1tW.jpg",
    "theme_ai" to "/uqOuJ50EtTj7kkDIXP8LCg7G45D.jpg",
    "theme_coming_of_age" to "/yjGllQUm28R4X9xD9T5xMszirgw.jpg",
    "theme_road" to "/u49EZzHvGCNrHdQxkc3hhLmQEPP.jpg",
    "theme_book" to "/oiwc338EoBgS4sEI2ixAny4KQKg.jpg",
    "theme_true" to "/8VXhcrl5z2I1zEU9X3pkkNrZlD.jpg",
    "theme_miniseries" to "/900tHlUYUkp7Ol04XFSoAaEIXcT.jpg",
    "theme_sitcom" to "/l0qVZIpXtIo7km9u5Yqh0nKPOr5.jpg",
    "acclaim_top" to "/pNjh59JSxChQktamG3LMp9ZoQzp.jpg",
    "acclaim_cult" to "/54aVU7tnvDKkX6ibzFg6ke03BV.jpg"
)

const val SCOUT_AWARD_MIN_TITLES = 20
private const val AWARD_SIDE_MIN_TITLES = 5

/** Winners, or nominees who did not win, in the file's order (newest first). A side with only a stray title or two is left out. */
fun scoutAwardTitles(choice: ScoutAwardChoice, lists: Map<String, ScoutAwardLists>): ScoutAwardTitles {
    val chosen = choice.awards.mapNotNull { lists[it] }
    fun side(won: (ScoutAwardLists) -> List<Int>, nominated: (ScoutAwardLists) -> List<Int>): List<Int> {
        val winners = chosen.flatMap(won).distinct()
        val ids = if (choice.winners) winners else chosen.flatMap(nominated).distinct() - winners.toSet()
        return ids.takeIf { it.size >= AWARD_SIDE_MIN_TITLES }.orEmpty()
    }
    return ScoutAwardTitles(side({ it.wonMovies }, { it.nominatedMovies }), side({ it.wonSeries }, { it.nominatedSeries }))
}

/** The categories of an award page, each with the titles it shows. A category with too few titles is left out. */
fun scoutAwardTabs(choice: ScoutAwardChoice, lists: Map<String, ScoutAwardLists>): List<ScoutAwardTabTitles> =
    choice.tabs.mapNotNull { tab ->
        val titles = scoutAwardTitles(ScoutAwardChoice(tab.awards, choice.winners), lists)
        when {
            titles.movies.isNotEmpty() -> ScoutAwardTabTitles(tab.titleRes, series = false, ids = titles.movies)
            titles.series.isNotEmpty() -> ScoutAwardTabTitles(tab.titleRes, series = true, ids = titles.series)
            else -> null
        }
    }

/** One way to slice the catalogue. A side left null means the slice offers only the other type. */
data class ScoutSlice(
    val id: String,
    val group: ScoutSliceGroup,
    @StringRes val titleRes: Int = 0,
    val titleArg: String? = null,
    val movie: TmdbCollectionFilters? = null,
    val tv: TmdbCollectionFilters? = null,
    val sortBy: String = POPULAR,
    val titleParts: List<Int> = emptyList(),
    val award: ScoutAwardChoice? = null
) {
    fun title(getString: (Int, Array<out Any>) -> String): String = when {
        titleParts.isNotEmpty() -> titleParts.joinToString(" \u2022 ") { getString(it, emptyArray()) }
        group == ScoutSliceGroup.COUNTRY && titleRes == 0 -> Locale("", titleArg.orEmpty()).displayCountry
        group == ScoutSliceGroup.LANGUAGE -> Locale(titleArg.orEmpty()).displayLanguage.replaceFirstChar { it.titlecase() }
        titleArg != null -> getString(titleRes, arrayOf(titleArg))
        else -> getString(titleRes, emptyArray())
    }
}

private const val POPULAR = "popularity.desc"
private const val TOP = "vote_average.desc"
private const val FEEL_GOOD_WORDS = "383896|319357|334465|325782|325784|335803|288816"
private const val LAUGH_WORDS = "320420|386408|319397"
private const val EDGE_WORDS = "314730|321464|316832|353406|288394"
private const val MIND_WORDS = "362567|9887|33465|170351|334469|3972|10854"
private const val DARK_WORDS = "259094|230747|286125|361070"
private const val SCARY_WORDS = "315058|256183|3358|12339|295907|283085|385522"
private const val DYSTOPIA = "4565"
private const val ROMANTIC_WORDS = "324429|9840"
private const val ROMANTIC_SERIES_WORDS = "324429|9840|386551|304976"
private const val EPIC_WORDS = "377433|211227|161257|161176|192772|2035|14643|234213"
private const val TEARS_WORDS = "276222|156924|370245|9872|6564|253411"
private const val SLOW_WORDS = "277551|228988|197056|343508|155800"
private const val SLOW_SERIES_WORDS = "277551|228988|343508|155800"
private const val WEIRD_WORDS = "9887|3307|210710|312814|362252"
internal const val SCOUT_NOT_DOCUMENTARY = "35"
private const val MIN_VOTES_MOVIE = 300
private const val MIN_VOTES_TV = 100
private const val NOT_SHOWS_PEOPLE_MEAN = "10763,10764,10766,10767"
const val SCOUT_GEM_MAX_VOTES = 5000
const val SCOUT_GEM_MAX_VOTES_TV = 2000

private fun quality(
    genres: String? = null,
    without: String? = null,
    keywords: String? = null,
    withoutKeywords: String? = null,
    average: Double = 6.5,
    votes: Int = MIN_VOTES_MOVIE,
    votesMax: Int? = null,
    from: String? = null,
    to: String? = null,
    country: String? = null,
    language: String? = null,
    runtimeMax: Int? = null,
    runtimeMin: Int? = null
) = TmdbCollectionFilters(
    withGenres = genres,
    withoutGenres = without,
    withKeywords = keywords,
    withoutKeywords = withoutKeywords,
    voteAverageGte = average,
    voteCountGte = votes,
    voteCountLte = votesMax,
    releaseDateGte = from,
    releaseDateLte = to,
    withOriginCountry = country,
    withOriginalLanguage = language,
    withRuntimeLte = runtimeMax,
    withRuntimeGte = runtimeMin
)

private fun both(keywords: String, average: Double = 6.3) =
    quality(keywords = keywords, average = average, votes = 100) to quality(keywords = keywords, average = average, votes = 30)

private fun slice(id: String, group: ScoutSliceGroup, res: Int, pair: Pair<TmdbCollectionFilters?, TmdbCollectionFilters?>, sort: String = POPULAR) =
    ScoutSlice(id, group, res, movie = pair.first, tv = pair.second, sortBy = sort)

private fun decade(start: Int): ScoutSlice {
    val from = "$start-01-01"
    val to = "${start + 9}-12-31"
    return ScoutSlice(
        id = "decade_$start",
        group = ScoutSliceGroup.DECADE,
        titleRes = R.string.scout_slice_decade,
        titleArg = start.toString(),
        movie = quality(from = from, to = to, average = 6.8),
        tv = quality(from = from, to = to, average = 6.8, votes = MIN_VOTES_TV)
    )
}

private fun award(id: String, res: Int, vararg awards: String, winners: Boolean = true, tabs: List<ScoutAwardTab> = emptyList()) = ScoutSlice(
    id = "award_$id",
    group = ScoutSliceGroup.ACCLAIM,
    titleRes = res,
    award = ScoutAwardChoice(awards.toList(), winners, tabs)
)

private fun tab(res: Int, vararg awards: String) = ScoutAwardTab(res, awards.toList())

private val OSCAR_TABS = listOf(
    tab(R.string.scout_award_tab_picture, "oscar_best_picture"),
    tab(R.string.scout_award_tab_director, "oscar_director"),
    tab(R.string.scout_award_tab_actor, "oscar_actor"),
    tab(R.string.scout_award_tab_actress, "oscar_actress"),
    tab(R.string.scout_genre_animation, "oscar_animated"),
    tab(R.string.scout_award_tab_international, "oscar_international"),
    tab(R.string.scout_genre_documentary, "oscar_documentary")
)

private val BAFTA_TABS = listOf(
    tab(R.string.scout_award_tab_best_film, "bafta_best_film"),
    tab(R.string.scout_award_tab_director, "bafta_director"),
    tab(R.string.scout_award_tab_actor, "bafta_actor"),
    tab(R.string.scout_award_tab_actress, "bafta_actress"),
    tab(R.string.scout_genre_animation, "bafta_animated"),
    tab(R.string.scout_award_tab_international, "bafta_international"),
    tab(R.string.scout_genre_documentary, "bafta_documentary")
)

private val GLOBE_TABS = listOf(
    tab(R.string.scout_award_tab_picture, "golden_globe_drama", "golden_globe_comedy"),
    tab(R.string.scout_award_tab_director, "globe_director"),
    tab(R.string.scout_award_tab_actor, "globe_actor_drama", "globe_actor_comedy"),
    tab(R.string.scout_award_tab_actress, "globe_actress_drama", "globe_actress_comedy"),
    tab(R.string.scout_genre_animation, "globe_animated"),
    tab(R.string.scout_award_tab_international, "globe_international")
)

private val EMMY_TABS = listOf(
    tab(R.string.scout_genre_drama, "emmy_drama"),
    tab(R.string.scout_genre_comedy, "emmy_comedy"),
    tab(R.string.scout_award_tab_limited, "emmy_limited")
)

private fun country(code: String) = ScoutSlice(
    id = "country_$code",
    group = ScoutSliceGroup.COUNTRY,
    titleArg = code,
    movie = quality(country = code, votes = 150),
    tv = quality(country = code, votes = 50)
)

private fun region(name: String, res: Int, countries: String) = ScoutSlice(
    id = "region_$name",
    group = ScoutSliceGroup.REGION,
    titleRes = res,
    movie = quality(country = countries, votes = 100),
    tv = quality(country = countries, votes = 20)
)

private fun genre(movieId: Int, res: Int, tv: String?) = ScoutSlice(
    id = "genre_$movieId",
    group = ScoutSliceGroup.GENRE,
    titleRes = res,
    movie = quality(genres = movieId.toString(), average = 6.8),
    tv = tv?.let { quality(genres = it, average = 7.0, votes = MIN_VOTES_TV) }
)

/** Series use their own ids for a few genres; Your taste counts them as one. */
fun scoutCanonicalGenre(id: Int): Int = when (id) {
    10759 -> 28
    10765 -> 878
    10768 -> 10752
    10762 -> 10751
    else -> id
}

fun scoutGenreLeaveOutIds(id: Int): Set<Int> = when (id) {
    28 -> setOf(28, 10759)
    878 -> setOf(878, 10765)
    10752 -> setOf(10752, 10768)
    else -> setOf(id)
}

private fun language(code: String) = ScoutSlice(
    id = "language_$code",
    group = ScoutSliceGroup.LANGUAGE,
    titleArg = code,
    movie = quality(language = code, votes = 150),
    tv = quality(language = code, votes = 50)
)

object ScoutSlices {
    val all: List<ScoutSlice> = listOf(
        slice("mood_feel_good", ScoutSliceGroup.MOOD, R.string.scout_slice_feel_good,
            quality(genres = "35|10751|16|10749", keywords = FEEL_GOOD_WORDS, without = "27,53,80,9648,10752", votes = 100) to quality(genres = "35|10751", keywords = FEEL_GOOD_WORDS, without = "80,9648,10759,10768", votes = 30)),
        slice("mood_laugh", ScoutSliceGroup.MOOD, R.string.scout_slice_laugh,
            quality(genres = "35", keywords = LAUGH_WORDS, without = "18,27", votes = 100) to quality(genres = "35", keywords = LAUGH_WORDS, without = "18", votes = 30)),
        slice("mood_action", ScoutSliceGroup.MOOD, R.string.scout_slice_action,
            quality(genres = "28", average = 6.8, votes = 500) to quality(genres = "10759", without = "16,10751", average = 7.2, votes = 100)),
        slice("mood_edge", ScoutSliceGroup.MOOD, R.string.scout_slice_edge,
            quality(genres = "53|80|9648", keywords = EDGE_WORDS, without = "27,35,10751,16", votes = 100) to quality(genres = "80|9648", keywords = EDGE_WORDS, without = "35,16,10751,10765", votes = 30)),
        slice("mood_mind", ScoutSliceGroup.MOOD, R.string.scout_slice_mind,
            quality(genres = "878|9648|53", keywords = MIND_WORDS, without = "27,16,10751", average = 7.0, votes = 100) to quality(genres = "10765|9648", keywords = MIND_WORDS, without = "16,10751,35,80,10759", average = 7.0, votes = 30)),
        slice("mood_dark", ScoutSliceGroup.MOOD, R.string.scout_slice_dark,
            quality(genres = "80", without = "35,10751,16,10749,878,14,12", average = 7.0, votes = 500) to quality(genres = "80|18", keywords = DARK_WORDS, without = "35,10751,16,10765", votes = 30)),
        slice("mood_scary", ScoutSliceGroup.MOOD, R.string.scout_slice_scary,
            quality(genres = "27", average = 6.3, votes = 200) to quality(keywords = SCARY_WORDS, without = "35,16,10751", withoutKeywords = DYSTOPIA, average = 7.0, votes = 30)),
        slice("mood_romantic", ScoutSliceGroup.MOOD, R.string.scout_slice_romantic,
            quality(genres = "10749", keywords = ROMANTIC_WORDS, votes = 100) to quality(keywords = ROMANTIC_SERIES_WORDS, without = "16,80,9648,10759", average = 7.0, votes = 30)),
        slice("mood_epic", ScoutSliceGroup.MOOD, R.string.scout_slice_epic,
            quality(genres = "12|14|36|10752", keywords = EPIC_WORDS, without = "16,10751,35", average = 7.2, votes = 500) to quality(genres = "10765|10768|10759", keywords = EPIC_WORDS, without = "16,35,9648,80", average = 7.5, votes = 100)),
        slice("mood_tears", ScoutSliceGroup.MOOD, R.string.scout_slice_tears,
            quality(genres = "18", keywords = TEARS_WORDS, without = "35,53,27,28,80,878,14", average = 7.0, votes = 100) to quality(genres = "18", keywords = TEARS_WORDS, without = "35,16,80,9648,10759,10768,10765", votes = 30)),
        slice("mood_slow", ScoutSliceGroup.MOOD, R.string.scout_slice_slow,
            quality(genres = "18", keywords = SLOW_WORDS, without = "28,35,16,10751", average = 7.2, votes = 100) to quality(genres = "18", keywords = SLOW_SERIES_WORDS, without = "35,16,10751,10759,10768", average = 7.0, votes = 30)),
        slice("mood_weird", ScoutSliceGroup.MOOD, R.string.scout_slice_weird,
            quality(genres = "35|878|14|18", keywords = WEIRD_WORDS, without = "27,10751,16", average = 6.8, votes = 100) to quality(keywords = WEIRD_WORDS, without = "10751,10762", average = 7.0, votes = 30)),
        decade(2020), decade(2010), decade(2000), decade(1990), decade(1980), decade(1970), decade(1960), decade(1950),
        country("US"), country("GB"), country("AU"), country("KR"), country("JP"), country("FR"), country("DE"),
        country("IT"), country("ES"), country("IN"), country("CN"), country("SE"), country("DK"), country("MX"),
        country("CA"), country("HK").copy(titleRes = R.string.scout_country_hong_kong, titleArg = null),
        region("uk_ireland", R.string.scout_region_uk_ireland, "GB|IE"),
        region("europe", R.string.scout_region_europe, "FR|DE|IT|ES|PT|NL|BE|AT|CH|SE|NO|DK|FI|IS|PL|CZ|HU|RO|GR"),
        region("nordic", R.string.scout_region_nordic, "SE|NO|DK|FI|IS"),
        region("east_asia", R.string.scout_region_east_asia, "JP|KR|CN|TW|HK"),
        region("south_asia", R.string.scout_region_south_asia, "IN|PK|BD|LK|NP|BT|MV|AF"),
        region("south_east_asia", R.string.scout_region_south_east_asia, "TH|ID|PH|MY|SG|VN"),
        region("middle_east", R.string.scout_region_middle_east, "TR|IL|IR|SA|AE|EG|LB|JO"),
        region("africa", R.string.scout_region_africa, "NG|ZA|KE|GH|EG|MA|SN"),
        region("latin_america", R.string.scout_region_latin_america, "MX|BR|AR|CO|CL|PE|CU"),
        region("australia_nz", R.string.scout_region_australia_nz, "AU|NZ"),
        language("en"), language("ko"), language("ja"), language("fr"), language("es"), language("de"), language("it"),
        language("hi"), language("zh"), language("pt"), language("ru"), language("sv"), language("da"), language("tr"),
        slice("length_90", ScoutSliceGroup.LENGTH, R.string.scout_slice_under_90, quality(runtimeMax = 90, average = 6.5) to null),
        slice("length_120", ScoutSliceGroup.LENGTH, R.string.scout_slice_under_120, quality(runtimeMax = 120, runtimeMin = 60, average = 7.0) to null),
        slice("length_long", ScoutSliceGroup.LENGTH, R.string.scout_slice_long_film, quality(runtimeMin = 150, average = 7.0) to null),
        slice("length_quick_episode", ScoutSliceGroup.LENGTH, R.string.scout_slice_quick_episode, null to quality(runtimeMax = 30, average = 7.0, votes = MIN_VOTES_TV)),
        slice("theme_true", ScoutSliceGroup.THEME, R.string.scout_slice_true_stories, both("9672")),
        slice("theme_book", ScoutSliceGroup.THEME, R.string.scout_slice_books, both("818", 7.0)),
        slice("theme_time", ScoutSliceGroup.THEME, R.string.scout_slice_time_travel, both("4379")),
        slice("theme_heist", ScoutSliceGroup.THEME, R.string.scout_slice_heist, both("10051")),
        slice("theme_survival", ScoutSliceGroup.THEME, R.string.scout_slice_survival, both("10349")),
        slice("theme_court", ScoutSliceGroup.THEME, R.string.scout_slice_courtroom, both("33519|214780")),
        slice("theme_whodunit", ScoutSliceGroup.THEME, R.string.scout_slice_whodunit, both("12570")),
        slice("theme_dystopia", ScoutSliceGroup.THEME, R.string.scout_slice_dystopia, both("4565|4458")),
        slice("theme_ai", ScoutSliceGroup.THEME, R.string.scout_slice_ai, both("310")),
        slice("theme_space", ScoutSliceGroup.THEME, R.string.scout_slice_space, both("3801")),
        slice("theme_coming_of_age", ScoutSliceGroup.THEME, R.string.scout_slice_coming_of_age, both("10683")),
        slice("theme_road", ScoutSliceGroup.THEME, R.string.scout_slice_road_trip, both("7312")),
        slice("theme_superhero", ScoutSliceGroup.THEME, R.string.scout_slice_superhero, both("9715")),
        slice("theme_zombie", ScoutSliceGroup.THEME, R.string.scout_slice_zombie, both("12377")),
        slice("theme_spy", ScoutSliceGroup.THEME, R.string.scout_slice_spy, both("470")),
        slice("theme_miniseries", ScoutSliceGroup.THEME, R.string.scout_slice_miniseries, null to quality(keywords = "11162", average = 7.2, votes = 50)),
        slice("theme_sitcom", ScoutSliceGroup.THEME, R.string.scout_slice_sitcom, null to quality(keywords = "193171", average = 7.0, votes = 50)),
        ScoutAnswers(family = true).slice().copy(id = "family_night", titleParts = listOf(R.string.scout_family_night)),
        ScoutAnswers(time = ScoutTime.COMPLETE).slice().copy(id = "complete_series", group = ScoutSliceGroup.LENGTH, titleParts = listOf(R.string.scout_complete_series)),
        genre(28, R.string.scout_genre_action, "10759"),
        genre(12, R.string.scout_genre_adventure, "10759"),
        genre(16, R.string.scout_genre_animation, "16"),
        genre(35, R.string.scout_genre_comedy, "35"),
        genre(80, R.string.scout_genre_crime, "80"),
        genre(99, R.string.scout_genre_documentary, "99").let { s ->
            s.copy(movie = s.movie?.copy(withoutGenres = SCOUT_NOT_DOCUMENTARY), tv = s.tv?.copy(withoutGenres = SCOUT_NOT_DOCUMENTARY))
        },
        genre(18, R.string.scout_genre_drama, "18"),
        genre(10751, R.string.scout_genre_family, "10751"),
        genre(14, R.string.scout_genre_fantasy, "10765"),
        genre(36, R.string.scout_genre_history, null),
        genre(27, R.string.scout_genre_horror, null),
        genre(10402, R.string.scout_genre_music, null),
        genre(9648, R.string.scout_genre_mystery, "9648"),
        genre(10749, R.string.scout_genre_romance, null),
        genre(878, R.string.scout_genre_scifi, "10765"),
        genre(53, R.string.scout_genre_thriller, "80|9648"),
        genre(10752, R.string.scout_genre_war, "10768"),
        genre(37, R.string.scout_genre_western, "37"),
        award("oscar_winners", R.string.scout_award_oscar_winners, "oscar_best_picture", tabs = OSCAR_TABS),
        award("oscar_nominees", R.string.scout_award_oscar_nominees, "oscar_best_picture", winners = false, tabs = OSCAR_TABS),
        award("palme", R.string.scout_award_palme, "palme_dor"),
        award("lion", R.string.scout_award_lion, "golden_lion"),
        award("bafta", R.string.scout_award_bafta, "bafta_best_film", tabs = BAFTA_TABS),
        award("bafta_nominees", R.string.scout_award_bafta_nominees, "bafta_best_film", winners = false, tabs = BAFTA_TABS),
        award("globes", R.string.scout_award_globes, "golden_globe_drama", "golden_globe_comedy", tabs = GLOBE_TABS),
        award("globes_nominees", R.string.scout_award_globes_nominees, "golden_globe_drama", "golden_globe_comedy", winners = false, tabs = GLOBE_TABS),
        award("emmy", R.string.scout_award_emmy, "emmy_drama", "emmy_comedy", tabs = EMMY_TABS),
        award("emmy_nominees", R.string.scout_award_emmy_nominees, "emmy_drama", "emmy_comedy", winners = false, tabs = EMMY_TABS),
        award("actor", R.string.scout_award_actor, "actor_award_cast"),
        award("criterion", R.string.scout_award_criterion, "criterion_collection"),
        slice("acclaim_top", ScoutSliceGroup.ACCLAIM, R.string.scout_slice_top_rated,
            quality(average = 8.0, votes = 5000) to quality(average = 8.3, votes = 1000), TOP),
        slice("acclaim_gems", ScoutSliceGroup.ACCLAIM, R.string.scout_row_gems,
            quality(average = 7.5, votes = 200, votesMax = SCOUT_GEM_MAX_VOTES) to quality(average = 8.0, votes = 80, votesMax = SCOUT_GEM_MAX_VOTES_TV), TOP),
        slice("acclaim_cult", ScoutSliceGroup.ACCLAIM, R.string.scout_slice_cult,
            quality(average = 7.2, votes = 1000, to = "1999-12-31") to quality(average = 7.8, votes = 200, to = "2005-12-31"), TOP),
        slice("acclaim_crowd", ScoutSliceGroup.ACCLAIM, R.string.scout_slice_crowd,
            quality(average = 7.0, votes = 10000) to quality(average = 7.5, votes = 3000), "vote_count.desc")
    ).map { slice ->
        slice.copy(tv = slice.tv?.let { f ->
            f.copy(withoutGenres = listOfNotNull(f.withoutGenres, NOT_SHOWS_PEOPLE_MEAN).joinToString(","))
        })
    }

    val homeTiles: List<String> = listOf("mood_feel_good", "mood_mind", "decade_1990", "country_KR", "length_90", "theme_true")

    fun byId(id: String): ScoutSlice? = all.firstOrNull { it.id == id } ?: ScoutAnswers.decode(id)?.slice()

    fun inGroup(group: ScoutSliceGroup): List<ScoutSlice> = all.filter { it.group == group }
}
