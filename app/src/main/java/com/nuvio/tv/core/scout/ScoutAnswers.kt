package com.nuvio.tv.core.scout

import androidx.annotation.StringRes
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.TmdbCollectionFilters
import java.time.LocalDate
import java.util.Locale

enum class ScoutSide { MOVIE, SERIES }

enum class ScoutWatch(val code: String, @StringRes val labelRes: Int, @StringRes val titleRes: Int) {
    ANYTHING("a", R.string.scout_watch_anything, R.string.scout_watch_anything),
    MOVIE("m", R.string.scout_watch_movie, R.string.scout_title_movies),
    SERIES("s", R.string.scout_watch_series, R.string.scout_title_series),
    ANIME("n", R.string.scout_watch_anime, R.string.scout_watch_anime),
    DOCUMENTARY("d", R.string.scout_watch_documentary, R.string.scout_title_documentaries)
}

/** Keywords mark the choices made only for anime or documentaries, which have no slice of their own. */
enum class ScoutMood(
    val code: String,
    @StringRes val labelRes: Int,
    @StringRes val whyRes: Int = 0,
    val sliceId: String? = null,
    val keywords: String? = null
) {
    FEEL_GOOD("fg", R.string.scout_slice_feel_good, R.string.scout_why_feel_good, "mood_feel_good"),
    LAUGH("la", R.string.scout_slice_laugh, R.string.scout_why_laugh, "mood_laugh"),
    ACTION("ac", R.string.scout_slice_action, R.string.scout_why_action, "mood_action"),
    EDGE("ed", R.string.scout_slice_edge, R.string.scout_why_edge, "mood_edge"),
    MIND("mi", R.string.scout_slice_mind, R.string.scout_why_mind, "mood_mind"),
    DARK("da", R.string.scout_slice_dark, R.string.scout_why_dark, "mood_dark"),
    SCARY("sc", R.string.scout_slice_scary, R.string.scout_why_scary, "mood_scary"),
    ROMANTIC("ro", R.string.scout_slice_romantic, R.string.scout_why_romantic, "mood_romantic"),
    EPIC("ep", R.string.scout_slice_epic, R.string.scout_why_epic, "mood_epic"),
    TEARS("te", R.string.scout_slice_tears, R.string.scout_why_tears, "mood_tears"),
    SLOW("sl", R.string.scout_slice_slow, R.string.scout_why_slow, "mood_slow"),
    WEIRD("we", R.string.scout_slice_weird, R.string.scout_why_weird, "mood_weird"),
    SHOUNEN("as", R.string.scout_anime_shounen, keywords = "207826"),
    ISEKAI("ai", R.string.scout_anime_isekai, keywords = "237451"),
    SLICE_OF_LIFE("al", R.string.scout_anime_slice_of_life, keywords = "9914"),
    MECHA("am", R.string.scout_anime_mecha, keywords = "10046"),
    PSYCHOLOGICAL("ap", R.string.scout_anime_psychological, keywords = "272553|12565"),
    MAGICAL_GIRL("ag", R.string.scout_anime_magical_girl, keywords = "292887"),
    SPORT_ANIME("ab", R.string.scout_topic_sport, keywords = "6075"),
    DARK_FANTASY("ad", R.string.scout_anime_dark_fantasy, keywords = "177895"),
    SAMURAI("au", R.string.scout_anime_samurai, keywords = "1462"),
    TRUE_CRIME("dc", R.string.scout_topic_true_crime, keywords = "33722"),
    NATURE("dn", R.string.scout_topic_nature, keywords = "18330|221355|9902"),
    HISTORY("dh", R.string.scout_topic_history, keywords = "282633|5565"),
    SPACE("ds", R.string.scout_topic_space, keywords = "9882|3801"),
    FOOD("df", R.string.scout_topic_food, keywords = "10637"),
    MUSIC("dm", R.string.scout_topic_music, keywords = "283297"),
    SPORT("dp", R.string.scout_topic_sport, keywords = "333328"),
    WAR("dw", R.string.scout_topic_war, keywords = "273967"),
    SCIENCE("dx", R.string.scout_topic_science, keywords = "287067|1576"),
    TRAVEL("dt", R.string.scout_topic_travel, keywords = "9935"),
    ART("dr", R.string.scout_topic_art, keywords = "14893"),
    POLITICS("dl", R.string.scout_topic_politics, keywords = "6078"),
    MAGICAL("fm", R.string.scout_family_magical, keywords = "2343");

    companion object {
        private val GENERAL = listOf(FEEL_GOOD, LAUGH, ACTION, EDGE, MIND, DARK, SCARY, ROMANTIC, EPIC, TEARS, SLOW, WEIRD)
        private val FAMILY = listOf(FEEL_GOOD, LAUGH, ACTION, EDGE, MIND, DARK, MAGICAL, ROMANTIC, EPIC, TEARS, SLOW, WEIRD)
        private val ANIME = listOf(
            FEEL_GOOD, LAUGH, SHOUNEN, ISEKAI, SLICE_OF_LIFE, MECHA, PSYCHOLOGICAL, ROMANTIC, MAGICAL_GIRL, SPORT_ANIME, DARK_FANTASY, SAMURAI
        )
        private val TOPICS = listOf(TRUE_CRIME, NATURE, HISTORY, SPACE, FOOD, MUSIC, SPORT, WAR, SCIENCE, TRAVEL, ART, POLITICS)

        fun choicesFor(watch: ScoutWatch, family: Boolean = false): List<ScoutMood> = when (watch) {
            ScoutWatch.ANIME -> ANIME
            ScoutWatch.DOCUMENTARY -> TOPICS
            else -> if (family) FAMILY else GENERAL
        }
    }
}

