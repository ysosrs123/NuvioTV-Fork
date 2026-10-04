package com.nuvio.tv.ui.components

import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.PosterShape

fun collectionFolderCardImageUrl(
    folder: CollectionFolder,
    isFocused: Boolean
): String? {
    // GIF URL is only used as an animated overlay on focus (when focusGifEnabled is true).
    // When focusGifEnabled is off, fall back to cover image only — don't use the GIF
    // as a static poster since it would still animate via Coil's GIF decoder.
    return firstNonBlank(folder.coverImageUrl)
}

/** A Wide tile stretches a cover made for it, but crops one whose shape is well off 16:9. */
fun wideFolderCoverNeedsCrop(tileShape: PosterShape, imageWidth: Int, imageHeight: Int): Boolean {
    if (tileShape != PosterShape.LANDSCAPE || imageWidth <= 0 || imageHeight <= 0) return false
    val ratio = (imageWidth.toFloat() / imageHeight) / (16f / 9f)
    return ratio < 0.8f || ratio > 1.25f
}

private fun firstNonBlank(vararg candidates: String?): String? {
    return candidates.firstOrNull { !it.isNullOrBlank() }?.trim()
}