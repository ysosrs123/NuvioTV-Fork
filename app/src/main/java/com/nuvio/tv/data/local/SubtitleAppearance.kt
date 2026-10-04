package com.nuvio.tv.data.local

import com.nuvio.tv.domain.model.AppFont

enum class SubtitleEdgeStyle { NONE, OUTLINE, DROP_SHADOW }

internal val DEFAULT_SUBTITLE_EDGE_STYLE = SubtitleEdgeStyle.DROP_SHADOW

/** Unknown new values retain the legacy fallback without rewriting saved preferences. */
internal fun parseSubtitleEdgeStyle(value: String?): SubtitleEdgeStyle? =
    SubtitleEdgeStyle.entries.firstOrNull { it.name == value }

internal fun parseSubtitleFont(value: String?): AppFont? =
    AppFont.entries.firstOrNull { it.name == value }

/** New profiles use shadow; explicit modern or legacy choices retain their existing semantics. */
internal fun resolveSubtitleEdgeStyle(value: String?, legacyOutline: Boolean?): SubtitleEdgeStyle? =
    if (value == null && legacyOutline == null) DEFAULT_SUBTITLE_EDGE_STYLE else parseSubtitleEdgeStyle(value)
