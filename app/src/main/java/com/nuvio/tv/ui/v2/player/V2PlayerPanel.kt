package com.nuvio.tv.ui.v2.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.components.GlassRole
import com.nuvio.tv.ui.v2.components.nuvioGlass

/** Static player material; no video backdrop capture or new Android window. */
@Composable
internal fun Modifier.v2PlayerPanel(): Modifier =
    if (LocalV2Appearance.current == null) background(Color.Black.copy(alpha = .85f), RoundedCornerShape(20.dp))
    else nuvioGlass(GlassRole.PANEL, shape = RoundedCornerShape(20.dp))
        .focusProperties { onExit = { cancelFocusChange() } }.focusGroup()
