package com.nuvio.tv.core.scout

import androidx.annotation.StringRes
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.ScoutMixes
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

enum class ScoutReadyTab(@StringRes val labelRes: Int) {
    DECADES(R.string.scout_premix_tab_decades),
    CLASSIC(R.string.scout_premix_tab_classic),
    MOODS(R.string.scout_premix_tab_moods),
    WORLD(R.string.scout_premix_tab_world),
    YEAR(R.string.scout_premix_tab_year)
}

/** Occasions with enough themed episodes to fill a mix; each can be played all year and leads in its own season. */
enum class ScoutOccasion(val code: String, @StringRes val nameRes: Int, @StringRes val whyRes: Int) {
    CHRISTMAS("christmas", R.string.scout_premix_christmas, R.string.scout_premix_why_christmas),
    HALLOWEEN("halloween", R.string.scout_premix_halloween, R.string.scout_premix_why_halloween),
    VALENTINES("valentines", R.string.scout_premix_valentines, R.string.scout_premix_why_valentines),
    THANKSGIVING("thanksgiving", R.string.scout_premix_thanksgiving, R.string.scout_premix_why_thanksgiving),
    EASTER("easter", R.string.scout_premix_easter, R.string.scout_premix_why_easter);

    /** The days it leads, around [today]'s year. Christmas runs on to New Year's Day. */
    fun season(year: Int): ClosedRange<LocalDate> = when (this) {
        CHRISTMAS -> LocalDate.of(year, 12, 1)..LocalDate.of(year + 1, 1, 1)
        HALLOWEEN -> LocalDate.of(year, 10, 1)..LocalDate.of(year, 10, 31)
        VALENTINES -> LocalDate.of(year, 2, 1)..LocalDate.of(year, 2, 14)
        THANKSGIVING -> LocalDate.of(year, 11, 1)..LocalDate.of(year, 11, 30)
        EASTER -> scoutEaster(year).let { it.minusDays(21)..it.plusDays(1) }
    }

    fun inSeason(today: LocalDate): Boolean =
        today in season(today.year) || (this == CHRISTMAS && today in season(today.year - 1))

    /** When its season next starts, for "Best in October". */
    fun nextStart(today: LocalDate): LocalDate {
        val now = season(today.year).start
        return if (now >= today) now else season(today.year + 1).start
    }
}

/** Easter Sunday, by the Gregorian computus. */
internal fun scoutEaster(year: Int): LocalDate {
    val a = year % 19
    val b = year / 100
    val c = year % 100
    val d = b / 4
    val e = b % 4
    val f = (b + 8) / 25
    val g = (b - f + 1) / 3
    val h = (19 * a + b - d - g + 15) % 30
    val i = c / 4
    val k = c % 4
    val l = (32 + 2 * e + 2 * i - h - k) % 7
    val m = (a + 11 * h + 22 * l) / 451
    val month = (h + l - 7 * m + 114) / 31
    val day = (h + l - 7 * m + 114) % 31 + 1
    return LocalDate.of(year, month, day)
}

/** One tile on the ready-made page. A recipe mix is filled by Scout; an occasion mix comes from the bundled list. */
data class ScoutReadyMix(
    val key: String,
    val tab: ScoutReadyTab,
    @StringRes val nameRes: Int,
    @StringRes val whyRes: Int,
    val recipe: ScoutMixRecipe? = null,
    val occasion: ScoutOccasion? = null,
    /** A wider span than the recipe's decade, for mixes that cross two. */
    val years: IntRange? = null,
    val familyOnly: Boolean = false,
    val notForFamily: Boolean = false
) {
    val id: String get() = ScoutMixes.READY_PREFIX + key
}

object ScoutReadyMixes {
    private fun decade(key: String, decade: ScoutMixDecade, name: Int, why: Int) =
        ScoutReadyMix(key, ScoutReadyTab.DECADES, name, why, ScoutMixRecipe(decade = decade))

    private fun world(from: ScoutMixFrom) =
        ScoutReadyMix("world_${from.code}", ScoutReadyTab.WORLD, from.labelRes, R.string.scout_premix_why_world, ScoutMixRecipe(from = from))

    private fun mood(key: String, feel: ScoutMixFeel, why: Int, notForFamily: Boolean = false) =
        ScoutReadyMix(key, ScoutReadyTab.MOODS, feel.labelRes, why, ScoutMixRecipe(feel = feel), notForFamily = notForFamily)

