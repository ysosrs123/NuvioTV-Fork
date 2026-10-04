package com.nuvio.tv.ui.components

/**
 * Whether a card draws its own logo, title and shade over the artwork or trailer.
 * Artwork that already carries the title never gets a second one, also not once the trailer covers it.
 */
internal fun cardTitleOverlayVisible(
    artworkCarriesTitle: Boolean,
    trailerShowing: Boolean,
    logoOverTrailer: Boolean
): Boolean = when {
    artworkCarriesTitle -> false
    trailerShowing -> logoOverTrailer
    else -> true
}
