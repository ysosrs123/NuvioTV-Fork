package com.nuvio.tv.ui.v2.components

/** Body opacity only. Highlights, focus and actual blur remain independent of this control.
 * Percent is a relative transparency preference; role-specific floors preserve text contrast.
 */
internal fun glassBodyAlpha(role: GlassRole, playback: Boolean, legacyAlpha: Float, transparency: Int): Float {
    val dense = role == GlassRole.HUD || role == GlassRole.MODAL
    // Never change body opacity just because blur is disabled or unavailable.
    val base = if (playback) legacyAlpha else when (role) {
        GlassRole.HUD -> .82f
        GlassRole.MODAL -> .62f
        GlassRole.NAVIGATION, GlassRole.CONTROL -> .48f
        else -> .50f
    }
    val minimum = when {
        role == GlassRole.HUD -> .28f
        role == GlassRole.MODAL -> .36f
        role == GlassRole.CONTROL -> .10f
        else -> .16f
    }
    val maximum = if (dense) .88f else if (playback) .68f else .86f
    return (base * (100 - transparency.coerceIn(0, 100)) / 40f).coerceIn(minimum, maximum)
}

/** Body opacity of a control sitting on moving video, where there is nothing still to frost. */
internal fun smokedGlassBodyAlpha(transparency: Int): Float =
    (.62f * (100 - transparency.coerceIn(0, 100)) / 40f).coerceIn(.50f, .86f)

/** Extra neutral fill that lifts a body of [bodyAlpha] to the smoked opacity. */
internal fun smokedGlassFillAlpha(bodyAlpha: Float, transparency: Int): Float {
    val target = smokedGlassBodyAlpha(transparency)
    if (bodyAlpha >= target) return 0f
    return 1f - (1f - target) / (1f - bodyAlpha)
}
