package com.nuvio.tv.data.local

/** Stable preference IDs, independent of enum names and UI/theme implementations. */
enum class PlayerControlAction(val id: String) {
    PLAY_PAUSE("play_pause"), RESTART("restart"), EPISODES("episodes"), NEXT_EPISODE("next_episode"),
    STATS("stats"), AUDIO("audio"), SUBTITLES("subtitles"), SOURCES("sources"), WATCH_PARTY("watch_party"),
    MORE("more"), SPEED("speed"), ASPECT("aspect"), EXTERNAL("external"), ENGINE("engine"), REPORT("report"),
    INFO("info")
}

enum class PlayerControlButtonStyle(val id: String) { PREVIOUS("previous"), LABELLED("labelled") }

enum class PlayerControlGroup(val id: String) { LEFT("left"), CENTRE("centre"), RIGHT("right") }

data class PlayerControlPlacement(
    val action: PlayerControlAction,
    val group: PlayerControlGroup,
    val visible: Boolean = true
)

/**
 * Ordering is the order of entries within each group. Missing newly introduced actions
 * get their defaults without overwriting existing choices. A null saved layout retains
 * the icon template for its renderer. More retains its saved menu boundary but is
 * displayed at the far right; other actions can move across that boundary.
 */
class PlayerControlLayout private constructor(private val placements: List<PlayerControlPlacement>, val style: PlayerControlButtonStyle) {
    val entries: List<PlayerControlPlacement> get() = placements.toList()

    fun withVisibility(action: PlayerControlAction, visible: Boolean): PlayerControlLayout =
        normalized(placements.map { if (it.action == action) it.copy(visible = visible) else it }, style)

    /** Moving to another group appends there, leaving other groups' order intact. */
    fun withGroup(action: PlayerControlAction, group: PlayerControlGroup): PlayerControlLayout {
        if (action == PlayerControlAction.MORE) return this
        val entry = placements.first { it.action == action }
        if (entry.group == group) return this
        return normalized(placements.filterNot { it.action == action } + entry.copy(group = group), style)
    }

    /** One step within its group, including hidden entries; edge moves are a no-op. */
    fun move(action: PlayerControlAction, delta: Int): PlayerControlLayout {
        if (action == PlayerControlAction.MORE || (delta != -1 && delta != 1)) return this
        val index = placements.indexOfFirst { it.action == action }
        val group = placements[index].group
        val siblings = placements.indices.filter { placements[it].group == group }
        val next = siblings.indexOf(index) + delta
        if (next !in siblings.indices) return this
        val target = siblings[next]
        val updated = placements.toMutableList()
        updated[index] = placements[target]; updated[target] = placements[index]
        return normalized(updated, style)
    }

    /** Saved visibility cannot make an unavailable engine/content action supported. */
    fun visibleGroups(available: Set<PlayerControlAction>): Map<PlayerControlGroup, List<PlayerControlAction>> =
        PlayerControlGroup.entries.associateWith { group ->
            placements.filter { it.group == group && it.visible && it.action in available }.map { it.action }
        }

    /** Screen order supplies deterministic cross-group navigation and recovery. */
    fun focusOrder(available: Set<PlayerControlAction>): List<PlayerControlAction> =
        visibleGroups(available).values.flatten()

    /** null means the timeline/container must own focus; never invent a hidden control. */
    fun focusFallback(previous: PlayerControlAction?, available: Set<PlayerControlAction>): PlayerControlAction? {
        val visible = focusOrder(available)
        return previous?.takeIf { it in visible }
            ?: PlayerControlAction.PLAY_PAUSE.takeIf { it in visible }
            ?: visible.firstOrNull()
    }

    fun withStyle(style: PlayerControlButtonStyle): PlayerControlLayout = if (this.style == style) this else normalized(placements, style)

    fun encode(): String = VERSION + style.id + "|" + placements.joinToString(";") { "${it.action.id},${it.group.id},${if (it.visible) 1 else 0}" }

    override fun equals(other: Any?): Boolean = other is PlayerControlLayout && placements == other.placements && style == other.style
    override fun hashCode(): Int = 31 * placements.hashCode() + style.hashCode()
    override fun toString(): String = "PlayerControlLayout(${placements.size} actions)"

