package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import android.os.Build
import androidx.annotation.FontRes
import androidx.annotation.RequiresApi
import androidx.core.content.res.ResourcesCompat
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.AppFont
import com.nuvio.tv.domain.model.SubtitleFont
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@FontRes
internal fun AppFont.fontResource(): Int = when (this) {
    AppFont.INTER -> R.font.inter_variable
    AppFont.DM_SANS -> R.font.dm_sans_variable
    AppFont.OPEN_SANS -> R.font.opensans_variable
    AppFont.ATKINSON_HYPERLEGIBLE_NEXT -> R.font.atkinson_hyperlegible_next_variable
    AppFont.SOURCE_SANS_3 -> R.font.source_sans3_variable
}

/** Regular (or, for Noto, semi-bold) file of the subtitle font; may be a variable font. */
@FontRes
internal fun SubtitleFont.fontResource(): Int = when (this) {
    SubtitleFont.ROBOTO -> R.font.subtitle_roboto_variable
    SubtitleFont.MONTSERRAT -> R.font.subtitle_montserrat_regular
    SubtitleFont.NOTO_SANS_SEMIBOLD -> R.font.subtitle_noto_sans_semibold
    SubtitleFont.ASAP_CONDENSED -> R.font.subtitle_asap_condensed_regular
    else -> requireNotNull(appFont).fontResource()
}

/** Separate bold file for static fonts; variable fonts take their bold from the weight axis. */
@FontRes
internal fun SubtitleFont.boldFontResource(): Int? = when (this) {
    SubtitleFont.MONTSERRAT -> R.font.subtitle_montserrat_bold
    SubtitleFont.NOTO_SANS_SEMIBOLD -> R.font.subtitle_noto_sans_bold
    SubtitleFont.ASAP_CONDENSED -> R.font.subtitle_asap_condensed_bold
    else -> null
}

internal fun SubtitleFont.regularWeight(): Int = if (this == SubtitleFont.NOTO_SANS_SEMIBOLD) 600 else 400

internal fun subtitleFontWeight(font: SubtitleFont?, bold: Boolean): Int =
    if (bold) 700 else font?.regularWeight() ?: 400

/** File and weight drawn for a choice; [weightFromFile] is false when the file is not already that weight. */
internal data class SubtitleFontFace(@param:FontRes val resource: Int, val weight: Int, val weightFromFile: Boolean)

internal fun SubtitleFont.face(bold: Boolean): SubtitleFontFace {
    val boldFile = boldFontResource()
    return when {
        bold && boldFile != null -> SubtitleFontFace(boldFile, 700, true)
        else -> SubtitleFontFace(fontResource(), subtitleFontWeight(this, bold), boldFile != null)
    }
}

/** Noto Sans semi-bold and bold for every script the app is translated into (CJK comes from the system). */
internal val NOTO_SUBTITLE_SCRIPT_FONTS: List<Pair<Int, Int>> = listOf(
    R.font.subtitle_noto_sans_arabic_semibold to R.font.subtitle_noto_sans_arabic_bold,
    R.font.subtitle_noto_sans_hebrew_semibold to R.font.subtitle_noto_sans_hebrew_bold,
    R.font.subtitle_noto_sans_devanagari_semibold to R.font.subtitle_noto_sans_devanagari_bold,
    R.font.subtitle_noto_sans_bengali_semibold to R.font.subtitle_noto_sans_bengali_bold,
    R.font.subtitle_noto_sans_tamil_semibold to R.font.subtitle_noto_sans_tamil_bold,
)

/** libass has no system font lookup here; this name only reaches the library's own fallback font. */
internal const val MPV_RENDERER_DEFAULT_SUBTITLE_FONT = "Droid Sans Fallback"

/**
 * MPV matches a font's internal family or full name, which may differ from its UI label. The bundled Noto files
 * carry an extra full name (Latin) and family name (other scripts), so libass keeps Latin text in Noto Sans and
 * finds the other scripts inside the same requested name.
 */
