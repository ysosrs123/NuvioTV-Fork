package com.nuvio.tv.ui.screens.player

import kotlinx.coroutines.flow.update

/**
 * Defaults the audio selection to the best lossless track, since the engines'
 * own defaults follow container flags and language and often land on the lossy
 * companion track of a remux.
 *
 * Precedence: an explicit pick this session, a remembered per-title selection,
 * an engine-failover carry-over, this default, then the engine's own default.
 *
 * The preference applies only within the first preferred language that has any
 * track other than a commentary; a lossless track is never chosen over the user's
 * language. When nothing lossless qualifies and the engine landed on a commentary,
 * the main track is chosen instead. A track is
 * ranked only when its codec or name proves the tier (mpv reports plain "dts",
 * so it needs an MA / Master Audio hint; ExoPlayer's DTS-HD mime ranks as
 * lossless). The default is never persisted.
 */
internal object LosslessAudioTrackDefault {

    /** Ranking tiers, higher is better. Mirrors the StreamQualityRank ordering. */
    const val TIER_TRUEHD = 4
    const val TIER_DTS_HD_MA = 3
    const val TIER_FLAC = 2
    const val TIER_PCM = 1

    private val commentaryHints = listOf(
        "commentary", "descriptive", "description", "narration", "narrator"
    )

    /**
     * Lossless tier of a track, or null when the track is not provably lossless.
     * [codec] is TrackInfo.codec: ExoPlayer supplies CustomDefaultTrackNameProvider
     * names ("TrueHD", "DTS-HD", "FLAC", "ALAC", "WAV", "PCM"); mpv supplies FFmpeg
     * codec names ("truehd", "mlp", "dts", "flac", "pcm_s24le", ...).
     * [name] is the display name, used for MA hints and PCM/LPCM hints only.
     */
    fun losslessTier(codec: String?, name: String?): Int? {
        val c = codec?.trim()?.lowercase().orEmpty()
        val n = name?.trim()?.lowercase().orEmpty()

        if (c == "truehd" || c == "mlp" || "truehd" in n || "true hd" in n) return TIER_TRUEHD

        val nameSaysMa =
            "dts-hd ma" in n || "dts hd ma" in n || "dtshd ma" in n || "master audio" in n ||
                Regex("\\bma\\b").containsMatchIn(n)
        if (c == "dts-hd") return TIER_DTS_HD_MA
        if (c == "dts" && nameSaysMa) return TIER_DTS_HD_MA
        if (c.isEmpty() && nameSaysMa && "dts" in n) return TIER_DTS_HD_MA

        if (c == "flac" || c == "alac" || "flac" in n) return TIER_FLAC

        if (c == "pcm" || c == "wav" || c.startsWith("pcm_") || "lpcm" in n) return TIER_PCM

        return null
    }

    fun isCommentaryRole(role: TrackRole?): Boolean =
        role == TrackRole.COMMENTARY || role == TrackRole.AUDIO_DESCRIPTION

    fun isCommentaryLike(name: String?): Boolean {
        val n = name?.lowercase() ?: return false
        return commentaryHints.any { it in n }
    }

    private fun isCommentary(track: TrackInfo): Boolean =
        isCommentaryLike(track.name) || isCommentaryRole(track.role)

    private fun languagePool(
        tracks: List<TrackInfo>,
        preferredLanguages: List<String>,
        languageMatches: (String?, String) -> Boolean
    ): List<TrackInfo> = preferredLanguages
        .asSequence()
        .map { lang -> tracks.filter { languageMatches(it.language, lang) && !isCommentary(it) } }
        .firstOrNull { it.isNotEmpty() }
        ?: tracks

    /**
     * Index of the track the default should select, or null when the engine's own pick
     * should stand. [languageMatches] is (trackLanguage, preferredLanguage) -> Boolean,
     * expected to tolerate ISO 639-1/639-2 variants (PlayerSubtitleUtils.matchesLanguageCode).
     */
    fun pickDefaultIndex(
        tracks: List<TrackInfo>,
        preferredLanguages: List<String>,
        languageMatches: (String?, String) -> Boolean
    ): Int? {
        if (tracks.isEmpty()) return null

        // Candidate pool: the first preferred language that has a track other than a
        // commentary; if no preferred language matches anything, the whole set.
        val pool = languagePool(tracks, preferredLanguages, languageMatches)

        val ranked = pool.mapNotNull { track ->
            losslessTier(track.codec, track.name)?.let { tier -> track to tier }
        }
        if (ranked.isEmpty()) return null

        // Highest tier that has a non-commentary member; a commentary track is never
        // made the default, even when it is the only lossless one.
        val byTierDesc = ranked.groupBy({ it.second }, { it.first })
            .toSortedMap(compareByDescending { it })
        for ((_, tierTracks) in byTierDesc) {
            val clean = tierTracks.filterNot { isCommentary(it) }
            if (clean.isNotEmpty()) return mostChannels(clean)
        }
        return null
    }

