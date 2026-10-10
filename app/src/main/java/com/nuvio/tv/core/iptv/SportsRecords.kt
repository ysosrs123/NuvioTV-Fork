package com.nuvio.tv.core.iptv

enum class RecordShape { WIN_DRAW_LOSS, WIN_LOSS, WIN_LOSS_TIE, WIN_LOSS_OVERTIME }

data class SportsRecord(val shape: RecordShape, val values: List<Int>)

object SportsRecords {
    private val PATTERN = Regex("\\d{1,3}(-\\d{1,3}){1,2}")

    fun parse(sport: String, record: String?): SportsRecord? {
        val text = record?.trim()?.takeIf { PATTERN.matches(it) } ?: return null
        val values = text.split('-').map { it.toInt() }
        val shape = when (values.size) {
            2 -> if (sport in WIN_LOSS) RecordShape.WIN_LOSS else null
            3 -> when (sport) {
                "soccer" -> RecordShape.WIN_DRAW_LOSS
                "american-football" -> RecordShape.WIN_LOSS_TIE
                "ice-hockey" -> RecordShape.WIN_LOSS_OVERTIME
                else -> null
            }
            else -> null
        } ?: return null
        return SportsRecord(shape, values)
    }

    private val WIN_LOSS = setOf("american-football", "basketball", "baseball", "ice-hockey", "australian-football")
}
