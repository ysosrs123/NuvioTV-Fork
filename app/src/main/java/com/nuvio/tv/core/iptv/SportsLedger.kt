package com.nuvio.tv.core.iptv

import kotlin.math.pow

object TeamColours {
    const val NEUTRAL = 0xFF8A8A8E.toInt()
    const val STRIPE_CONTRAST = 3.0
    const val TEXT_CONTRAST = 4.5

    fun parse(hex: String?): Int? {
        val value = hex?.trim()?.removePrefix("#")?.takeIf { it.length == 6 } ?: return null
        return value.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
    }

    fun stripe(hex: String?, base: Int): Int {
        val colour = parse(hex) ?: return NEUTRAL
        if (contrast(colour, base) >= STRIPE_CONTRAST) return colour
        var step = 1
        while (step <= 20) {
            val lighter = blend(0xFFFFFFFF.toInt(), step / 20f, colour)
            if (contrast(lighter, base) >= STRIPE_CONTRAST) return lighter
            step++
        }
        return NEUTRAL
    }

    fun bandAlpha(colour: Int, base: Int, text: Int, cap: Float = .34f): Float {
        var alpha = (cap * 100).toInt()
        while (alpha > 0) {
            if (contrast(text, blend(colour, alpha / 100f, base)) >= TEXT_CONTRAST) return alpha / 100f
            alpha--
        }
        return 0f
    }

    fun blend(top: Int, alpha: Float, base: Int): Int {
        val a = alpha.coerceIn(0f, 1f)
        fun channel(shift: Int) = Math.round(((top shr shift) and 0xFF) * a + ((base shr shift) and 0xFF) * (1 - a)).coerceIn(0, 255)
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    fun luminance(colour: Int): Double {
        fun linear(shift: Int): Double {
            val c = ((colour shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
    }

    fun contrast(a: Int, b: Int): Double {
        val x = luminance(a)
        val y = luminance(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }
}

enum class PeriodName { QUARTER, PERIOD, FIRST_HALF, SECOND_HALF, EXTRA_TIME, OVERTIME }

object SportsLedger {
    private val COUNT_DOWN = setOf("basketball", "american-football", "ice-hockey", "australian-football")

    fun names(team: FixtureTeam): Pair<String?, String> {
        val short = team.shortName?.trim()?.takeIf(String::isNotEmpty) ?: return null to team.name
        val name = team.name.trim()
        if (name.length > short.length && name.endsWith(" $short")) return name.dropLast(short.length + 1).trim() to short
        return null to name
    }

    fun code(team: FixtureTeam): String? = team.abbreviation?.trim()?.takeIf { it.isNotEmpty() && it.length <= 4 }?.uppercase()

    fun leader(first: String?, second: String?): Int? {
        val a = first?.trim()?.toIntOrNull() ?: return null
        val b = second?.trim()?.toIntOrNull() ?: return null
        return when { a > b -> 0; b > a -> 1; else -> null }
    }

    fun countsDown(sport: String): Boolean = sport in COUNT_DOWN

    fun currentPeriod(fixture: SportsFixture, count: Int): Int? =
        if (fixture.status == FixtureStatus.LIVE && count > 0) ((fixture.period ?: count) - 1).coerceIn(0, count - 1) else null

    fun periodName(sport: String, period: Int?): PeriodName? {
        val number = period?.takeIf { it > 0 } ?: return null
        return when (sport) {
            "basketball", "american-football", "australian-football" -> if (number <= 4) PeriodName.QUARTER else PeriodName.OVERTIME
            "ice-hockey" -> if (number <= 3) PeriodName.PERIOD else PeriodName.OVERTIME
            "soccer", "rugby", "rugby-league" -> when (number) { 1 -> PeriodName.FIRST_HALF; 2 -> PeriodName.SECOND_HALF; else -> PeriodName.EXTRA_TIME }
            else -> null
        }
    }
}
