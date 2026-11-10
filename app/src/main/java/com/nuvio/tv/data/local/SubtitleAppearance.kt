package com.nuvio.tv.data.local

import com.nuvio.tv.domain.model.SubtitleFont

enum class SubtitleEdgeStyle { NONE, OUTLINE, DROP_SHADOW }

/** Stored by name: THICK keeps its width for everyone who chose it; the UI calls it Extra thick and MEDIUM Thick. */
enum class SubtitleOutlineSize { OFF, THIN, NORMAL, MEDIUM, THICK }

internal val DEFAULT_SUBTITLE_EDGE_STYLE = SubtitleEdgeStyle.DROP_SHADOW

internal const val DEFAULT_SUBTITLE_SHADOW_OPACITY = 70
internal const val MIN_SUBTITLE_SHADOW_OPACITY = 10
internal const val MAX_SUBTITLE_SHADOW_OPACITY = 100

/** Unknown new values retain the legacy fallback without rewriting saved preferences. */
internal fun parseSubtitleEdgeStyle(value: String?): SubtitleEdgeStyle? =
    SubtitleEdgeStyle.entries.firstOrNull { it.name == value }

internal fun parseSubtitleFont(value: String?): SubtitleFont? =
    SubtitleFont.entries.firstOrNull { it.name == value }

internal fun parseSubtitleOutlineSize(value: String?): SubtitleOutlineSize? =
    SubtitleOutlineSize.entries.firstOrNull { it.name == value }

/** New profiles use shadow; explicit modern or legacy choices retain their existing semantics. */
internal fun resolveSubtitleEdgeStyle(value: String?, legacyOutline: Boolean?): SubtitleEdgeStyle? =
    if (value == null && legacyOutline == null) DEFAULT_SUBTITLE_EDGE_STYLE else parseSubtitleEdgeStyle(value)

/** The single edge choice older builds saved, read the way they read it. */
internal fun legacySubtitleEdgeStyle(value: String?, legacyOutline: Boolean?): SubtitleEdgeStyle =
    resolveSubtitleEdgeStyle(value, legacyOutline)
        ?: if (legacyOutline ?: true) SubtitleEdgeStyle.OUTLINE else SubtitleEdgeStyle.NONE

/** Text edge Outline becomes a normal outline, Drop shadow a shadow without outline, None neither. */
internal fun SubtitleEdgeStyle.toOutlineSize(): SubtitleOutlineSize =
    if (this == SubtitleEdgeStyle.OUTLINE) SubtitleOutlineSize.NORMAL else SubtitleOutlineSize.OFF

internal fun SubtitleEdgeStyle.hasShadow(): Boolean = this == SubtitleEdgeStyle.DROP_SHADOW

/** The closest single edge choice, kept for older builds and profiles that read only that. */
internal fun legacySubtitleEdgeStyle(outline: SubtitleOutlineSize, shadow: Boolean): SubtitleEdgeStyle = when {
    outline != SubtitleOutlineSize.OFF -> SubtitleEdgeStyle.OUTLINE
    shadow -> SubtitleEdgeStyle.DROP_SHADOW
    else -> SubtitleEdgeStyle.NONE
}

internal fun SubtitleOutlineSize.next(): SubtitleOutlineSize =
    SubtitleOutlineSize.entries[(ordinal + 1) % SubtitleOutlineSize.entries.size]

internal fun normalizeSubtitleShadowOpacity(value: Int): Int =
    (Math.floorDiv(value + 5, 10) * 10).coerceIn(MIN_SUBTITLE_SHADOW_OPACITY, MAX_SUBTITLE_SHADOW_OPACITY)