    companion object {
        private const val VERSION = "v2|"
        private const val LEGACY_VERSION = "v1|"
        private const val MAX_BYTES = 4096
        private const val MAX_ENTRIES = 64
        private val defaults = PlayerControlAction.entries.map { action ->
            PlayerControlPlacement(action,
                if (action in setOf(PlayerControlAction.PLAY_PAUSE, PlayerControlAction.RESTART,
                    PlayerControlAction.EPISODES, PlayerControlAction.NEXT_EPISODE)) PlayerControlGroup.LEFT else PlayerControlGroup.RIGHT,
                visible = action != PlayerControlAction.INFO)
        }

        fun default(): PlayerControlLayout = normalized(emptyList())

        /** Display fallback only: never replaces raw profile revisions or an explicit Labels choice. */
        fun effective(saved: PlayerControlLayout?, v2: Boolean): PlayerControlLayout = saved ?: original(v2)

        /** Editor's untouched template follows the actual legacy theme action set; extra actions remain editable ghosts. */
        fun original(v2: Boolean): PlayerControlLayout {
            val shown = setOf(PlayerControlAction.PLAY_PAUSE, PlayerControlAction.RESTART, PlayerControlAction.EPISODES,
                PlayerControlAction.NEXT_EPISODE, PlayerControlAction.STATS, PlayerControlAction.AUDIO,
                PlayerControlAction.SUBTITLES, PlayerControlAction.SOURCES, PlayerControlAction.WATCH_PARTY,
                PlayerControlAction.MORE,
                PlayerControlAction.SPEED, PlayerControlAction.ASPECT, PlayerControlAction.EXTERNAL,
                PlayerControlAction.ENGINE, PlayerControlAction.REPORT)
            val entries = defaults.map { entry -> entry.copy(
                group = if (v2 && entry.action == PlayerControlAction.EPISODES) PlayerControlGroup.RIGHT else entry.group,
                visible = entry.action in shown) }
            val ordered = if (v2) entries.filter { it.action != PlayerControlAction.EPISODES }.toMutableList().apply {
                add(indexOfFirst { it.action == PlayerControlAction.WATCH_PARTY }, entries.single { it.action == PlayerControlAction.EPISODES })
            } else entries
            return normalized(ordered)
        }

        /** Accept at most one entry per action; first valid occurrence wins. */
        fun normalized(entries: List<PlayerControlPlacement>, style: PlayerControlButtonStyle = PlayerControlButtonStyle.PREVIOUS): PlayerControlLayout {
            val known = entries.take(MAX_ENTRIES).distinctBy { it.action }
            val missing = defaults.filterNot { def -> known.any { it.action == def.action } }
            // Canonical group order also makes equality independent of cross-group interleaving.
            return PlayerControlLayout(PlayerControlGroup.entries.flatMap { group -> (known + missing).filter { it.group == group } }, style)
        }

        /** Malformed/future data falls back for display without rewriting its saved bytes. */
        fun decode(value: String?): PlayerControlLayout? {
            if (value == null || value.length > MAX_BYTES) return null
            val style: PlayerControlButtonStyle
            val payload: String
            when {
                value.startsWith(LEGACY_VERSION) -> { style = PlayerControlButtonStyle.LABELLED; payload = value.removePrefix(LEGACY_VERSION) }
                value.startsWith(VERSION) -> {
                    val header = value.removePrefix(VERSION).split('|', limit = 2)
                    if (header.size != 2) return null
                    style = PlayerControlButtonStyle.entries.firstOrNull { it.id == header[0] } ?: return null
                    payload = header[1]
                }
                else -> return null
            }
            val raw = payload.split(';')
            if (raw.size > MAX_ENTRIES) return null
            val entries = raw.mapNotNull { row ->
                val parts = row.split(',')
                if (parts.size != 3) return@mapNotNull null
                val action = PlayerControlAction.entries.firstOrNull { it.id == parts[0] } ?: return@mapNotNull null
                val group = PlayerControlGroup.entries.firstOrNull { it.id == parts[1] } ?: return@mapNotNull null
                val visible = when (parts[2]) { "1" -> true; "0" -> false; else -> return@mapNotNull null }
                PlayerControlPlacement(action, group, visible)
            }
            if (entries.isEmpty()) return null
            return normalized(entries, style)
        }
    }
}

/** Raw revision and profile are kept together so a stale editor cannot overwrite a new layout. */
data class PlayerControlLayoutSnapshot internal constructor(
    val profileId: Int,
    internal val serialized: String?,
    val layout: PlayerControlLayout?
)