enum class ScoutTime(val code: String, @StringRes val labelRes: Int, @StringRes val titleRes: Int, val only: ScoutSide? = null) {
    ANY("a", R.string.scout_time_any, R.string.scout_time_any),
    SHORT("s", R.string.scout_time_short, R.string.scout_time_short),
    LONG("l", R.string.scout_time_long, R.string.scout_time_long),
    UNDER_90("u", R.string.scout_time_under_90, R.string.scout_slice_under_90, ScoutSide.MOVIE),
    UNDER_120("v", R.string.scout_slice_under_120, R.string.scout_slice_under_120, ScoutSide.MOVIE),
    LONG_FILM("g", R.string.scout_time_long_film, R.string.scout_slice_long_film, ScoutSide.MOVIE),
    QUICK_EPISODES("q", R.string.scout_slice_quick_episode, R.string.scout_slice_quick_episode, ScoutSide.SERIES),
    ABOUT_AN_HOUR("h", R.string.scout_time_hour, R.string.scout_time_hour, ScoutSide.SERIES),
    WEEKEND("w", R.string.scout_time_weekend, R.string.scout_time_weekend, ScoutSide.SERIES),
    COMPLETE("c", R.string.scout_complete_series, R.string.scout_complete_series, ScoutSide.SERIES),
    ANIME_SERIES("n", R.string.scout_time_anime_series, R.string.scout_time_anime_series, ScoutSide.SERIES),
    ANIME_FILM("f", R.string.scout_time_anime_film, R.string.scout_time_anime_film, ScoutSide.MOVIE),
    SHORT_SERIES("r", R.string.scout_time_short_series, R.string.scout_time_short_series, ScoutSide.SERIES),
    FEATURE("t", R.string.scout_time_feature, R.string.scout_time_feature, ScoutSide.MOVIE),
    DOC_SERIES("d", R.string.scout_time_doc_series, R.string.scout_time_doc_series, ScoutSide.SERIES),
    MINISERIES("m", R.string.scout_slice_miniseries, R.string.scout_slice_miniseries, ScoutSide.SERIES);

    companion object {
        fun choicesFor(watch: ScoutWatch): List<ScoutTime> = when (watch) {
            ScoutWatch.ANYTHING -> listOf(ANY, SHORT, LONG)
            ScoutWatch.MOVIE -> listOf(ANY, UNDER_90, UNDER_120, LONG_FILM)
            ScoutWatch.SERIES -> listOf(ANY, QUICK_EPISODES, ABOUT_AN_HOUR, WEEKEND, COMPLETE)
            ScoutWatch.ANIME -> listOf(ANY, ANIME_SERIES, ANIME_FILM, SHORT_SERIES, COMPLETE)
            ScoutWatch.DOCUMENTARY -> listOf(ANY, UNDER_90, FEATURE, DOC_SERIES, MINISERIES)
        }
    }
}

enum class ScoutTune(val code: String, @StringRes val labelRes: Int) {
    LIGHTER("l", R.string.scout_tune_lighter),
    DARKER("r", R.string.scout_tune_darker),
    NEWER("n", R.string.scout_tune_newer),
    OLDER("o", R.string.scout_tune_older),
    BETTER_RATED("b", R.string.scout_tune_better_rated),
    HIDDEN_GEMS("k", R.string.scout_row_gems),
    INTERNATIONAL("a", R.string.scout_tune_international),
    FROM_COUNTRY("c", R.string.scout_tune_from_country_row)
}

