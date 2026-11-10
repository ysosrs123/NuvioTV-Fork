package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.view.KeyEvent
import com.nuvio.tv.R
import com.nuvio.tv.data.local.NIGHT_MODE_KEY_STYLE
import com.nuvio.tv.data.local.NightModeKeyStyle
import com.nuvio.tv.data.local.RemoteButtonBinding
import com.nuvio.tv.data.local.RemoteButtonJob
import com.nuvio.tv.data.local.RemoteButtonMap
import com.nuvio.tv.data.local.RemotePressKind
import java.util.Locale

internal fun remoteKeyLabel(context: Context, keyCode: Int): String {
    RemoteButtonMap.numberOf(keyCode)?.let { return context.getString(R.string.remote_key_number, it) }
    val res = when (keyCode) {
        RemoteButtonMap.NUMBER_KEYS_ROW -> R.string.remote_buttons_number_keys
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> R.string.remote_key_play_pause
        KeyEvent.KEYCODE_SPACE -> R.string.remote_key_space
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> R.string.remote_key_fast_forward
        KeyEvent.KEYCODE_MEDIA_REWIND -> R.string.remote_key_rewind
        KeyEvent.KEYCODE_MEDIA_STOP -> R.string.remote_key_stop
        KeyEvent.KEYCODE_MENU -> R.string.remote_key_menu
        KeyEvent.KEYCODE_INFO -> R.string.remote_key_info
        KeyEvent.KEYCODE_MEDIA_NEXT -> R.string.remote_key_next
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> R.string.remote_key_previous
        KeyEvent.KEYCODE_CHANNEL_UP -> R.string.remote_key_channel_up
        KeyEvent.KEYCODE_CHANNEL_DOWN -> R.string.remote_key_channel_down
        KeyEvent.KEYCODE_PAGE_UP -> R.string.remote_key_page_up
        KeyEvent.KEYCODE_PAGE_DOWN -> R.string.remote_key_page_down
        KeyEvent.KEYCODE_PROG_RED -> R.string.remote_key_red
        KeyEvent.KEYCODE_PROG_GREEN -> R.string.remote_key_green
        KeyEvent.KEYCODE_PROG_YELLOW -> R.string.remote_key_yellow
        KeyEvent.KEYCODE_PROG_BLUE -> R.string.remote_key_blue
        KeyEvent.KEYCODE_MUSIC -> R.string.remote_key_music
        KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> R.string.remote_key_audio_track
        KeyEvent.KEYCODE_CAPTIONS -> R.string.remote_key_captions
        KeyEvent.KEYCODE_GUIDE -> R.string.remote_key_guide
        KeyEvent.KEYCODE_MEDIA_RECORD -> R.string.remote_key_record
        KeyEvent.KEYCODE_TV_TELETEXT -> R.string.remote_key_text
        KeyEvent.KEYCODE_BOOKMARK -> R.string.remote_key_favourite
        KeyEvent.KEYCODE_SEARCH -> R.string.remote_key_search
        KeyEvent.KEYCODE_TV -> R.string.remote_key_live_tv
        KeyEvent.KEYCODE_SETTINGS -> R.string.remote_key_settings
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> R.string.remote_key_ok
        KeyEvent.KEYCODE_DPAD_UP -> R.string.remote_key_up
        KeyEvent.KEYCODE_DPAD_DOWN -> R.string.remote_key_down
        KeyEvent.KEYCODE_DPAD_LEFT -> R.string.remote_key_left
        KeyEvent.KEYCODE_DPAD_RIGHT -> R.string.remote_key_right
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> R.string.remote_key_back
        else -> null
    }
    if (res != null) return context.getString(res)
    val raw = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
    if (raw.all { it.isDigit() }) return raw
    return raw.split('_').joinToString(" ") { part ->
        if (part.length <= 2) part else part.lowercase(Locale.ROOT)
    }.replaceFirstChar { it.titlecase(Locale.getDefault()) }
}

internal fun remoteSkipSizeLabel(context: Context, skipMs: Long): String {
    val seconds = (skipMs / 1000L).toInt()
    return if (seconds < 60) context.getString(R.string.remote_buttons_size_seconds, seconds)
    else context.getString(R.string.remote_buttons_size_minutes, seconds / 60)
}

