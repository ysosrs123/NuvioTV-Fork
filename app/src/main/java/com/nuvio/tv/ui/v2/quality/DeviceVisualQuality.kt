package com.nuvio.tv.ui.v2.quality

import android.app.ActivityManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.local.DeviceUiPreferenceStore
import com.nuvio.tv.domain.model.DeviceUiPreferences
import com.nuvio.tv.domain.model.VisualQualityMode
import com.nuvio.tv.ui.v2.diagnostics.UiCanvasSnapshot
import kotlinx.coroutines.delay

@Composable
fun rememberDeviceVisualQuality(canvas: UiCanvasSnapshot, preferences: DeviceUiPreferences, governor: AutomaticQualityGovernor): VisualQualityDecision {
    val context = LocalContext.current
    val view = LocalView.current.rootView
    var rootReady by remember(view) { mutableStateOf(false) }
    var accelerated by remember(view) { mutableStateOf(view.isHardwareAccelerated) }
    DisposableEffect(view) {
        val refresh = Runnable { accelerated = view.isHardwareAccelerated; rootReady = true }
        view.post(refresh)
        onDispose { view.removeCallbacks(refresh) }
    }
    val manager = remember(context) { context.getSystemService(ActivityManager::class.java) }
    val memory = remember(manager) { ActivityManager.MemoryInfo().also { manager?.getMemoryInfo(it) } }
    val capabilities = remember(canvas.windowWidthPx, canvas.windowHeightPx, accelerated, manager) {
        UiRenderCapabilities(
            api = Build.VERSION.SDK_INT,
            lowRam = manager?.isLowRamDevice ?: true,
            totalRamBytes = memory.totalMem,
            heapMb = manager?.memoryClass ?: 0,
            hardwareAccelerated = accelerated,
            rootWidthPx = canvas.windowWidthPx,
            rootHeightPx = canvas.windowHeightPx,
            glEsVersion = manager?.deviceConfigurationInfo?.reqGlEsVersion ?: 0
        )
    }
    val automatic = remember(capabilities) { VisualQualityResolver.automatic(capabilities) }
    // Persist the device-local initial assessment independently of the user's manual mode.
    // A valid lower historical tier survives recomposition and relaunch.
    val assessmentKey = remember(capabilities) { "2:${BuildConfig.VERSION_CODE}:$capabilities" }
    LaunchedEffect(assessmentKey, automatic.tier, rootReady, canvas.hasValidGeometry) {
        // Pre-attachment hardware/root values must not invalidate persisted history.
        if (!rootReady || !canvas.hasValidGeometry) return@LaunchedEffect
        DeviceUiPreferenceStore.update(context) {
            if (it.qualityAssessmentKey != assessmentKey || VisualQualityTier.entries.none { tier -> tier.name == it.automaticQualityTier })
                it.copy(qualityAssessmentKey = assessmentKey, automaticQualityTier = automatic.tier.name)
            else it
        }
    }
    LaunchedEffect(governor, assessmentKey, rootReady, canvas.hasValidGeometry) {
        if (!rootReady || !canvas.hasValidGeometry) return@LaunchedEffect
        while (true) {
            delay(1_000)
            val reduction = governor.takeReduction(System.nanoTime()) ?: continue
            DeviceUiPreferenceStore.update(context) {
                val current = VisualQualityTier.entries.firstOrNull { tier -> tier.name == it.automaticQualityTier }
                if (it.qualityAssessmentKey == assessmentKey && it.visualQualityMode == VisualQualityMode.AUTOMATIC &&
                    current != null && reduction.ordinal < current.ordinal)
                    it.copy(automaticQualityTier = reduction.name)
                else it
            }
        }
    }
    val stored = preferences.automaticQualityTier.takeIf { preferences.qualityAssessmentKey == assessmentKey }
    return remember(preferences.visualQualityMode, capabilities, stored) {
        VisualQualityResolver.resolve(preferences.visualQualityMode, capabilities, stored)
    }
}
