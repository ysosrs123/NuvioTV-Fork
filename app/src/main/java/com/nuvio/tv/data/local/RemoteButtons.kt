package com.nuvio.tv.data.local

import android.view.KeyEvent

enum class RemoteButtonJob(val id: String) {
    NOTHING("nothing"),
    STATS("stats"),
    INFO("info"),
    SHOW_DETAILS("show_details"),
    SOURCES("sources"),
    EPISODES("episodes"),
    AUDIO("audio"),
    NEXT_AUDIO("next_audio"),
    NIGHT_MODE("night_mode"),
    SUBTITLES("subtitles"),
    SUBTITLES_TOGGLE("subtitles_toggle"),
    SUBTITLES_UP("subtitles_up"),
    SUBTITLES_DOWN("subtitles_down"),
    PLAY_PAUSE("play_pause"),
    SEEK_FORWARD("seek_forward"),
    SEEK_BACK("seek_back"),
    SKIP_FORWARD("skip_forward"),
    SKIP_BACK("skip_back"),
    JUMP_TO_PERCENT("jump_percent"),
    CHAPTERS_LIST("chapters_list"),
    NEXT_CHAPTER("next_chapter"),
    PREVIOUS_CHAPTER("previous_chapter"),
    SKIP_INTRO_OR_NEXT("skip_intro_or_next"),
    NEXT_EPISODE("next_episode"),
    RESTART("restart"),
    SPEED("speed"),
    ASPECT("aspect"),
    WATCH_PARTY("watch_party"),
    EXTERNAL("external"),
    REPORT_MARK("report_mark"),
    LEAVE("leave");

    val needsSkipSize: Boolean get() = this == SKIP_FORWARD || this == SKIP_BACK

    /** Jobs that deal with the pause overlay themselves instead of closing it first. */
    val keepsPauseOverlay: Boolean
        get() = this == NOTHING || this == PLAY_PAUSE || this == LEAVE || this == SHOW_DETAILS

    companion object {
        fun fromId(id: String): RemoteButtonJob? = entries.firstOrNull { it.id == id }
    }
}

data class RemoteButtonBinding(val job: RemoteButtonJob, val skipMs: Long = 0L) {
    companion object {
        val NOTHING = RemoteButtonBinding(RemoteButtonJob.NOTHING)
    }
}

/** A button runs one job on a press and may run another when it is held. */
enum class RemotePressKind { PRESS, HOLD }

/**
 * [HIDDEN_CONTROLS] keys (Up and Down) take a job only while the player's controls are hidden; with the controls
 * showing they move the focus as always.
 */
enum class RemoteKeyKind { ASSIGNABLE, HIDDEN_CONTROLS, LOCKED, VOLUME, IGNORED }

/** The jobs the player takes a key over for. A key with a hold job runs its press job when it is let go. */
data class RemoteKeyJobs(val press: RemoteButtonBinding?, val hold: RemoteButtonBinding?)

/**
 * Which job each remote key does while a video plays. Keys without an entry keep whatever the
 * player already does with them. The number keys share one switch unless a single number is
 * given its own job.
 */
