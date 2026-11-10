package com.nuvio.tv.core.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import coil3.ImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.toBitmap
import com.nuvio.tv.domain.model.ScoutMixArtCell
import com.nuvio.tv.domain.model.scoutMixArtCells
import com.nuvio.tv.domain.model.scoutMixDeck
import com.nuvio.tv.domain.model.scoutMixHeroPanels
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URLDecoder
import java.net.URLEncoder

/** A mix's pictures: addresses that name the shows' artwork and are drawn from them when asked for. */
object ScoutMixArt {
    private const val SCHEME = "nuvio-mix-art"
    private const val GRID = "art"
    private const val DECK = "deck"
    private const val HERO = "hero"
    private const val NONE = "-"
    private const val MOST = 4
    internal const val WIDTH = 640
    internal const val HEIGHT = 360
    internal const val GAP = 2

    /** Up to four shows side by side. */
    fun url(pictures: List<String>): String? = address(GRID, pictures.clean().take(MOST))

    /** The Home card: the lead show's colours behind up to four posters, dealt at the far edge. */
    fun deck(lead: String?, posters: List<String>, mirrored: Boolean): String? {
        val dealt = posters.clean().take(MOST)
        if (dealt.isEmpty() && lead.isNullOrBlank()) return null
        return address(DECK, listOf(if (mirrored) "r" else "l", lead?.takeIf(String::isNotBlank) ?: NONE) + dealt)
    }

    /** The Home hero: up to three shows across the whole picture, joined on a slant. */
    fun hero(backdrops: List<String>): String? = address(HERO, backdrops.clean().take(3))

    private fun List<String>.clean() = filter(String::isNotBlank).distinct()

    private fun address(kind: String, parts: List<String>): String? {
        if (parts.isEmpty()) return null
        return "$SCHEME://$kind/" + parts.joinToString("/") { URLEncoder.encode(it, "UTF-8") }
    }

    internal fun kind(url: String): String = url.removePrefix("$SCHEME://").substringBefore('/')

    internal fun pictures(url: String): List<String> = url.removePrefix("$SCHEME://").substringAfter('/', "").split('/')
        .filter(String::isNotBlank)
        .mapNotNull { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }

    internal fun handles(url: String): Boolean = url.startsWith("$SCHEME://")

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != SCHEME) return null
            val url = data.toString()
            val parts = pictures(url)
            return when (kind(url)) {
                DECK -> parts.takeIf { it.size >= 2 }?.let {
                    ScoutMixDeckFetcher(it[1].takeUnless { lead -> lead == NONE }, it.drop(2), it[0] == "r", options, imageLoader)
                }
                HERO -> ScoutMixHeroFetcher(parts, options, imageLoader)
                else -> ScoutMixArtFetcher(parts, options, imageLoader)
            }
        }
    }
}

private suspend fun loadArt(options: Options, imageLoader: ImageLoader, picture: String, width: Int, height: Int): Bitmap? =
    imageLoader.execute(
        ImageRequest.Builder(options.context)
            .data(picture)
            .size(width, height)
            .precision(Precision.INEXACT)
            .allowHardware(false)
            .build()
    ).image?.toBitmap()

/** The part of [image] that fills a [width] by [height] box, centred across and [bias] of the way down. */
private fun crop(image: Bitmap, width: Float, height: Float, bias: Float = .5f): Rect {
    val scale = minOf(image.width / width, image.height / height)
    val w = (width * scale).toInt().coerceIn(1, image.width)
    val h = (height * scale).toInt().coerceIn(1, image.height)
    val left = (image.width - w) / 2
    val top = ((image.height - h) * bias).toInt()
    return Rect(left, top, left + w, top + h)
}

private class ScoutMixArtFetcher(
    private val pictures: List<String>,
    private val options: Options,
    private val imageLoader: ImageLoader
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val cells = scoutMixArtCells(pictures.size)
        val images = coroutineScope {
            pictures.zip(cells).map { (picture, cell) ->
                async { target(cell).let { loadArt(options, imageLoader, picture, it.width(), it.height()) } }
            }.awaitAll()
        }
        if (images.all { it == null }) return null
        val out = createBitmap(ScoutMixArt.WIDTH, ScoutMixArt.HEIGHT)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        images.zip(cells).forEach { (image, cell) ->
            if (image != null) {
                val target = target(cell)
                canvas.drawBitmap(image, crop(image, target.width().toFloat(), target.height().toFloat()), target, paint)
            }
        }
        return ImageFetchResult(image = out.asImage(), isSampled = false, dataSource = DataSource.NETWORK)
    }

    private fun target(cell: ScoutMixArtCell) = Rect(
        (cell.left * ScoutMixArt.WIDTH).toInt() + if (cell.left > 0f) ScoutMixArt.GAP else 0,
        (cell.top * ScoutMixArt.HEIGHT).toInt() + if (cell.top > 0f) ScoutMixArt.GAP else 0,
        (cell.right * ScoutMixArt.WIDTH).toInt(),
        (cell.bottom * ScoutMixArt.HEIGHT).toInt()
    )
}

private const val DECK_WIDTH = 960
private const val DECK_HEIGHT = 540
private const val WASH_WIDTH = 48

