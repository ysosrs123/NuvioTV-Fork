package com.nuvio.tv.core.player.thumbnail

/** Black-bar crop for thumbnails. Applied at display time, stored pictures stay whole. */
object ThumbCrop {
    /**
     * A row/column is bar when its brightest pixel is below [MAX_LUMA] (0-255) and its mean below [MEAN_LUMA].
     * 22 also catches bars rendered at 16/16/16 after a range mismatch.
     */
    const val MAX_LUMA = 40
    const val MEAN_LUMA = 22

    /** Crop in pixels. */
    class Bars(val left: Int, val top: Int, val right: Int, val bottom: Int, val width: Int = 0, val height: Int = 0) {
        val isNone: Boolean get() = left == 0 && top == 0 && right == 0 && bottom == 0

        fun fits(w: Int, h: Int): Boolean = w == width && h == height && left + right < w && top + bottom < h
    }

    private fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 2 + g * 5 + b) / 8
    }

    private fun dark(pixels: IntArray, start: Int, count: Int, step: Int): Boolean {
        var max = 0
        var sum = 0
        var i = start
        repeat(count) {
            val y = luma(pixels[i])
            if (y > max) max = y
            sum += y
            i += step
        }
        return max < MAX_LUMA && sum < MEAN_LUMA * count
    }

    /** Dark bars of one picture ([pixels] ARGB, row-major). Null when the picture is nearly all dark. */
    fun measure(width: Int, height: Int, pixels: IntArray): Bars? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        var top = 0
        while (top < height && dark(pixels, top * width, width, 1)) top++
        if (top >= height * 4 / 5) return null
        var bottom = 0
        while (bottom < height - top && dark(pixels, (height - 1 - bottom) * width, width, 1)) bottom++
        val rows = height - top - bottom
        if (rows <= height / 5) return null
        var left = 0
        while (left < width && dark(pixels, top * width + left, rows, width)) left++
        var right = 0
        while (right < width - left && dark(pixels, top * width + width - 1 - right, rows, width)) right++
        if (width - left - right <= width / 5) return null
        return Bars(left, top, right, bottom, width, height)
    }

    /**
     * One title's crop: per side the lower quartile of the measured bars (dark scenes widen a bar, burnt-in
     * subtitles narrow it). Sides under [MIN_FRACTION_PERCENT] of the picture stay uncropped.
     */
    class Estimator {
        private val lefts = ArrayList<Int>()
        private val tops = ArrayList<Int>()
        private val rights = ArrayList<Int>()
        private val bottoms = ArrayList<Int>()
        private var width = 0
        private var height = 0
        @Volatile var current: Bars? = null
            private set
        val sampleCount: Int @Synchronized get() = lefts.size

        @Synchronized
        fun add(width: Int, height: Int, bars: Bars?) {
            if (bars == null) return
            if (this.width != width || this.height != height) {       // picture size changed
                lefts.clear(); tops.clear(); rights.clear(); bottoms.clear()
                this.width = width
                this.height = height
                current = null
            }
            if (lefts.size >= MAX_SAMPLES) return
            lefts += bars.left; tops += bars.top; rights += bars.right; bottoms += bars.bottom
            if (lefts.size < MIN_SAMPLES) return
            fun side(v: ArrayList<Int>, dim: Int): Int {
                val q = v.sorted()[v.size / 4]
                return if (q * 100 < dim * MIN_FRACTION_PERCENT) 0 else q
            }
            current = Bars(side(lefts, width), side(tops, height), side(rights, width), side(bottoms, height),
                width, height).takeUnless { it.isNone }
        }

        companion object {
            const val MIN_SAMPLES = 5
            const val MAX_SAMPLES = 16
            const val MIN_FRACTION_PERCENT = 3
        }
    }
}
