package com.nuvio.tv.core.iptv

enum class LiveDisplayMatch { OFF, START, START_STOP }
enum class LiveFrameRateChoice(val match: LiveDisplayMatch?) { NUVIO(null), OFF(LiveDisplayMatch.OFF), START(LiveDisplayMatch.START), START_STOP(LiveDisplayMatch.START_STOP) }
enum class LiveResolutionChoice(val enabled: Boolean?) { NUVIO(null), OFF(false), ON(true) }
enum class LivePassthroughChoice { NUVIO, OFF }
enum class LiveAudioDecoder { AUTOMATIC, PREFER_APP }

data class LiveDisplayPlan(val frameRate: LiveDisplayMatch, val resolution: Boolean)

data class LiveAudioPlan(val passthrough: Boolean, val tunnelling: Boolean, val preferAppDecoder: Boolean, val gain: Boolean, val surroundLift: Boolean)

data class LiveAudioCandidate(val channels: Int, val language: String?, val playable: Boolean)

object LiveAudioOptions {
    const val LANGUAGE_DEFAULT = "default"
    const val LANGUAGE_DEVICE = "device"

    fun display(frameRate: LiveFrameRateChoice, resolution: LiveResolutionChoice, nuvioFrameRate: LiveDisplayMatch, nuvioResolution: Boolean) =
        LiveDisplayPlan(frameRate.match ?: nuvioFrameRate, resolution.enabled ?: nuvioResolution)

    fun audio(passthrough: LivePassthroughChoice, tunnelling: Boolean, decoder: LiveAudioDecoder, surroundLift: Boolean,
        primary: Boolean, surfaceCorner: Boolean): LiveAudioPlan {
        val tunnel = tunnelling && primary && surfaceCorner && decoder == LiveAudioDecoder.AUTOMATIC
        return LiveAudioPlan(primary && passthrough == LivePassthroughChoice.NUVIO, tunnel, decoder == LiveAudioDecoder.PREFER_APP, !tunnel, surroundLift && !tunnel)
    }

    fun languages(choice: String?, system: List<String>): List<String> = when (val code = choice?.trim()?.lowercase()) {
        null, "", LANGUAGE_DEFAULT -> emptyList()
        LANGUAGE_DEVICE -> system.mapNotNull { it.trim().lowercase().takeIf(String::isNotEmpty) }.distinct()
        else -> listOf(code)
    }

    fun surround(candidates: List<LiveAudioCandidate>, chosen: Int?): Int? {
        val current = chosen?.let(candidates::getOrNull) ?: return chosen
        val base = base(current.language)
        val best = candidates.withIndex().filter { (_, it) -> it.playable && (base == null || base(it.language).let { other -> other == null || other == base }) }
            .maxByOrNull { it.value.channels } ?: return chosen
        return if (best.value.channels > current.channels) best.index else chosen
    }

    private fun base(language: String?): String? =
        language?.substringBefore('-')?.substringBefore('_')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "und" }
}
