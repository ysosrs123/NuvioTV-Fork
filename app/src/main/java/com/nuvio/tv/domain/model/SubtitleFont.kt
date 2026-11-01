package com.nuvio.tv.domain.model

/** Subtitle choices: every UI font under its stored [AppFont] name, plus fonts offered only for subtitles. */
enum class SubtitleFont(val displayName: String, val appFont: AppFont? = null) {
    INTER(AppFont.INTER),
    DM_SANS(AppFont.DM_SANS),
    OPEN_SANS(AppFont.OPEN_SANS),
    ATKINSON_HYPERLEGIBLE_NEXT(AppFont.ATKINSON_HYPERLEGIBLE_NEXT),
    SOURCE_SANS_3(AppFont.SOURCE_SANS_3),
    ROBOTO("Roboto"),
    MONTSERRAT("Montserrat"),
    NOTO_SANS_SEMIBOLD("Noto Sans Semi-Bold"),
    ASAP_CONDENSED("Asap Condensed");

    constructor(appFont: AppFont) : this(appFont.displayName, appFont)
}
