package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import androidx.media3.common.Format
import com.nuvio.tv.core.player.DoviBridge
import java.io.File
import java.util.Locale

/**
 * Dolby Vision and format rows of the playback stats HUD (formatting only; the RPU summaries come from
 * [com.nuvio.tv.core.player.DolbyVisionConversionStats], the HDMI output from amhdmitx sysfs).
 */
internal object PlaybackStatsDv {

    /** Level digits of a Dolby Vision codecs string ("dvhe.07.06" -> 6), or null. */
    fun levelFromCodecs(codecs: String?): Int? {
        val c = codecs?.lowercase(Locale.ROOT) ?: return null
        val m = Regex("(?:^|,)\\s*(?:dvhe|dvh1|dvav|dva1|dav1)\\.(\\d+)\\.(\\d+)").find(c) ?: return null
        return m.groupValues[2].toIntOrNull()
    }

    /**
     * The "DV" row text, without a health dot: "Profile 7.6 FEL - RPU + BL + EL" on the native dual-layer path,
     * "Profile 7.6 FEL -> 8.1" / "Profile 7.6 MEL -> 8.1" on the libdovi conversion path.
     */
    fun profileRowText(
        sourceProfile: String,
        elType: String?,
        modeEffective: String?,
        sourceCodecs: String?,
        converting: Boolean
    ): String {
        val profile = sourceProfile.trim().toIntOrNull()
        val name = buildString {
            append("Profile ").append(profile ?: sourceProfile.trim())
            if (profile == 7) {
                levelFromCodecs(sourceCodecs)?.let { append('.').append(it) }
                if (elType == "FEL" || elType == "MEL") append(' ').append(elType)
            }
        }
        if (profile != null && profile != 7) return "$name · native"
        return when (modeEffective) {
            "NATIVE_FEL" -> "$name · RPU + BL + EL"
            "DV81_LIBDOVI" -> if (converting) "$name → 8.1" else "$name → 8.1 (not converting)"
            "HDR10_BASE_LAYER" -> "$name → HDR10 base layer"
            "STRIP_DV" -> "$name → HDR10 (RPU stripped)"
            "OFF" -> "$name · MediaCodec"
            else -> name
        }
    }

    fun cmText(dm: DoviBridge.RpuDmInfo): String? = when (dm.cmVersion) {
        40 -> "v4.0"
        29 -> "v2.9"
        else -> null
    }

    fun l1Text(dm: DoviBridge.RpuDmInfo): String? {
        val max = dm.l1MaxPq ?: return null
        val avg = dm.l1AvgPq ?: return null
        return "max ${nits(DoviBridge.pq12ToNits(max))} · avg ${nits(DoviBridge.pq12ToNits(avg))} nits"
    }

    /** Active picture area from L5 ("full frame", or "3840×1604 · 2.39:1") against the BL size. */
    fun l5Text(dm: DoviBridge.RpuDmInfo, width: Int, height: Int): String? {
        val l = dm.l5Left ?: return null
        val r = dm.l5Right ?: return null
        val t = dm.l5Top ?: return null
        val b = dm.l5Bottom ?: return null
        if (l == 0 && r == 0 && t == 0 && b == 0) return "full frame"
        if (width <= 0 || height <= 0) return "L $l · R $r · T $t · B $b"
        val w = width - l - r
        val h = height - t - b
        if (w <= 0 || h <= 0) return "L $l · R $r · T $t · B $b"
        return String.format(Locale.ROOT, "%d×%d · %.2f:1", w, h, w.toDouble() / h)
    }

    private fun nits(v: Double): String = when {
        v < 10.0 -> String.format(Locale.ROOT, "%.2f", v)
        v < 100.0 -> String.format(Locale.ROOT, "%.1f", v)
        else -> String.format(Locale.ROOT, "%.0f", v)
    }

