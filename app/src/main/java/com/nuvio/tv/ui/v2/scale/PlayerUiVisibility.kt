package com.nuvio.tv.ui.v2.scale

/** The outgoing player remains visible during NavHost's fade even after currentRoute changes. */
fun isPlayerUiVisible(currentRoute: String?, visibleRoutes: List<String?>): Boolean =
    currentRoute?.startsWith("player/") == true || visibleRoutes.any { it?.startsWith("player/") == true }