class RemoteButtonMap private constructor(
    private val custom: Map<Int, RemoteButtonBinding>,
    private val holds: Map<Int, RemoteButtonBinding>,
    val numberKeysJump: Boolean
) {
    fun bindingFor(keyCode: Int): RemoteButtonBinding? {
        custom[keyCode]?.let { return it }
        if (isNumberKey(keyCode)) {
            return if (numberKeysJump) RemoteButtonBinding(RemoteButtonJob.JUMP_TO_PERCENT) else null
        }
        return DEFAULTS[keyCode]
    }

    fun holdFor(keyCode: Int): RemoteButtonBinding? = holds[keyCode]

    fun bindingFor(keyCode: Int, kind: RemotePressKind): RemoteButtonBinding? =
        if (kind == RemotePressKind.HOLD) holdFor(keyCode) else bindingFor(keyCode)

    /**
     * What the player runs for a key instead of its own handling, or null to leave the key alone.
     * A key the player handles itself is only taken once its press job changed or it has a hold job.
     */
    fun takeOver(keyCode: Int, controlsHidden: Boolean = true): RemoteKeyJobs? {
        when (kindOf(keyCode)) {
            RemoteKeyKind.ASSIGNABLE -> Unit
            RemoteKeyKind.HIDDEN_CONTROLS -> if (!controlsHidden) return null
            else -> return null
        }
        val hold = holds[keyCode]
        val press = bindingFor(keyCode)
        if (hold == null) {
            if (press == null) return null
            if (keyCode in PLAYER_KEYS && press == DEFAULTS[keyCode]) return null
        }
        return RemoteKeyJobs(press, hold)
    }

    /** The press job to run instead of the player's own handling, or null to leave the key alone. */
    fun overrideFor(keyCode: Int, controlsHidden: Boolean = true): RemoteButtonBinding? =
        takeOver(keyCode, controlsHidden)?.press

    /** Keys shown on the settings page: the starting set, then any button set up on this box. */
    val rowKeys: List<Int>
        get() = DEFAULT_ROW_KEYS + (custom.keys + holds.keys).filter { it !in DEFAULT_ROW_KEYS }

    fun isCustomKey(keyCode: Int): Boolean = keyCode !in DEFAULT_ROW_KEYS

    /** Up and Down set to do nothing on a press keep opening the controls, as they do with no job at all. */
    fun with(keyCode: Int, binding: RemoteButtonBinding): RemoteButtonMap {
        val starting = if (isNumberKey(keyCode)) null else DEFAULTS[keyCode]
        val usual = (binding == starting && keyCode in DEFAULT_ROW_KEYS) ||
            (binding.job == RemoteButtonJob.NOTHING && isHiddenControlsKey(keyCode))
        val next = if (usual) custom - keyCode else custom + (keyCode to binding)
        return RemoteButtonMap(next, holds, numberKeysJump)
    }

    /** A hold that does nothing is no hold: the press job runs straight away again. */
    fun withHold(keyCode: Int, binding: RemoteButtonBinding): RemoteButtonMap {
        val next = if (binding.job == RemoteButtonJob.NOTHING) holds - keyCode else holds + (keyCode to binding)
        return RemoteButtonMap(custom, next, numberKeysJump)
    }

    fun with(keyCode: Int, kind: RemotePressKind, binding: RemoteButtonBinding): RemoteButtonMap =
        if (kind == RemotePressKind.HOLD) withHold(keyCode, binding) else with(keyCode, binding)

    fun without(keyCode: Int): RemoteButtonMap = RemoteButtonMap(custom - keyCode, holds - keyCode, numberKeysJump)

    fun withNumberKeysJump(enabled: Boolean): RemoteButtonMap = RemoteButtonMap(custom, holds, enabled)

    fun encode(): String = buildString {
        append("digits=").append(if (numberKeysJump) "1" else "0")
        custom.forEach { (key, binding) -> appendEntry(key.toString(), binding) }
        holds.forEach { (key, binding) -> appendEntry("$key$HOLD_SUFFIX", binding) }
    }

    private fun StringBuilder.appendEntry(key: String, binding: RemoteButtonBinding) {
        append(',').append(key).append('=').append(binding.job.id)
        if (binding.job.needsSkipSize) append('@').append(binding.skipMs)
    }

    override fun equals(other: Any?): Boolean =
        other is RemoteButtonMap && other.custom == custom && other.holds == holds && other.numberKeysJump == numberKeysJump

    override fun hashCode(): Int = (custom.hashCode() * 31 + holds.hashCode()) * 31 + numberKeysJump.hashCode()

    companion object {
        const val DEFAULT_SKIP_MS = 300_000L
        val SKIP_SIZES_MS = listOf(10_000L, 30_000L, 60_000L, 300_000L, 600_000L)

        private const val HOLD_SUFFIX = 'h'

        val NUMBER_KEYS: Set<Int> = ((KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) +
            (KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9)).toSet()

        /** Stands for the number keys as one row on the settings page. */
        const val NUMBER_KEYS_ROW = -1

        private val HIDDEN_CONTROLS_KEYS = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
        private val LOCKED_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_SPACE
        )
        private val VOLUME_KEYS = setOf(
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_MUTE
        )
        private val IGNORED_KEYS = setOf(
            KeyEvent.KEYCODE_UNKNOWN, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP, KeyEvent.KEYCODE_SOFT_SLEEP,
            KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.KEYCODE_CTRL_RIGHT, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT, KeyEvent.KEYCODE_FUNCTION,
            KeyEvent.KEYCODE_SYM, KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.KEYCODE_NUM_LOCK
        )

        /** Separate Play and Pause keys stay as they are; learning one opens the Play/Pause row. */
        private val SEPARATE_PLAY_KEYS = setOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE)

        /** Keys the player already handles; they only change when someone picks another job. */
        private val PLAYER_KEYS = setOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_CAPTIONS
        )

        private fun skip(job: RemoteButtonJob) = RemoteButtonBinding(job, DEFAULT_SKIP_MS)

        val DEFAULTS: Map<Int, RemoteButtonBinding> = linkedMapOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to RemoteButtonBinding(RemoteButtonJob.PLAY_PAUSE),
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD to RemoteButtonBinding(RemoteButtonJob.SEEK_FORWARD),
            KeyEvent.KEYCODE_MEDIA_REWIND to RemoteButtonBinding(RemoteButtonJob.SEEK_BACK),
            KeyEvent.KEYCODE_MEDIA_STOP to RemoteButtonBinding(RemoteButtonJob.PLAY_PAUSE),
            KeyEvent.KEYCODE_MENU to RemoteButtonBinding(RemoteButtonJob.STATS),
            KeyEvent.KEYCODE_INFO to RemoteButtonBinding(RemoteButtonJob.INFO),
            KeyEvent.KEYCODE_MEDIA_NEXT to RemoteButtonBinding(RemoteButtonJob.SKIP_INTRO_OR_NEXT),
            KeyEvent.KEYCODE_MEDIA_PREVIOUS to RemoteButtonBinding(RemoteButtonJob.RESTART),
            KeyEvent.KEYCODE_CHANNEL_UP to skip(RemoteButtonJob.SKIP_FORWARD),
            KeyEvent.KEYCODE_CHANNEL_DOWN to skip(RemoteButtonJob.SKIP_BACK),
            KeyEvent.KEYCODE_PAGE_UP to skip(RemoteButtonJob.SKIP_FORWARD),
            KeyEvent.KEYCODE_PAGE_DOWN to skip(RemoteButtonJob.SKIP_BACK),
            KeyEvent.KEYCODE_PROG_RED to RemoteButtonBinding(RemoteButtonJob.SOURCES),
            KeyEvent.KEYCODE_PROG_GREEN to RemoteButtonBinding(RemoteButtonJob.SUBTITLES),
            KeyEvent.KEYCODE_PROG_YELLOW to RemoteButtonBinding(RemoteButtonJob.AUDIO),
            KeyEvent.KEYCODE_PROG_BLUE to RemoteButtonBinding(RemoteButtonJob.STATS),
            KeyEvent.KEYCODE_MUSIC to RemoteButtonBinding(RemoteButtonJob.NEXT_AUDIO),
            KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK to RemoteButtonBinding(RemoteButtonJob.NEXT_AUDIO),
            KeyEvent.KEYCODE_CAPTIONS to RemoteButtonBinding(RemoteButtonJob.SUBTITLES),
            KeyEvent.KEYCODE_GUIDE to RemoteButtonBinding(RemoteButtonJob.EPISODES)
        )

        val DEFAULT_ROW_KEYS: List<Int> = DEFAULTS.keys.toList().let { keys ->
            val beforeNumbers = keys.indexOf(KeyEvent.KEYCODE_PROG_BLUE) + 1
            keys.take(beforeNumbers) + NUMBER_KEYS_ROW + keys.drop(beforeNumbers)
        }

        val DEFAULT = RemoteButtonMap(emptyMap(), emptyMap(), numberKeysJump = true)

        fun isNumberKey(keyCode: Int): Boolean = keyCode in NUMBER_KEYS

        fun isHiddenControlsKey(keyCode: Int): Boolean = keyCode in HIDDEN_CONTROLS_KEYS

        fun numberOf(keyCode: Int): Int? = when (keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
            else -> null
        }

        /** The settings row a learned key belongs to. */
        fun learnTarget(keyCode: Int): Int =
            if (keyCode in SEPARATE_PLAY_KEYS) KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE else keyCode

        fun kindOf(keyCode: Int): RemoteKeyKind = when (keyCode) {
            in HIDDEN_CONTROLS_KEYS -> RemoteKeyKind.HIDDEN_CONTROLS
            in LOCKED_KEYS -> RemoteKeyKind.LOCKED
            in VOLUME_KEYS -> RemoteKeyKind.VOLUME
            in IGNORED_KEYS -> RemoteKeyKind.IGNORED
            else -> RemoteKeyKind.ASSIGNABLE
        }

        /** True for the keys a user can give a job: the learn dialog takes these. */
        fun canLearn(keyCode: Int): Boolean =
            kindOf(keyCode) == RemoteKeyKind.ASSIGNABLE || kindOf(keyCode) == RemoteKeyKind.HIDDEN_CONTROLS

        /** Jobs offered for a key; the number keys also get the jump through the video, on a press. */
        fun jobsFor(keyCode: Int, kind: RemotePressKind = RemotePressKind.PRESS): List<RemoteButtonJob> =
            RemoteButtonJob.entries.filter {
                it != RemoteButtonJob.JUMP_TO_PERCENT || (isNumberKey(keyCode) && kind == RemotePressKind.PRESS)
            }

        fun decode(raw: String?): RemoteButtonMap {
            if (raw.isNullOrBlank()) return DEFAULT
            var digits = true
            val custom = linkedMapOf<Int, RemoteButtonBinding>()
            val holds = linkedMapOf<Int, RemoteButtonBinding>()
            raw.split(',').forEach { entry ->
                val key = entry.substringBefore('=', "")
                val value = entry.substringAfter('=', "")
                if (key == "digits") {
                    digits = value != "0"
                    return@forEach
                }
                val hold = key.endsWith(HOLD_SUFFIX)
                val code = key.removeSuffix(HOLD_SUFFIX.toString()).toIntOrNull() ?: return@forEach
                if (!canLearn(code) || code in SEPARATE_PLAY_KEYS) return@forEach
                val job = RemoteButtonJob.fromId(value.substringBefore('@')) ?: return@forEach
                if (job !in jobsFor(code, if (hold) RemotePressKind.HOLD else RemotePressKind.PRESS)) return@forEach
                if (job == RemoteButtonJob.NOTHING && (hold || isHiddenControlsKey(code))) return@forEach
                val skipMs = if (job.needsSkipSize) {
                    value.substringAfter('@', "").toLongOrNull()?.takeIf { it in SKIP_SIZES_MS } ?: DEFAULT_SKIP_MS
                } else 0L
                (if (hold) holds else custom)[code] = RemoteButtonBinding(job, skipMs)
            }
            return RemoteButtonMap(custom, holds, digits)
        }
    }
}
