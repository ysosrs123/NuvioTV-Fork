package com.nuvio.tv.core.player.thumbnail

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Opt-in: black-bar crop on real frames. NUVIO_THUMB_CROP_DIR holds raw RGB24 frames named
 * `<title>_<nn>_<w>x<h>.rgb`.
 */
class RealCropTest {
    @Test fun realFrames() {
        val dir = System.getenv("NUVIO_THUMB_CROP_DIR")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val byTitle = dir!!.listFiles { f -> f.name.endsWith(".rgb") }!!.sortedBy { it.name }.groupBy { it.name.substringBefore('_') }
        for ((title, files) in byTitle) {
            val e = ThumbCrop.Estimator()
            val per = StringBuilder()
            for (f in files) {
                val dims = f.name.substringAfterLast('_').removeSuffix(".rgb").split('x')
                val w = dims[0].toInt()
                val h = dims[1].toInt()
                val raw = f.readBytes()
                val px = IntArray(w * h) { i ->
                    val r = raw[i * 3].toInt() and 0xFF
                    val g = raw[i * 3 + 1].toInt() and 0xFF
                    val b = raw[i * 3 + 2].toInt() and 0xFF
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                val bars = ThumbCrop.measure(w, h, px)
                per.append(bars?.let { " ${it.top}/${it.bottom}" } ?: " -")
                e.add(w, h, bars)
            }
            val c = e.current
            println("CROP $title frames=${files.size} per-frame(top/bottom):$per => " +
                (c?.let { "crop l=${it.left} t=${it.top} r=${it.right} b=${it.bottom}" } ?: "no crop"))
        }
    }
}
