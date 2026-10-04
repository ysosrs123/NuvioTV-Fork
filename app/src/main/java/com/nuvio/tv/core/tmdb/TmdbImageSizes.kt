package com.nuvio.tv.core.tmdb

import android.content.res.Resources

/**
 * Resolution-aware TMDB source-size selection.
 *
 * Backdrops render full-bleed, and TMDB has no backdrop size between w1280 and
 * original, so UIs wider than 1600 px get original and the rest stay on w1280.
 *
 * Stills use original: the official ladder is w92/w185/w300/original, and
 * episode cards render at about 640-800 px. Requests are already sized to
 * display pixels, so only transfer size rises.
 */
object TmdbImageSizes {

    val backdrop: String by lazy {
        val dm = Resources.getSystem().displayMetrics
        if (maxOf(dm.widthPixels, dm.heightPixels) > 1600) "original" else "w1280"
    }

    const val STILL: String = "original"
}
