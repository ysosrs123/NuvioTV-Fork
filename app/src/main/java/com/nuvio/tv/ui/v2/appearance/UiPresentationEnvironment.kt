package com.nuvio.tv.ui.v2.appearance

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nuvio.tv.domain.model.DeviceUiPreferences
import com.nuvio.tv.domain.model.InterfaceExperience
import com.nuvio.tv.domain.model.V2AppearancePreferences
import com.nuvio.tv.ui.v2.diagnostics.UiCanvasSnapshot
import com.nuvio.tv.ui.v2.diagnostics.readUiCanvasSnapshot
import com.nuvio.tv.ui.v2.scale.UiScaleDecision
import com.nuvio.tv.ui.v2.scale.UiScaleResolver

val LocalV2Appearance = staticCompositionLocalOf<V2AppearancePreferences?> { null }
val LocalUiScaleDecision = staticCompositionLocalOf { UiScaleDecision(100, "Original Nuvio") }
val LocalDeviceUiPreferences = staticCompositionLocalOf { DeviceUiPreferences() }
val LocalResolvedAppearance = staticCompositionLocalOf<ResolvedAppearance?> { null }

data class ResolvedAppearance(
    val device: DeviceUiPreferences,
    val appearance: V2AppearancePreferences,
    val uiScale: UiScaleDecision
)

@Composable
fun rememberUiCanvasSnapshot(): UiCanvasSnapshot {
    val context = LocalContext.current
    val view = LocalView.current.rootView
    val configuration = LocalConfiguration.current
    var snapshot by remember(view) { mutableStateOf(readUiCanvasSnapshot(context, view)) }
    DisposableEffect(view, configuration.densityDpi) {
        val update = Runnable { snapshot = readUiCanvasSnapshot(context, view) }
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> update.run() }
        view.addOnLayoutChangeListener(listener)
        view.post(update)
        onDispose {
            view.removeCallbacks(update)
            view.removeOnLayoutChangeListener(listener)
        }
    }
    return snapshot
}

@Composable
fun rememberStableUiScale(
    canvas: UiCanvasSnapshot,
    preferences: DeviceUiPreferences,
    originalScale: Int,
    playbackActive: Boolean
): UiScaleDecision {
    val resolver = remember { UiScaleResolver() }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    fun resolve() = if (preferences.interfaceExperience == InterfaceExperience.ORIGINAL_NUVIO) {
        UiScaleDecision(originalScale, "Original Nuvio")
    } else resolver.resolve(canvas, preferences)
    var decision by remember { mutableStateOf(resolve()) }
    // Output mode/refresh rate and visual quality deliberately are not geometry inputs.
    LaunchedEffect(
        canvas.windowWidthPx, canvas.windowHeightPx, canvas.baseDensity,
        preferences.interfaceExperience, preferences.uiScaleMode,
        preferences.manualUiScalePercent, preferences.autoScaleFineTunePercent,
        originalScale, playbackActive, lifecycleState
    ) {
        // External players pause this Activity; defer their display changes until return too.
        if (!playbackActive && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) decision = resolve()
    }
    return decision
}
