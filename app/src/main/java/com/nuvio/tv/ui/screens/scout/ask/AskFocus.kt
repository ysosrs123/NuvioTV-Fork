package com.nuvio.tv.ui.screens.scout.ask

internal const val ASK_STARTERS = 4

internal sealed interface AskSpot {
    data object Mic : AskSpot
    data object Field : AskSpot
    data object ChangeAi : AskSpot
    data class Starter(val index: Int) : AskSpot
    data object Clear : AskSpot
    data class Asked(val index: Int) : AskSpot
}

internal enum class AskMove { UP, DOWN, START, END }

/** Every move on the Ask page, written out so that no press can bounce sideways. Null means stay put. */
internal fun askFocusNext(from: AskSpot, move: AskMove, hasMic: Boolean, asked: Int): AskSpot? {
    val starterAbove = { index: Int ->
        when {
            index == ASK_STARTERS - 1 -> AskSpot.ChangeAi
            index == 0 && hasMic -> AskSpot.Mic
            else -> AskSpot.Field
        }
    }
    return when (from) {
        AskSpot.Mic -> when (move) {
            AskMove.END -> AskSpot.Field
            AskMove.DOWN -> AskSpot.Starter(0)
            else -> null
        }
        AskSpot.Field -> when (move) {
            AskMove.START -> if (hasMic) AskSpot.Mic else null
            AskMove.END -> AskSpot.ChangeAi
            AskMove.DOWN -> AskSpot.Starter(if (hasMic) 1 else 0)
            AskMove.UP -> null
        }
        AskSpot.ChangeAi -> when (move) {
            AskMove.START -> AskSpot.Field
            AskMove.DOWN -> AskSpot.Starter(ASK_STARTERS - 1)
            else -> null
        }
        is AskSpot.Starter -> when (move) {
            AskMove.UP -> starterAbove(from.index)
            AskMove.DOWN -> if (asked > 0) {
                if (from.index == 0) AskSpot.Clear else AskSpot.Asked(minOf(from.index, asked - 1))
            } else null
            AskMove.START -> if (from.index > 0) AskSpot.Starter(from.index - 1) else null
            AskMove.END -> if (from.index < ASK_STARTERS - 1) AskSpot.Starter(from.index + 1) else null
        }
        AskSpot.Clear -> when (move) {
            AskMove.UP -> AskSpot.Starter(0)
            AskMove.DOWN -> if (asked > 0) AskSpot.Asked(0) else null
            else -> null
        }
        is AskSpot.Asked -> when (move) {
            AskMove.UP -> if (from.index == 0) AskSpot.Clear else AskSpot.Starter(minOf(from.index, ASK_STARTERS - 1))
            AskMove.DOWN -> null
            AskMove.START -> if (from.index > 0) AskSpot.Asked(from.index - 1) else null
            AskMove.END -> if (from.index < asked - 1) AskSpot.Asked(from.index + 1) else null
        }
    }
}

/** Where focus goes once a card is gone. Null when another card moved into its place, or Clear is still there. */
internal fun askSpotAfterRemoval(index: Int, remaining: Int, home: AskSpot): AskSpot? = when {
    remaining == 0 -> home
    index >= remaining -> AskSpot.Asked(remaining - 1)
    else -> null
}
