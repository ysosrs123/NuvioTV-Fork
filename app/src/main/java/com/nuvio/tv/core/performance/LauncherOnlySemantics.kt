package com.nuvio.tv.core.performance

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityManager

private const val TEST_TAG = "ArcSemanticsTest"

/** Launchers that keep an accessibility service on for their own key handling and read nothing in Arc. */
private val LAUNCHER_SERVICE_PACKAGES = setOf("com.spocky.projengmenu")

/** True when every enabled service belongs to a launcher and none explores the screen by touch. */
internal fun onlyLauncherServices(servicePackages: List<String>, touchExploration: Boolean): Boolean =
    !touchExploration && servicePackages.isNotEmpty() && servicePackages.all { it in LAUNCHER_SERVICE_PACKAGES }

/**
 * Measurement switch, off unless `log.tag.ArcSemanticsTest` is set to VERBOSE on the box. Any enabled
 * accessibility service makes Compose keep its semantics tree current on every layout change. When
 * the only such service is a launcher's, the tree can be dropped for the length of the test.
 */
fun launcherOnlySemanticsTest(context: Context): Boolean {
    if (!Log.isLoggable(TEST_TAG, Log.VERBOSE)) return false
    val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
    if (!manager.isEnabled) return false
    val packages = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .map { it.resolveInfo?.serviceInfo?.packageName.orEmpty() }
    return onlyLauncherServices(packages, manager.isTouchExplorationEnabled)
}
