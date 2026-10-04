package com.nuvio.tv.ui.screens.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class PageHeroArtwork(val ownerKey: String?, val url: String?, val generation: Long)

data class TitleHeroArtwork(val backdropUrl: String?, val logoUrl: String?)

object HeroBackdropState {
    var pageArtwork by mutableStateOf(PageHeroArtwork(null, null, 0))
        private set

    fun selectPageArtwork(ownerKey: String?, url: String?): PageHeroArtwork {
        val cleanUrl = url?.takeIf(String::isNotBlank)
        if (pageArtwork.ownerKey != ownerKey || pageArtwork.url != cleanUrl) {
            pageArtwork = PageHeroArtwork(ownerKey, cleanUrl, pageArtwork.generation + 1)
        }
        return pageArtwork
    }

    fun recordDisplayedArtwork(selection: PageHeroArtwork, url: String) {
        if (pageArtwork == selection) lastDisplayedUrl = url
    }

    @Volatile
    var currentHeroBackdropUrl: String? = null
        private set

    /** Last backdrop URL that was actually displayed — survives navigation for seamless back transitions. */
    @Volatile
    var lastDisplayedUrl: String? = null

    private var selectedTitle: Pair<String, String>? = null
    private var selectedLogoUrl: String? = null

    fun updateForTitle(id: String, type: String, url: String?, logoUrl: String? = null) {
        update(url)
        selectedTitle = id to type
        selectedLogoUrl = logoUrl?.takeIf(String::isNotBlank)
        selectPageArtwork("$type:$id", url)
    }

    fun consumeForTitle(id: String, type: String): String? =
        consumeArtworkForTitle(id, type)?.backdropUrl

    fun consumeArtworkForTitle(id: String, type: String): TitleHeroArtwork? {
        val matches = selectedTitle == (id to type)
        val artwork = TitleHeroArtwork(currentHeroBackdropUrl, selectedLogoUrl)
        consumeAndClear()
        return artwork.takeIf { matches }
    }

    fun update(url: String?) {
        selectedTitle = null
        selectedLogoUrl = null
        currentHeroBackdropUrl = url
        selectPageArtwork(null, url)
        if (!url.isNullOrBlank()) {
            lastDisplayedUrl = url
        }
    }

    fun consumeAndClear(): String? {
        val url = currentHeroBackdropUrl
        currentHeroBackdropUrl = null
        selectedTitle = null
        selectedLogoUrl = null
        return url
    }
}
