package com.nuvio.tv.ui.v2.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.domain.model.VisualStyle
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance

/** Glass is a neutral optical material, separate from White's accent treatment. */
internal fun isClearGlassTheme(theme: AppTheme, style: VisualStyle?): Boolean =
    theme == AppTheme.GLASS && style == VisualStyle.CINEMATIC_GLASS

@Composable
@ReadOnlyComposable
internal fun usesClearGlassTheme(): Boolean =
    isClearGlassTheme(NuvioTheme.currentTheme, LocalV2Appearance.current?.visualStyle)

/** No white/coloured body wash. Dense text surfaces keep a small neutral contrast floor. */
internal fun clearGlassBodyAlpha(role: GlassRole, transparency: Int): Float {
    val (base, minimum, maximum) = when (role) {
        GlassRole.CONTROL -> Triple(.08f, .025f, .20f)
        GlassRole.PANEL -> Triple(.10f, .04f, .25f)
        GlassRole.NAVIGATION -> Triple(.12f, .05f, .30f)
        GlassRole.MODAL -> Triple(.26f, .16f, .50f)
        GlassRole.HUD -> Triple(.24f, .16f, .46f)
        GlassRole.CARD_FOCUS -> Triple(.04f, 0f, .10f)
        GlassRole.TOOLTIP -> Triple(.18f, .12f, .42f)
    }
    return (base * (100 - transparency.coerceIn(0, 100)) / 40f).coerceIn(minimum, maximum)
}
