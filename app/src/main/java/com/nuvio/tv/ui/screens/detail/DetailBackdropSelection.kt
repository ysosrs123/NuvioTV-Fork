package com.nuvio.tv.ui.screens.detail

/** One choice per navigation entry. Metadata enrichment must not replace visible artwork. */
internal data class DetailBackdropSelection(
    val url: String? = null,
    val failedUrls: Set<String> = emptySet()
) {
    fun select(vararg candidates: String?): DetailBackdropSelection =
        if (url != null) this else copy(url = candidates.firstOrNull {
            !it.isNullOrBlank() && it !in failedUrls
        })

    /** Only a failed current request may advance to another image for this title. */
    fun failed(requestUrl: String?): DetailBackdropSelection =
        if (requestUrl == null || requestUrl != url) this
        else copy(url = null, failedUrls = failedUrls + requestUrl)
}