    /**
     * Index of a main track to use instead of the engine's pick when that pick is a
     * commentary, or null when the engine's pick should stand. Only used when
     * [pickDefaultIndex] found nothing lossless.
     */
    fun pickInsteadOfCommentary(
        tracks: List<TrackInfo>,
        selectedIndex: Int,
        preferredLanguages: List<String>,
        languageMatches: (String?, String) -> Boolean
    ): Int? {
        val selected = tracks.firstOrNull { it.index == selectedIndex } ?: return null
        if (!isCommentary(selected)) return null
        val clean = tracks.filterNot { isCommentary(it) }
        if (clean.isEmpty()) return null
        return mostChannels(languagePool(clean, preferredLanguages, languageMatches))
    }

    private fun mostChannels(tracks: List<TrackInfo>): Int =
        tracks
            .sortedWith(
                compareByDescending<TrackInfo> { it.channelCount ?: 0 }
                    .thenBy { it.index }
            )
            .first()
            .index
}

/**
 * Applies the lossless default once per stream, after the persisted/remembered restore
 * pass has had its chance. Runs on both engines' tracks-ready paths; selection goes
 * through the same selectAudioTrack() the restore path uses, so engine handling and the
 * failover carry-over capture see it exactly like any other selection. Never persisted.
 */
internal fun PlayerRuntimeController.applyLosslessAudioDefaultIfUnset(
    audioTracks: List<TrackInfo>
) {
    if (audioTracks.isEmpty()) return
    if (losslessAudioDefaultAppliedForStream) return

    val rememberedAudio = rememberedTrackPreference?.audio
    val persistedAudio = persistedTrackPreference?.audio
    if (rememberedAudio != null || persistedAudio != null ||
        persistedAudioPreferenceSeenForStream ||
        pendingEngineSwitchTrackPreference != null
    ) {
        losslessAudioDefaultAppliedForStream = true
        logSwitchTrace(
            stage = "lossless-default",
            message = "result=skip reason=preference-present remembered=${rememberedAudio != null} " +
                "persisted=${persistedAudio != null} seen=$persistedAudioPreferenceSeenForStream " +
                "switchPending=${pendingEngineSwitchTrackPreference != null}"
        )
        return
    }

    losslessAudioDefaultAppliedForStream = true

    val losslessPick = LosslessAudioTrackDefault.pickDefaultIndex(
        tracks = audioTracks,
        preferredLanguages = mpvPreferredAudioLanguages,
        languageMatches = { trackLanguage, preferred ->
            PlayerSubtitleUtils.matchesLanguageCode(trackLanguage, preferred)
        }
    )
    val selectedIndex = audioTracks.indexOfFirst { it.isSelected }
        .takeIf { it >= 0 } ?: _uiState.value.selectedAudioTrackIndex
    val pick = losslessPick ?: LosslessAudioTrackDefault.pickInsteadOfCommentary(
        tracks = audioTracks,
        selectedIndex = selectedIndex,
        preferredLanguages = mpvPreferredAudioLanguages,
        languageMatches = { trackLanguage, preferred ->
            PlayerSubtitleUtils.matchesLanguageCode(trackLanguage, preferred)
        }
    )
    if (pick == null || pick == selectedIndex) {
        logSwitchTrace(
            stage = "lossless-default",
            message = "result=${if (pick == null) "none" else "already-selected"} " +
                "pick=$pick selected=$selectedIndex " +
                "tracks=${audioTracks.joinToString { "${it.index}:${it.codec}/${it.language}" }}"
        )
        return
    }

    val target = audioTracks[pick]
    logSwitchTrace(
        stage = "lossless-default",
        message = "result=apply reason=${if (losslessPick != null) "lossless" else "not-commentary"} " +
            "index=$pick codec=${target.codec} lang=${target.language} " +
            "name=${target.name} channels=${target.channelCount} was=$selectedIndex"
    )
    selectAudioTrack(pick)
    _uiState.update { it.copy(selectedAudioTrackIndex = pick) }
}