internal fun SubtitleFont?.mpvFontName(bold: Boolean): String = when (this) {
    null -> MPV_RENDERER_DEFAULT_SUBTITLE_FONT
    SubtitleFont.SOURCE_SANS_3 -> "SourceSans3VF"
    SubtitleFont.DM_SANS -> "DM Sans 9pt"
    SubtitleFont.NOTO_SANS_SEMIBOLD -> if (bold) "Noto Sans Subtitles Bold" else "Noto Sans Subtitles"
    else -> displayName
}

/** Noto picks its bold file by name; asking libass for bold too would select by weight across scripts. */
internal fun SubtitleFont?.mpvBold(bold: Boolean): Boolean = bold && this != SubtitleFont.NOTO_SANS_SEMIBOLD

internal fun mpvSubtitleFontResources(): List<Int> =
    SubtitleFont.entries.flatMap { listOfNotNull(it.fontResource(), it.boldFontResource()) } +
        NOTO_SUBTITLE_SCRIPT_FONTS.flatMap { listOf(it.first, it.second) } +
        listOf(R.font.atkinson_hyperlegible_next_italic, R.font.source_sans3_italic)

private val notoTypefaces = ConcurrentHashMap<Boolean, Typeface>()

/** Cached Android resources; never resolved from the UI font or downloaded. */
internal fun subtitleTypeface(context: Context?, font: SubtitleFont?, bold: Boolean): Typeface {
    if (font == null) return if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    val ctx = requireNotNull(context)
    if (font == SubtitleFont.NOTO_SANS_SEMIBOLD && Build.VERSION.SDK_INT >= 29) {
        runCatching { notoTypefaces.getOrPut(bold) { notoSubtitleTypeface(ctx, bold) } }.getOrNull()?.let { return it }
    }
    val face = font.face(bold)
    val base = ResourcesCompat.getFont(ctx, face.resource)
        ?: return if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    if (face.weightFromFile) return base
    return if (Build.VERSION.SDK_INT >= 28) Typeface.create(base, face.weight, false)
    else Typeface.create(base, if (bold) Typeface.BOLD else Typeface.NORMAL)
}

@RequiresApi(29)
private fun notoSubtitleTypeface(context: Context, bold: Boolean): Typeface {
    fun family(regular: Int, boldRes: Int) = FontFamily.Builder(
        Font.Builder(context.resources, regular).setWeight(600).build()
    ).addFont(Font.Builder(context.resources, boldRes).setWeight(700).build()).build()
    val builder = Typeface.CustomFallbackBuilder(family(R.font.subtitle_noto_sans_semibold, R.font.subtitle_noto_sans_bold))
    NOTO_SUBTITLE_SCRIPT_FONTS.forEach { (regular, boldRes) -> builder.addCustomFallback(family(regular, boldRes)) }
    return builder.setSystemFallback("sans-serif")
        .setStyle(FontStyle(subtitleFontWeight(SubtitleFont.NOTO_SANS_SEMIBOLD, bold), FontStyle.FONT_SLANT_UPRIGHT))
        .build()
}

/** libass/MPV must discover the actual bundled fonts, not just receive a name. */
@Synchronized
internal fun prepareMpvSubtitleFonts(context: Context): File {
    val directory = File(context.filesDir, "subtitle-fonts-v1")
    check(directory.isDirectory || directory.mkdirs())
    for (resource in mpvSubtitleFontResources()) {
        val bytes = context.resources.openRawResource(resource).use { it.readBytes() }
        val target = File(directory, "${context.resources.getResourceEntryName(resource)}.ttf")
        if (!target.isFile || !target.readBytes().contentEquals(bytes)) {
            val temporary = File(directory, "${target.name}.tmp")
            temporary.writeBytes(bytes)
            check(temporary.renameTo(target)) { "Could not install bundled subtitle font" }
        }
    }
    return directory
}
