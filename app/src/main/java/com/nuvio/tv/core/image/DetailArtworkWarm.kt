package com.nuvio.tv.core.image

import android.content.Context
import coil3.decode.BlackholeDecoder
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest

/** Fetches a focused title's backdrop and logo to disk, at the sizes its page asks for, without decoding them. */
suspend fun warmDetailArtworkOnDisk(context: Context, background: String?, logo: String?) {
    val metrics = context.resources.displayMetrics
    suspend fun warm(url: String?, width: Int, height: Int) {
        if (url.isNullOrBlank() || width <= 0 || height <= 0) return
        context.imageLoader.execute(
            ImageRequest.Builder(context).data(url).size(width, height)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .decoderFactory(BlackholeDecoder.Factory())
                .build()
        )
    }
    warm(background, metrics.widthPixels, metrics.heightPixels)
    warm(logo, (metrics.widthPixels * DETAIL_LOGO_WIDTH_SHARE).toInt(), metrics.heightPixels / 4)
}

private const val DETAIL_LOGO_WIDTH_SHARE = 0.4f