private class ScoutMixDeckFetcher(
    private val lead: String?,
    private val posters: List<String>,
    private val mirrored: Boolean,
    private val options: Options,
    private val imageLoader: ImageLoader
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val cards = scoutMixDeck(posters.size, mirrored)
        val cardWidth = (cards.firstOrNull()?.width ?: 0f) * DECK_WIDTH
        val cardHeight = (cards.firstOrNull()?.height ?: 0f) * DECK_HEIGHT
        val (wash, faces) = coroutineScope {
            val wash = async { lead?.let { loadArt(options, imageLoader, it, DECK_WIDTH / 4, DECK_HEIGHT / 4) } }
            val faces = posters.take(cards.size).map { poster ->
                async { loadArt(options, imageLoader, poster, cardWidth.toInt().coerceAtLeast(1), cardHeight.toInt().coerceAtLeast(1)) }
            }
            wash.await() to faces.awaitAll()
        }
        if (wash == null && faces.all { it == null }) return null
        val out = createBitmap(DECK_WIDTH, DECK_HEIGHT)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        (wash ?: faces.firstOrNull { it != null })?.let { drawWash(canvas, it) }
        // scoutMixDeck lists the cards back to front; the first poster is the last one drawn.
        val order = (1 until cards.size).reversed() + 0
        order.zip(cards).forEach { (index, card) -> faces.getOrNull(index)?.let { drawCard(canvas, it, card) } }
        return ImageFetchResult(image = out.asImage(), isSampled = false, dataSource = DataSource.NETWORK)
    }

    /** The lead show's picture, softened to its colours so the posters stand out against it. */
    private fun drawWash(canvas: Canvas, image: Bitmap) {
        val source = crop(image, DECK_WIDTH.toFloat(), DECK_HEIGHT.toFloat())
        val small = createBitmap(WASH_WIDTH, WASH_WIDTH * DECK_HEIGHT / DECK_WIDTH)
        Canvas(small).drawBitmap(image, source, Rect(0, 0, small.width, small.height), Paint(Paint.FILTER_BITMAP_FLAG))
        val soft = small.scale(small.width * 4, small.height * 4, filter = true)
        val colour = ColorMatrix().apply {
            setSaturation(1.25f)
            postConcat(ColorMatrix().apply { setScale(.62f, .62f, .62f, 1f) })
        }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(colour) }
        val bleed = DECK_WIDTH * .08f
        canvas.drawBitmap(soft, null, RectF(-bleed, -bleed, DECK_WIDTH + bleed, DECK_HEIGHT + bleed), paint)
    }

    private fun drawCard(canvas: Canvas, image: Bitmap, card: com.nuvio.tv.domain.model.ScoutMixDeckCard) {
        val rect = RectF(
            card.left * DECK_WIDTH,
            card.top * DECK_HEIGHT,
            (card.left + card.width) * DECK_WIDTH,
            (card.top + card.height) * DECK_HEIGHT
        )
        val radius = DECK_HEIGHT * .02f
        canvas.save()
        canvas.rotate(card.degrees, rect.centerX(), rect.centerY())
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            setShadowLayer(DECK_HEIGHT * .05f, -DECK_HEIGHT * .014f, DECK_HEIGHT * .014f, Color.argb(150, 0, 0, 0))
        }
        canvas.drawRoundRect(rect, radius, radius, shadow)
        val clip = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawBitmap(image, crop(image, rect.width(), rect.height()), rect, Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.restore()
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            color = Color.argb(26, 255, 255, 255)
        }
        canvas.drawRoundRect(rect, radius, radius, edge)
        canvas.restore()
    }
}

private const val HERO_WIDTH = 1280
private const val HERO_HEIGHT = 720

private class ScoutMixHeroFetcher(
    private val backdrops: List<String>,
    private val options: Options,
    private val imageLoader: ImageLoader
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val panels = scoutMixHeroPanels(backdrops.size)
        val images = coroutineScope {
            backdrops.zip(panels).map { (backdrop, panel) ->
                val width = ((maxOf(panel.topRight, panel.bottomRight) - minOf(panel.topLeft, panel.bottomLeft)) * HERO_WIDTH).toInt()
                async { loadArt(options, imageLoader, backdrop, width.coerceAtLeast(1), HERO_HEIGHT) }
            }.awaitAll()
        }
        if (images.all { it == null }) return null
        val out = createBitmap(HERO_WIDTH, HERO_HEIGHT)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        images.zip(panels).forEach { (image, panel) ->
            if (image == null) return@forEach
            val left = minOf(panel.topLeft, panel.bottomLeft) * HERO_WIDTH
            val right = maxOf(panel.topRight, panel.bottomRight) * HERO_WIDTH
            val area = RectF(left, 0f, right, HERO_HEIGHT.toFloat())
            val shape = Path().apply {
                moveTo(panel.topLeft * HERO_WIDTH, 0f)
                lineTo(panel.topRight * HERO_WIDTH, 0f)
                lineTo(panel.bottomRight * HERO_WIDTH, HERO_HEIGHT.toFloat())
                lineTo(panel.bottomLeft * HERO_WIDTH, HERO_HEIGHT.toFloat())
                close()
            }
            canvas.save()
            canvas.clipPath(shape)
            canvas.drawBitmap(image, crop(image, area.width(), area.height(), bias = .2f), area, paint)
            canvas.restore()
        }
        return ImageFetchResult(image = out.asImage(), isSampled = false, dataSource = DataSource.NETWORK)
    }
}
