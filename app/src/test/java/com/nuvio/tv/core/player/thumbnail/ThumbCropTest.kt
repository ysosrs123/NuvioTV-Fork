package com.nuvio.tv.core.player.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Black-bar crop: letterbox and pillarbox found, dark scenes and burnt-in subtitles do not mislead it. */
class ThumbCropTest {
    private val w = 320
    private val h = 180

    /** A picture with bars of the given size, content noisy mid-grey or dark. */
    private fun picture(top: Int, bottom: Int, left: Int = 0, right: Int = 0, dark: Boolean = false,
                        seed: Int = 1, subtitleInBottomBar: Boolean = false): IntArray {
        val rnd = Random(seed)
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val bar = y < top || y >= h - bottom || x < left || x >= w - right
            val v = when {
                bar -> rnd.nextInt(0, 6)                    // encoded black
                dark -> rnd.nextInt(0, 30)
                else -> rnd.nextInt(40, 220)
            }
            px[y * w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        if (subtitleInBottomBar) {
            val y0 = h - bottom + 4
            for (y in y0 until y0 + 8) for (x in 100 until 220) px[y * w + x] = -1   // white text
        }
        return px
    }

    @Test fun letterbox239In169() {
        val b = ThumbCrop.measure(w, h, picture(23, 23))
        assertNotNull(b)
        assertEquals(23, b!!.top)
        assertEquals(23, b.bottom)
        assertEquals(0, b.left)
        assertEquals(0, b.right)
    }

    @Test fun pillarbox43In169() {
        val b = ThumbCrop.measure(w, h, picture(0, 0, left = 40, right = 40))!!
        assertEquals(40, b.left)
        assertEquals(40, b.right)
        assertEquals(0, b.top)
    }

    @Test fun darkPictureTellsNothing() {
        assertNull(ThumbCrop.measure(w, h, IntArray(w * h) { 0xFF000000.toInt() }))
    }

    @Test fun estimatorNeedsFiveSamplesThenCrops() {
        val e = ThumbCrop.Estimator()
        repeat(4) { e.add(w, h, ThumbCrop.measure(w, h, picture(23, 23, seed = it))) }
        assertNull(e.current)
        e.add(w, h, ThumbCrop.measure(w, h, picture(23, 23, seed = 9)))
        assertEquals(23, e.current!!.top)
        assertEquals(23, e.current!!.bottom)
    }

    /** Dark scenes widen the measured bar, a subtitle in the bar narrows it. */
    @Test fun estimatorRobustToDarkScenesAndSubtitles() {
        val e = ThumbCrop.Estimator()
        for (i in 0 until 5) e.add(w, h, ThumbCrop.measure(w, h, picture(23, 23, seed = i)))
        e.add(w, h, ThumbCrop.measure(w, h, picture(40, 40, seed = 20)))              // dark scene
        e.add(w, h, ThumbCrop.measure(w, h, picture(23, 23, seed = 21, subtitleInBottomBar = true)))
        e.add(w, h, ThumbCrop.measure(w, h, picture(23, 23, seed = 22)))
        assertEquals(23, e.current!!.top)
        assertEquals(23, e.current!!.bottom)
    }

    @Test fun fullFrameStaysUncropped() {
        val e = ThumbCrop.Estimator()
        repeat(6) { e.add(w, h, ThumbCrop.measure(w, h, picture(0, 0, seed = it))) }
        assertNull(e.current)
    }

    /** A new picture size drops the old crop. A crop only fits its own size. */
    @Test fun sizeChangeResetsAndFits() {
        val e = ThumbCrop.Estimator()
        repeat(5) { e.add(w, h, ThumbCrop.measure(w, h, picture(23, 23, seed = it))) }
        val c = e.current!!
        assertTrue(c.fits(w, h))
        assertFalse(c.fits(320, 134))
        e.add(320, 240, ThumbCrop.Bars(0, 10, 0, 10, 320, 240))
        assertNull(e.current)
    }

    /** One or two dark edge rows are below the 3 % threshold. */
    @Test fun thinEdgeIgnored() {
        val e = ThumbCrop.Estimator()
        repeat(6) { e.add(w, h, ThumbCrop.measure(w, h, picture(2, 2, seed = it))) }
        assertNull(e.current)
    }
}