    val all: List<ScoutReadyMix> = buildList {
        add(decade("d70", ScoutMixDecade.SEVENTIES, R.string.scout_premix_70s, R.string.scout_premix_why_decade))
        add(decade("d80", ScoutMixDecade.EIGHTIES, R.string.scout_premix_80s, R.string.scout_premix_why_decade))
        add(decade("d90", ScoutMixDecade.NINETIES, R.string.scout_premix_90s, R.string.scout_premix_why_decade))
        add(decade("d00", ScoutMixDecade.NOUGHTIES, R.string.scout_premix_00s, R.string.scout_premix_why_decade))
        add(decade("d10", ScoutMixDecade.TENS, R.string.scout_premix_2010s, R.string.scout_premix_why_decade))
        add(decade("d20", ScoutMixDecade.TWENTIES, R.string.scout_premix_2020s, R.string.scout_premix_why_decade))
        add(ScoutReadyMix("sitcoms", ScoutReadyTab.CLASSIC, R.string.scout_premix_sitcoms, R.string.scout_premix_why_sitcoms, ScoutMixRecipe(kind = ScoutMixKind.SITCOMS)))
        add(
            ScoutReadyMix(
                "saturday", ScoutReadyTab.CLASSIC, R.string.scout_premix_saturday, R.string.scout_premix_why_saturday,
                ScoutMixRecipe(decade = ScoutMixDecade.EIGHTIES, kind = ScoutMixKind.CARTOONS, from = ScoutMixFrom.US),
                years = 1980..1999
            )
        )
        add(ScoutReadyMix("cops", ScoutReadyTab.CLASSIC, R.string.scout_premix_cops, R.string.scout_premix_why_cops, ScoutMixRecipe(kind = ScoutMixKind.CRIME), notForFamily = true))
        add(ScoutReadyMix("scifi", ScoutReadyTab.CLASSIC, R.string.scout_premix_scifi, R.string.scout_premix_why_scifi, ScoutMixRecipe(kind = ScoutMixKind.SCI_FI)))
        add(mood("feel_good", ScoutMixFeel.FEEL_GOOD, R.string.scout_premix_why_feel_good))
        add(mood("laugh", ScoutMixFeel.LAUGH, R.string.scout_premix_why_laugh))
        add(mood("edge", ScoutMixFeel.EDGE, R.string.scout_premix_why_edge, notForFamily = true))
        add(mood("weird", ScoutMixFeel.WEIRD, R.string.scout_premix_why_weird, notForFamily = true))
        ScoutMixFrom.entries.filter { it != ScoutMixFrom.ANYWHERE }.forEach { add(world(it)) }
        ScoutOccasion.entries.forEach { occasion ->
            add(ScoutReadyMix("year_${occasion.code}", ScoutReadyTab.YEAR, occasion.nameRes, occasion.whyRes, occasion = occasion))
        }
    }

    fun byId(id: String): ScoutReadyMix? = all.firstOrNull { it.id == id }

    /**
     * The tiles a tab shows: occasions in season lead, then the next to come. On World the countries the profile
     * watches most lead, then the viewer's own country, then the rest in their usual order.
     */
    fun tab(
        tab: ScoutReadyTab,
        family: Boolean,
        today: LocalDate,
        country: String,
        taste: Map<String, Double> = emptyMap()
    ): List<ScoutReadyMix> {
        val shown = all.filter { it.tab == tab && !(family && it.notForFamily) && !(it.familyOnly && !family) }
        return when (tab) {
            ScoutReadyTab.YEAR -> shown.sortedWith(
                compareByDescending<ScoutReadyMix> { it.occasion?.inSeason(today) == true }
                    .thenBy { it.occasion?.nextStart(today) }
            )
            ScoutReadyTab.WORLD -> {
                val own = country.uppercase()
                fun codes(mix: ScoutReadyMix) = mix.recipe?.from?.countries?.split('|').orEmpty()
                shown.sortedWith(
                    compareByDescending<ScoutReadyMix> { mix -> codes(mix).sumOf { taste[it] ?: 0.0 } }
                        .thenByDescending { mix -> own in codes(mix) }
                )
            }
            else -> shown
        }
    }

    /** The occasion that leads today, if any: the one Home shows. */
    fun inSeason(today: LocalDate): ScoutReadyMix? =
        all.firstOrNull { it.occasion?.inSeason(today) == true }
}

/** A ready-made mix is rebuilt once a week, from Monday. */
internal fun scoutReadyFresh(builtAt: Long, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): Boolean {
    if (builtAt <= 0L) return false
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(zone).toInstant().toEpochMilli()
    return builtAt >= monday
}

/** The same shuffle all week, a new one each Monday. */
internal fun scoutReadyWeekSeed(today: LocalDate = LocalDate.now()): Long =
    today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay()
