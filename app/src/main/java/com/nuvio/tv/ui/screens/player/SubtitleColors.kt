package com.nuvio.tv.ui.screens.player

import androidx.compose.ui.graphics.Color

/** Shared by both subtitle editors so each renderer offers the same saved colours. */
internal val SubtitleTextColors = listOf(
    Color.White,
    Color(0xFFD9D9D9),
    Color(0xFFFFD700),
    Color(0xFF00E5FF),
    Color(0xFFFF5C5C),
    Color(0xFF00FF88),
    Color(0xFFFFAD42), // Warm orange
    Color(0xFFFFF59D), // Soft yellow
    Color(0xFFB3E5FC), // Pale blue
    Color(0xFFE1BEE7)  // Lavender
)

internal val SubtitleOutlineColors = listOf(
    Color.Black,
    Color.White,
    Color(0xFF00E5FF),
    Color(0xFFFF5C5C),
    Color(0xFF212121), // Charcoal
    Color(0xFF8E44AD)  // Medium purple
)
