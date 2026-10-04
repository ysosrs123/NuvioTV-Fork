package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import androidx.annotation.FontRes
import androidx.core.content.res.ResourcesCompat
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.AppFont
import java.io.File

@FontRes
internal fun AppFont.fontResource(): Int = when (this) {
    AppFont.INTER -> R.font.inter_variable
    AppFont.DM_SANS -> R.font.dm_sans_variable
    AppFont.OPEN_SANS -> R.font.opensans_variable
    AppFont.ATKINSON_HYPERLEGIBLE_NEXT -> R.font.atkinson_hyperlegible_next_variable
    AppFont.SOURCE_SANS_3 -> R.font.source_sans3_variable
}

/** MPV matches a font's internal family, which may differ from its UI label. */
internal fun AppFont.mpvFontName(): String = when (this) {
    AppFont.SOURCE_SANS_3 -> "SourceSans3VF"
    else -> displayName
}

/** Cached Android resources; never resolved from the UI font or downloaded. */
internal fun subtitleTypeface(context: Context?, font: AppFont?, bold: Boolean): Typeface {
    val base = font?.let { ResourcesCompat.getFont(requireNotNull(context), it.fontResource()) }
    if (base == null) return if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    return if (Build.VERSION.SDK_INT >= 28) Typeface.create(base, if (bold) 700 else 400, false)
    else Typeface.create(base, if (bold) Typeface.BOLD else Typeface.NORMAL)
}

/** libass/MPV must discover the actual bundled fonts, not just receive a name. */
@Synchronized
internal fun prepareMpvSubtitleFonts(context: Context): File {
    val directory = File(context.filesDir, "subtitle-fonts-v1")
    check(directory.isDirectory || directory.mkdirs())
    val resources = AppFont.entries.map { it.fontResource() } +
        listOf(R.font.atkinson_hyperlegible_next_italic, R.font.source_sans3_italic)
    for (resource in resources) {
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