    /**
     * One compact format row: "BL 10-bit 4:2:0 · EL 10-bit 1920×1080 · HDMI DV std RGB 8-bit" (native FEL) or
     * "10-bit 4:2:0 · HDMI HDR10 YCbCr 4:2:2 12-bit". Parts that cannot be determined are left out. Standard
     * Dolby Vision is tunnelled in an 8-bit RGB container by design (the 12-bit 4:2:2 signal rides inside it).
     */
    fun formatRowText(format: Format, dm: DoviBridge.RpuDmInfo?, nativeFel: Boolean): String? {
        val parts = ArrayList<String>(3)
        val depth = format.colorInfo?.lumaBitdepth?.takeIf { it > 0 } ?: dm?.blBitDepth ?: bitDepthFromCodecs(format)
        val chroma = chromaFromCodecs(format)
        val video = listOfNotNull(depth?.let { "$it-bit" }, chroma).joinToString(" ")
        if (nativeFel && dm != null) {
            if (video.isNotEmpty()) parts += "BL $video"
            val elSize = if (format.width > 0 && format.height > 0 && dm.elHalfResolution != null) {
                if (dm.elHalfResolution == true) "${format.width / 2}×${format.height / 2}" else "${format.width}×${format.height}"
            } else null
            val el = listOfNotNull(dm.elBitDepth?.let { "$it-bit" }, elSize).joinToString(" ")
            if (el.isNotEmpty()) parts += "EL $el"
        } else if (video.isNotEmpty()) {
            parts += video
        }
        HdmiOutput.describe()?.let { parts += "HDMI $it" }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun bitDepthFromCodecs(format: Format): Int? {
        val c = format.codecs?.lowercase(Locale.ROOT) ?: return null
        return when {
            c.startsWith("dvhe") || c.startsWith("dvh1") -> 10
            c.startsWith("hvc1.2.") || c.startsWith("hev1.2.") -> 10
            c.startsWith("hvc1.1.") || c.startsWith("hev1.1.") -> 8
            c.startsWith("avc1") || c.startsWith("avc3") -> {
                // avc1.PPCCLL: profile_idc 110 (High 10), 122 (High 4:2:2), 244 (High 4:4:4) may be > 8 bit.
                val p = c.substringAfter('.', "").take(2).toIntOrNull(16)
                if (p == null || p == 110 || p == 122 || p == 244) null else 8
            }
            c.startsWith("av01") -> c.split('.').getOrNull(3)?.toIntOrNull()
            else -> null
        }
    }

    private fun chromaFromCodecs(format: Format): String? {
        val c = format.codecs?.lowercase(Locale.ROOT) ?: return null
        return when {
            c.startsWith("dvhe") || c.startsWith("dvh1") -> "4:2:0"
            c.startsWith("hvc1.1.") || c.startsWith("hev1.1.") || c.startsWith("hvc1.2.") || c.startsWith("hev1.2.") -> "4:2:0"
            c.startsWith("avc1") || c.startsWith("avc3") -> {
                val p = c.substringAfter('.', "").take(2).toIntOrNull(16)
                if (p != null && p < 122) "4:2:0" else null
            }
            c.startsWith("av01.0") -> "4:2:0"
            else -> null
        }
    }

    /** HDMI output from amhdmitx sysfs (read at most every [CACHE_MS]; the HUD samples on the main thread). */
    private object HdmiOutput {
        private const val DIR = "/sys/class/amhdmitx/amhdmitx0"
        private const val CACHE_MS = 2_000L
        @Volatile private var cachedAtMs = 0L
        @Volatile private var cached: String? = null
        private val reading = java.util.concurrent.atomic.AtomicBoolean(false)
        private val reader = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "dv-hdmi-stats").apply { isDaemon = true }
        }

        fun describe(): String? {
            val now = SystemClock.elapsedRealtime()
            if (cachedAtMs != 0L && now - cachedAtMs < CACHE_MS) return cached
            if (reading.compareAndSet(false, true)) reader.execute {
                try {
                    cached = runCatching { read() }.getOrNull()
                    cachedAtMs = SystemClock.elapsedRealtime()
                } finally { reading.set(false) }
            }
            return cached
        }

        private fun read(): String? {
            val config = readText("$DIR/config") ?: return null
            val vic = Regex("VIC:\\s*(\\d+)").find(config)?.groupValues?.get(1)?.toIntOrNull()
            if (vic == 0) return null // no output (TV off / no sink)
            val space = Regex("Colourspace:\\s*(\\S+)").find(config)?.groupValues?.get(1)
            val depth = Regex("Colour depth:\\s*(\\d+)-bit").find(config)?.groupValues?.get(1)
            val signal = readText("$DIR/hdmi_hdr_status")?.let { hdrLabel(it.trim()) }
            val colour = when (space?.uppercase(Locale.ROOT)) {
                null -> null
                "RGB" -> "RGB"
                "YUV444" -> "YCbCr 4:4:4"
                "YUV422" -> "YCbCr 4:2:2"
                "YUV420" -> "YCbCr 4:2:0"
                else -> space
            }
            return listOfNotNull(signal, colour, depth?.let { "$it-bit" }).joinToString(" ").takeIf { it.isNotEmpty() }
        }

        private fun hdrLabel(s: String): String = when {
            s.contains("DolbyVision", ignoreCase = true) && s.contains("Low", ignoreCase = true) -> "DV LL"
            s.contains("DolbyVision", ignoreCase = true) -> "DV std"
            s.contains("HLG", ignoreCase = true) -> "HLG"
            s.contains("Plus", ignoreCase = true) -> "HDR10+"
            s.contains("ST2084", ignoreCase = true) || s.contains("HDR10", ignoreCase = true) -> "HDR10"
            s.equals("SDR", ignoreCase = true) -> "SDR"
            else -> s
        }

        private fun readText(path: String): String? = runCatching {
            File(path).inputStream().use { input ->
                val bytes = ByteArray(4096)
                val n = input.read(bytes)
                if (n <= 0) null else String(bytes, 0, n)
            }
        }.getOrNull()
    }
}
