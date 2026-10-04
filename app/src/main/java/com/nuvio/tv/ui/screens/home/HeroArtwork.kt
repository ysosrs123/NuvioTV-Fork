package com.nuvio.tv.ui.screens.home

/** Every fallback belongs to this focused carousel item; never borrow row/global artwork. */
internal data class HeroArtworkSelection(val ownerKey: String?, val urls: List<String>)

internal fun heroArtworkSelection(ownerKey: String?, vararg urls: String?): HeroArtworkSelection =
    HeroArtworkSelection(ownerKey, if (ownerKey == null) emptyList() else
        urls.mapNotNull { it?.takeIf(String::isNotBlank) }.distinct())

internal data class HeroArtworkTicket(val ownerKey: String?, val url: String, val generation: Long)

/** A decoded image may survive enrichment of its own title, but never a focus change. */
internal data class HeroArtworkLoadState<T>(
    val selection: HeroArtworkSelection,
    val generation: Long = 0,
    val candidateIndex: Int = 0,
    val ready: T? = null
) {
    val ticket: HeroArtworkTicket?
        get() = selection.urls.getOrNull(candidateIndex)?.let {
            HeroArtworkTicket(selection.ownerKey, it, generation)
        }

    fun select(next: HeroArtworkSelection): HeroArtworkLoadState<T> = if (next == selection) this else
        HeroArtworkLoadState(next, generation + 1, ready = ready.takeIf {
            next.ownerKey == selection.ownerKey && next.urls.isNotEmpty()
        })

    fun loaded(request: HeroArtworkTicket, image: T): HeroArtworkLoadState<T> =
        if (request == ticket) copy(ready = image) else this

    fun failed(request: HeroArtworkTicket): HeroArtworkLoadState<T> =
        if (request == ticket) copy(candidateIndex = candidateIndex + 1) else this
}

internal data class ResolvedModernHeroState(
    val artwork: HeroArtworkSelection,
    val preview: HeroPreview?,
    val enrichmentActive: Boolean
)
