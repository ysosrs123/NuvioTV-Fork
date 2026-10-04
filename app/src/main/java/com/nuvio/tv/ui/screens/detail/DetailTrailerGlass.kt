package com.nuvio.tv.ui.screens.detail

/** True while the background trailer, not the still backdrop, is the picture behind the page. */
internal fun isBackgroundTrailerShowing(
    isBackgroundTrailerPlaying: Boolean,
    isBackgroundTrailerRendered: Boolean,
    pauseBackgroundTrailerOnScroll: Boolean,
    scrolledPastHero: Boolean
): Boolean = isBackgroundTrailerPlaying && isBackgroundTrailerRendered &&
    !(pauseBackgroundTrailerOnScroll && scrolledPastHero)

/** The hero controls sit on moving video only then; the foreground trailer hides them instead. */
internal fun heroGlassOverTrailer(
    isTrailerPlaying: Boolean,
    isBackgroundTrailerPlaying: Boolean,
    isBackgroundTrailerRendered: Boolean,
    pauseBackgroundTrailerOnScroll: Boolean,
    scrolledPastHero: Boolean
): Boolean = !isTrailerPlaying && isBackgroundTrailerShowing(
    isBackgroundTrailerPlaying,
    isBackgroundTrailerRendered,
    pauseBackgroundTrailerOnScroll,
    scrolledPastHero
)