private const val SERIES_PEOPLE_MEAN = "10763,10764,10766,10767"
private const val HORROR = "27"
private const val LIGHTER_MOVIE = "27,53,80,10752"
private const val LIGHTER_SERIES = "80,9648,10768"
private const val DARKER_MOVIE = "53|80|27"
private const val DARKER_SERIES = "80|9648"
private const val NOT_DARK_GENRES = "35,10751,16"
private const val ANIMATION = "16"
private const val DOCUMENTARY = "99"
private const val JAPAN = "JP"
private const val ENDED = "3"
private const val MINISERIES = "2"
private const val INTERNATIONAL_COUNTRIES = "GB|AU|CA|IE|NZ|FR|DE|IT|ES|SE|DK|NO|NL|BE|KR|JP|IN|MX|BR|CN|HK|TR"
private const val NEWER_YEARS = 5L
private const val OLDER_YEARS = 20L
private const val ANSWERS_PREFIX = "q"

/** The rating that counts as PG, only where TMDB's list for that country was checked to be sensible. */
private val FAMILY_FILM = mapOf("US" to "PG", "AU" to "PG", "GB" to "PG", "DE" to "6", "NL" to "9", "IE" to "PG")
private val FAMILY_SERIES = mapOf("US" to "TV-PG", "AU" to "PG", "GB" to "PG", "DE" to "6", "NL" to "9")

/** Co-productions carry a country's flag too; requiring the language keeps its own films, where it has one main language. */
private val COUNTRY_LANGUAGE = mapOf(
    "FR" to "fr", "DE" to "de", "IT" to "it", "ES" to "es", "JP" to "ja", "KR" to "ko", "BR" to "pt", "PT" to "pt",
    "TR" to "tr", "SE" to "sv", "NO" to "no", "DK" to "da", "FI" to "fi", "NL" to "nl", "PL" to "pl", "RU" to "ru",
    "GR" to "el", "TH" to "th", "ID" to "id", "CN" to "zh"
)

internal fun scoutFamilyRating(side: ScoutSide, country: String): Pair<String, String> {
    val table = if (side == ScoutSide.MOVIE) FAMILY_FILM else FAMILY_SERIES
    val code = country.uppercase(Locale.ROOT)
    return table[code]?.let { code to it } ?: ("US" to table.getValue("US"))
}

/** A box's own country has few votes on TMDB if it is small, so its list has lower limits than the rest. */
internal fun TmdbCollectionFilters.scoutFromCountry(country: String, movie: Boolean): TmdbCollectionFilters {
    val code = country.uppercase(Locale.ROOT)
    return copy(
        withOriginCountry = code,
        withOriginalLanguage = COUNTRY_LANGUAGE[code] ?: withOriginalLanguage,
        voteCountGte = minOf(voteCountGte ?: Int.MAX_VALUE, if (movie) 20 else 5),
        voteAverageGte = minOf(voteAverageGte ?: 6.0, 6.0)
    )
}

