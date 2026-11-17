package com.nuvio.tv.core.performance

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nuvio.tv.data.local.LauncherAccessibilityPreferences

/**
 * Launchers and remappers whose accessibility service is only there for their own key handling and reads
 * nothing in Arc. Add a package only once it is known what its service uses.
 */
private val LAUNCHER_SERVICE_PACKAGES = setOf("com.spocky.projengmenu")

/** True when every enabled service belongs to a launcher and none explores the screen by touch. */
internal fun onlyLauncherServices(servicePackages: List<String>, touchExploration: Boolean): Boolean =
    !touchExploration && servicePackages.isNotEmpty() && servicePackages.all { it in LAUNCHER_SERVICE_PACKAGES }

/**
 * Any enabled accessibility service makes Compose keep its description of the screen up to date on every change,
 * which costs Home a third or more of its late frames on a launcher like Projectivy. When the only services are
 * launchers' and the setting allows it, Arc leaves that description empty. A screen reader turns it back on.
 */
internal fun launcherOnlyNow(context: Context): Boolean {
    val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
    return launcherOnly(
        settingOn = LauncherAccessibilityPreferences(context).quietForLaunchers,
        accessibilityOn = manager.isEnabled,
        servicePackages = {
            // The settings list also holds services switched on but not running yet.
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .map { it.resolveInfo?.serviceInfo?.packageName.orEmpty() } +
                enabledServicePackages(
                    Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                )
        },
        touchExploration = manager.isTouchExplorationEnabled
    )
}

/** Packages in the system's "package/class:package/class" list of enabled accessibility services. */
internal fun enabledServicePackages(setting: String?): List<String> =
    setting.orEmpty().split(':').map { it.substringBefore('/').trim() }.filter { it.isNotEmpty() }

internal fun launcherOnly(
    settingOn: Boolean,
    accessibilityOn: Boolean,
    servicePackages: () -> List<String>,
    touchExploration: Boolean
): Boolean = settingOn && accessibilityOn && onlyLauncherServices(servicePackages(), touchExploration)

/**
 * [launcherOnlyNow], checked again whenever accessibility is switched on or off, touch exploration changes, the
 * system's list of enabled services changes (also when the remote's accessibility shortcut is pressed in Arc), the
 * setting changes, or Arc comes back to the front.
 */
@Composable
internal fun rememberLauncherOnlyAccessibility(): State<Boolean> {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = remember(context) { mutableStateOf(launcherOnlyNow(context)) }
    DisposableEffect(context, lifecycleOwner) {
        val update = { state.value = launcherOnlyNow(context) }
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val onState = AccessibilityManager.AccessibilityStateChangeListener { update() }
        val onTouch = AccessibilityManager.TouchExplorationStateChangeListener { update() }
        manager?.addAccessibilityStateChangeListener(onState)
        manager?.addTouchExplorationStateChangeListener(onTouch)
        val onServices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && manager != null) {
            AccessibilityManager.AccessibilityServicesStateChangeListener { update() }
                .also { manager.addAccessibilityServicesStateChangeListener(context.mainExecutor, it) }
        } else {
            null
        }
        val onSystemSetting = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = update()
        }
        listOf(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, Settings.Secure.ACCESSIBILITY_ENABLED).forEach {
            context.contentResolver.registerContentObserver(Settings.Secure.getUriFor(it), false, onSystemSetting)
        }
        val preferences = LauncherAccessibilityPreferences(context)
        val onSetting = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == LauncherAccessibilityPreferences.KEY_QUIET_FOR_LAUNCHERS) update()
        }
        preferences.register(onSetting)
        val onResume = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) update() }
        lifecycleOwner.lifecycle.addObserver(onResume)
        update()
        onDispose {
            manager?.removeAccessibilityStateChangeListener(onState)
            manager?.removeTouchExplorationStateChangeListener(onTouch)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && onServices != null) {
                manager?.removeAccessibilityServicesStateChangeListener(onServices)
            }
            context.contentResolver.unregisterContentObserver(onSystemSetting)
            preferences.unregister(onSetting)
            lifecycleOwner.lifecycle.removeObserver(onResume)
        }
    }
    return state
}
