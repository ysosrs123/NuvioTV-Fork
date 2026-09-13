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
import com.nuvio.tv.ui.v2.diagnostics.UiCanvasSnapshot

@Composable
fun rememberDeviceVisualQuality(canvas: UiCanvasSnapshot, preferences: DeviceUiPreferences): VisualQualityDecision {
    val context = LocalContext.current
    val view = LocalView.current.rootView
    var accelerated by remember(view) { mutableStateOf(view.isHardwareAccelerated) }
    DisposableEffect(view) {
        val refresh = Runnable { accelerated = view.isHardwareAccelerated }
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
    // Version/root/capability changes invalidate it. No historical-jank adaptation is enabled yet.
    val assessmentKey = remember(capabilities) { "1:${BuildConfig.VERSION_CODE}:$capabilities" }
    LaunchedEffect(assessmentKey, automatic.tier) {
        if (preferences.qualityAssessmentKey != assessmentKey || preferences.automaticQualityTier != automatic.tier.name) {
            DeviceUiPreferenceStore.update(context) {
                it.copy(qualityAssessmentKey = assessmentKey, automaticQualityTier = automatic.tier.name)
            }
        }
    }
    return remember(preferences.visualQualityMode, capabilities) {
        VisualQualityResolver.resolve(preferences.visualQualityMode, capabilities)
    }
}