data class ScoutAnswers(
    val watch: ScoutWatch = ScoutWatch.ANYTHING,
    val mood: ScoutMood? = null,
    val time: ScoutTime = ScoutTime.ANY,
    val family: Boolean = false,
    val tunes: Set<ScoutTune> = emptySet()
) {
    val isUnset: Boolean get() = this == ScoutAnswers()

    val offersFromCountry: Boolean get() = watch != ScoutWatch.ANIME

    /** Pressing the chip that is on takes it back. A type keeps only the answers it offers too. */
    fun pressWatch(next: ScoutWatch): ScoutAnswers {
        val chosen = if (watch == next) ScoutWatch.ANYTHING else next
        return copy(
            watch = chosen,
            mood = mood?.takeIf { it in ScoutMood.choicesFor(chosen, family) },
            time = time.takeIf { it in ScoutTime.choicesFor(chosen) } ?: ScoutTime.ANY,
            tunes = emptySet()
        )
    }

    fun pressMood(next: ScoutMood): ScoutAnswers = copy(mood = if (mood == next) null else next, tunes = emptySet())

    fun pressTime(next: ScoutTime): ScoutAnswers = copy(time = if (time == next) ScoutTime.ANY else next, tunes = emptySet())

    fun pressFamily(on: Boolean): ScoutAnswers =
        copy(family = on, mood = mood?.takeIf { it in ScoutMood.choicesFor(watch, on) }, tunes = emptySet())

    fun pressTune(tune: ScoutTune): ScoutAnswers {
        val without = when (tune) {
            ScoutTune.NEWER -> tunes - ScoutTune.OLDER
            ScoutTune.OLDER -> tunes - ScoutTune.NEWER
            ScoutTune.LIGHTER -> tunes - ScoutTune.DARKER
            ScoutTune.DARKER -> tunes - ScoutTune.LIGHTER
            ScoutTune.INTERNATIONAL -> tunes - ScoutTune.FROM_COUNTRY
            ScoutTune.FROM_COUNTRY -> tunes - ScoutTune.INTERNATIONAL
            else -> tunes
        }
        return copy(tunes = if (tune in tunes) tunes - tune else without + tune)
    }

    fun encode(): String = listOf(
        ANSWERS_PREFIX,
        watch.code,
        mood?.code ?: "x",
        time.code,
        if (family) "f" else "x",
        tunes.map { it.code }.sorted().joinToString("")
    ).joinToString(".")

    fun titleParts(): List<Int> = buildList {
        mood?.let { add(it.labelRes) }
        if (watch != ScoutWatch.ANYTHING) add(watch.titleRes)
        if (time != ScoutTime.ANY) add(time.titleRes)
        if (family) add(R.string.scout_family_night)
        ScoutTune.entries.filter { it in tunes }.forEach { add(it.labelRes) }
        if (isEmpty()) add(watch.titleRes)
    }

    fun slice(today: LocalDate = LocalDate.now(), country: String = Locale.getDefault().country): ScoutSlice {
        val base = mood?.sliceId?.let { ScoutSlices.byId(it) }
        val movie = if (watch == ScoutWatch.SERIES || time.only == ScoutSide.SERIES) null
        else side(ScoutSide.MOVIE, base?.movie ?: TmdbCollectionFilters(voteAverageGte = 6.8, voteCountGte = 300), today, country)
        val tv = if (watch == ScoutWatch.MOVIE || time.only == ScoutSide.MOVIE) null
        else side(ScoutSide.SERIES, base?.tv ?: TmdbCollectionFilters(voteAverageGte = 7.0, voteCountGte = 100), today, country)
        return ScoutSlice(
            id = encode(),
            group = ScoutSliceGroup.MOOD,
            movie = movie,
            tv = tv,
            titleParts = titleParts()
        )
    }

    private fun side(side: ScoutSide, start: TmdbCollectionFilters, today: LocalDate, country: String): TmdbCollectionFilters {
        val movie = side == ScoutSide.MOVIE
        var f = start
        fun votes(atMost: Int) { f = f.copy(voteCountGte = minOf(f.voteCountGte ?: atMost, atMost)) }
        fun average(atMost: Double) { f = f.copy(voteAverageGte = minOf(f.voteAverageGte ?: atMost, atMost)) }
        fun leaveOut(genres: String) {
            val wanted = f.withGenres.orEmpty().split(',', '|').toSet()
            val merged = (f.withoutGenres.orEmpty().split(',') + genres.split(','))
                .filter { it.isNotBlank() && it !in wanted }.distinct()
            f = f.copy(withoutGenres = merged.joinToString(",").ifBlank { null })
        }
        when (watch) {
            ScoutWatch.ANIME -> {
                f = f.copy(
                    withGenres = scoutAllGenres(ANIMATION, f.withGenres),
                    withoutGenres = f.withoutGenres?.split(',')?.filter { it != ANIMATION }?.joinToString(",")?.ifBlank { null },
                    withOriginCountry = JAPAN,
                    withKeywords = mood?.keywords ?: f.withKeywords?.takeIf { start.withGenres == null }
                )
                if (mood?.keywords != null) { votes(if (movie) 10 else 5); average(6.0) } else votes(if (movie) 100 else 50)
            }
            ScoutWatch.DOCUMENTARY -> {
                f = f.copy(withGenres = DOCUMENTARY, withoutGenres = SCOUT_NOT_DOCUMENTARY, withKeywords = mood?.keywords, withoutKeywords = null)
                if (mood != null) { votes(if (movie) 20 else 5); average(6.5) } else votes(if (movie) 100 else 30)
            }
            else -> if (mood?.keywords != null) {
                f = f.copy(withKeywords = mood.keywords)
                votes(if (movie) 50 else 10)
                average(6.0)
            }
        }
        when (time) {
            ScoutTime.SHORT -> f = f.copy(withRuntimeLte = if (movie) 90 else 30)
            ScoutTime.LONG -> f = f.copy(withRuntimeGte = if (movie) 150 else 40)
            ScoutTime.UNDER_90 -> f = f.copy(withRuntimeLte = 90)
            ScoutTime.UNDER_120 -> f = f.copy(withRuntimeLte = 120, withRuntimeGte = 60)
            ScoutTime.LONG_FILM -> f = f.copy(withRuntimeGte = 150)
            ScoutTime.QUICK_EPISODES -> f = f.copy(withRuntimeLte = 30)
            ScoutTime.ABOUT_AN_HOUR -> f = f.copy(withRuntimeGte = 40, withRuntimeLte = 70)
            ScoutTime.WEEKEND, ScoutTime.MINISERIES -> { f = f.copy(withType = MINISERIES); votes(50) }
            ScoutTime.SHORT_SERIES -> { f = f.copy(withType = MINISERIES); votes(10); average(6.0) }
            ScoutTime.COMPLETE -> f = f.copy(withStatus = ENDED)
            ScoutTime.FEATURE -> f = f.copy(withRuntimeGte = 70)
            ScoutTime.ANY, ScoutTime.ANIME_SERIES, ScoutTime.ANIME_FILM, ScoutTime.DOC_SERIES -> Unit
        }
        if (family) {
            val (ratingCountry, rating) = scoutFamilyRating(side, country)
            f = f.copy(certificationCountry = ratingCountry, certificationLte = rating)
            votes(if (movie) 200 else 50)
            average(if (movie) 6.3 else 6.5)
            leaveOut(HORROR)
        }
        if (ScoutTune.LIGHTER in tunes) leaveOut(if (movie) LIGHTER_MOVIE else LIGHTER_SERIES)
        if (ScoutTune.DARKER in tunes) {
            if (f.withGenres == null) {
                val leftOut = f.withoutGenres.orEmpty().split(',')
                f = f.copy(withGenres = (if (movie) DARKER_MOVIE else DARKER_SERIES).split('|').filter { it !in leftOut }.joinToString("|").ifBlank { null })
            }
            if (start.withGenres != null || watch == ScoutWatch.ANIME || watch == ScoutWatch.DOCUMENTARY) leaveOut(NOT_DARK_GENRES)
        }
        if (ScoutTune.NEWER in tunes) f = f.copy(releaseDateGte = today.minusYears(NEWER_YEARS).toString())
        if (ScoutTune.OLDER in tunes) f = f.copy(releaseDateLte = today.minusYears(OLDER_YEARS).toString())
        if (ScoutTune.BETTER_RATED in tunes) f = f.copy(voteAverageGte = maxOf(f.voteAverageGte ?: 0.0, 7.8))
        if (ScoutTune.HIDDEN_GEMS in tunes) {
            f = f.copy(voteCountLte = if (movie) SCOUT_GEM_MAX_VOTES else SCOUT_GEM_MAX_VOTES_TV, voteAverageGte = maxOf(f.voteAverageGte ?: 0.0, 7.5))
            votes(if (movie) 100 else 30)
        }
        if (ScoutTune.INTERNATIONAL in tunes && f.withOriginCountry == null) f = f.copy(withOriginCountry = INTERNATIONAL_COUNTRIES)
        if (ScoutTune.FROM_COUNTRY in tunes && offersFromCountry && country.isNotBlank()) f = f.scoutFromCountry(country, movie)
        if (!movie) leaveOut(SERIES_PEOPLE_MEAN)
        return f
    }

    companion object {
        fun decode(id: String): ScoutAnswers? {
            val parts = id.split('.')
            if (parts.size != 6 || parts[0] != ANSWERS_PREFIX) return null
            val watch = ScoutWatch.entries.firstOrNull { it.code == parts[1] } ?: return null
            val mood = if (parts[2] == "x") null else ScoutMood.entries.firstOrNull { it.code == parts[2] } ?: return null
            val time = ScoutTime.entries.firstOrNull { it.code == parts[3] } ?: return null
            val tunes = parts[5].map { c -> ScoutTune.entries.firstOrNull { it.code == c.toString() } ?: return null }.toSet()
            return ScoutAnswers(watch, mood, time, parts[4] == "f", tunes)
        }
    }
}

/** TMDB cannot group "this and (that or that)", so a choice of genres narrows to its first one. */
internal fun scoutAllGenres(required: String, others: String?): String {
    val rest = others.orEmpty().split(',').filter { it.isNotBlank() }.map { it.substringBefore('|') }
    return (listOf(required) + rest).distinct().joinToString(",")
}

/** When answers leave too little, the thresholds give way before the answers do. */
internal fun TmdbCollectionFilters.scoutRelaxed(): TmdbCollectionFilters = copy(
    voteAverageGte = voteAverageGte?.let { minOf(it, 6.0) },
    voteCountGte = voteCountGte?.let { maxOf(10, it / 5) }
)
