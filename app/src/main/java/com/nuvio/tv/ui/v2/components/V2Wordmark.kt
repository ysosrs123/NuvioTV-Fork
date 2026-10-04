package com.nuvio.tv.ui.v2.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.nuvio.tv.ui.components.BrandWordmark

/** All surfaces use the same official, theme-aware wordmark asset. */
@Composable
fun V2Wordmark(modifier: Modifier = Modifier, size: TextUnit = 32.sp) {
    BrandWordmark(modifier = modifier, contentDescription = "Nuvio")
}