internal fun remoteJobLabel(context: Context, job: RemoteButtonJob): String = context.getString(when (job) {
    RemoteButtonJob.NOTHING -> R.string.remote_buttons_job_nothing
    RemoteButtonJob.STATS -> R.string.remote_buttons_job_stats
    RemoteButtonJob.INFO -> R.string.cd_stream_info
    RemoteButtonJob.SHOW_DETAILS -> R.string.remote_buttons_job_show_details
    RemoteButtonJob.SOURCES -> R.string.cd_sources
    RemoteButtonJob.EPISODES -> R.string.player_pill_episodes
    RemoteButtonJob.AUDIO -> R.string.cd_audio_tracks
    RemoteButtonJob.NEXT_AUDIO -> R.string.remote_buttons_job_next_audio
    RemoteButtonJob.NIGHT_MODE -> when (NIGHT_MODE_KEY_STYLE) {
        NightModeKeyStyle.TOGGLE -> R.string.remote_buttons_job_night_mode
        NightModeKeyStyle.CYCLE -> R.string.remote_buttons_job_night_mode_cycle
    }
    RemoteButtonJob.SUBTITLES -> R.string.cd_subtitles
    RemoteButtonJob.SUBTITLES_TOGGLE -> R.string.remote_buttons_job_subtitles_toggle
    RemoteButtonJob.SUBTITLES_UP -> R.string.remote_buttons_job_subtitles_up
    RemoteButtonJob.SUBTITLES_DOWN -> R.string.remote_buttons_job_subtitles_down
    RemoteButtonJob.PLAY_PAUSE -> R.string.remote_buttons_job_play_pause
    RemoteButtonJob.SEEK_FORWARD -> R.string.remote_buttons_job_seek_forward
    RemoteButtonJob.SEEK_BACK -> R.string.remote_buttons_job_seek_back
    RemoteButtonJob.SKIP_FORWARD -> R.string.remote_buttons_job_skip_forward
    RemoteButtonJob.SKIP_BACK -> R.string.remote_buttons_job_skip_back
    RemoteButtonJob.JUMP_TO_PERCENT -> R.string.remote_buttons_job_jump_percent
    RemoteButtonJob.SKIP_INTRO_OR_NEXT -> R.string.remote_buttons_job_skip_intro_or_next
    RemoteButtonJob.NEXT_EPISODE -> R.string.player_pill_next_episode
    RemoteButtonJob.RESTART -> R.string.player_pill_restart
    RemoteButtonJob.SPEED -> R.string.cd_playback_speed
    RemoteButtonJob.ASPECT -> R.string.cd_aspect_ratio
    RemoteButtonJob.WATCH_PARTY -> R.string.party_title
    RemoteButtonJob.EXTERNAL -> R.string.cd_open_external_player
    RemoteButtonJob.CHAPTERS_LIST -> R.string.remote_buttons_job_chapters_list
    RemoteButtonJob.NEXT_CHAPTER -> R.string.remote_buttons_job_next_chapter
    RemoteButtonJob.PREVIOUS_CHAPTER -> R.string.remote_buttons_job_previous_chapter
    RemoteButtonJob.REPORT_MARK -> R.string.remote_buttons_job_report_mark
    RemoteButtonJob.LEAVE -> R.string.remote_buttons_job_leave
})

internal fun remoteBindingLabel(context: Context, binding: RemoteButtonBinding): String = when (binding.job) {
    RemoteButtonJob.SKIP_FORWARD -> context.getString(
        R.string.remote_buttons_job_skip_forward_value, remoteSkipSizeLabel(context, binding.skipMs)
    )
    RemoteButtonJob.SKIP_BACK -> context.getString(
        R.string.remote_buttons_job_skip_back_value, remoteSkipSizeLabel(context, binding.skipMs)
    )
    else -> remoteJobLabel(context, binding.job)
}

internal fun remoteJobDescription(
    context: Context,
    job: RemoteButtonJob,
    kind: RemotePressKind = RemotePressKind.PRESS
): String? = when (job) {
    RemoteButtonJob.SEEK_FORWARD, RemoteButtonJob.SEEK_BACK -> context.getString(
        if (kind == RemotePressKind.HOLD) R.string.remote_buttons_job_seek_hold_desc else R.string.remote_buttons_job_seek_desc
    )
    RemoteButtonJob.SKIP_FORWARD, RemoteButtonJob.SKIP_BACK -> context.getString(R.string.remote_buttons_job_skip_desc)
    RemoteButtonJob.SKIP_INTRO_OR_NEXT -> context.getString(R.string.remote_buttons_job_skip_intro_or_next_desc)
    RemoteButtonJob.JUMP_TO_PERCENT -> context.getString(R.string.remote_buttons_number_keys_desc)
    RemoteButtonJob.SHOW_DETAILS -> context.getString(R.string.remote_buttons_job_show_details_desc)
    RemoteButtonJob.NIGHT_MODE -> context.getString(when (NIGHT_MODE_KEY_STYLE) {
        NightModeKeyStyle.TOGGLE -> R.string.remote_buttons_job_night_mode_desc
        NightModeKeyStyle.CYCLE -> R.string.remote_buttons_job_night_mode_cycle_desc
    })
    RemoteButtonJob.REPORT_MARK -> context.getString(R.string.remote_buttons_job_report_mark_desc)
    RemoteButtonJob.CHAPTERS_LIST -> context.getString(R.string.remote_buttons_job_chapters_list_desc)
    RemoteButtonJob.PREVIOUS_CHAPTER -> context.getString(R.string.remote_buttons_job_previous_chapter_desc)
    else -> null
}
