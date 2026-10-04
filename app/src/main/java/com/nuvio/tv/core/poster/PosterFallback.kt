package com.nuvio.tv.core.poster

import coil3.request.ErrorResult
import com.nuvio.tv.core.image.CustomPosterFallbackInterceptor
import com.nuvio.tv.domain.model.MetaPreview

fun MetaPreview.isPosterUrl(url: String?): Boolean =
    !url.isNullOrBlank() && (url == poster || url == rawPosterUrl)

/**
 * URL to load when [imageUrl] fails. A landscape card only falls back to the background,
 * never to the portrait poster, unless the poster is what it was showing.
 */
fun MetaPreview.posterFallbackUrl(imageUrl: String?, landscapeCard: Boolean): String? {
    val candidate = if (landscapeCard && imageUrl != poster) {
        background?.takeUnless { isPosterUrl(it) }
    } else {
        rawPosterUrl
    }
    return candidate?.takeIf { it.isNotBlank() && it != imageUrl }
}

/** A load that failed with nothing to fall back to, so a fallback that turns up later is worth a reload. */
fun ErrorResult.failedWithoutFallback(imageUrl: String?): Boolean =
    failedWithoutFallback(request.data, request.memoryCacheKeyExtras, imageUrl)

internal fun failedWithoutFallback(requestData: Any, requestExtras: Map<String, String>, imageUrl: String?): Boolean =
    requestData == imageUrl && requestExtras[CustomPosterFallbackInterceptor.FALLBACK_URL_KEY].isNullOrBlank()

/** A loaded image counts as portrait only when it is clearly taller than wide. */
fun isPortraitArtwork(width: Int, height: Int): Boolean =
    width > 0 && height > 0 && width.toFloat() / height < 0.85f
