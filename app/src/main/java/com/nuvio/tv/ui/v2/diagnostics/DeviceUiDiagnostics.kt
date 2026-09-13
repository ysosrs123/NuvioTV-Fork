package com.nuvio.tv.ui.v2.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.view.View
import com.nuvio.tv.BuildConfig
import java.util.Locale

/** Capture from the Activity view before opening a dialog, whose window has different bounds. */
fun deviceUiDiagnosticReport(context: Context, view: View, scalePercent: Int): String {
    val metrics = context.resources.displayMetrics
    val configuration = context.resources.configuration
    val root = view.rootView
    val mode = view.display?.mode
    val canvas = UiCanvasSnapshot(
        windowWidthPx = root.width,
        windowHeightPx = root.height,
        baseDensity = metrics.density,
        densityDpi = metrics.densityDpi,
        screenWidthDp = configuration.screenWidthDp,
        screenHeightDp = configuration.screenHeightDp,
        modeId = mode?.modeId,
        outputWidthPx = mode?.physicalWidth,
        outputHeightPx = mode?.physicalHeight,
        refreshRateHz = mode?.refreshRate
    )
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val memory = manager?.let { ActivityManager.MemoryInfo().also(it::getMemoryInfo) }
    val frames = UiFrameDiagnostics.frames.snapshot(System.nanoTime())
    fun number(value: Number?): String = value?.let { String.format(Locale.US, "%.2f", it.toDouble()) } ?: "unavailable"
    return buildString {
        appendLine("${Build.MODEL} / API ${Build.VERSION.SDK_INT}")
        appendLine("Nuvio ${BuildConfig.VERSION_NAME} / ${BuildConfig.BUILD_TYPE}")
        appendLine("App window: ${canvas.windowWidthPx} x ${canvas.windowHeightPx} px")
        appendLine("Density: ${number(canvas.baseDensity)} / ${canvas.densityDpi} dpi")
        appendLine("Reported screen: ${canvas.screenWidthDp} x ${canvas.screenHeightDp} dp")
        appendLine("Base canvas: ${number(canvas.logicalWidthDp)} x ${number(canvas.logicalHeightDp)} dp")
        appendLine("UI scale: $scalePercent% (current Original renderer)")
        appendLine("Effective canvas: ${number(canvas.effectiveWidthDp(scalePercent))} x ${number(canvas.effectiveHeightDp(scalePercent))} dp")
        appendLine("Output mode ${canvas.modeId ?: "unavailable"}: ${canvas.outputWidthPx ?: "?"} x ${canvas.outputHeightPx ?: "?"} @ ${number(canvas.refreshRateHz)} Hz")
        appendLine("RAM: ${number(memory?.totalMem?.div(1_073_741_824.0))} GiB / heap: ${manager?.memoryClass ?: "?"} MiB / low RAM: ${manager?.isLowRamDevice ?: "?"}")
        appendLine("Hardware acceleration: ${view.isHardwareAccelerated}")
        appendLine("Recent frames (up to 30 s / 2048 frames): ${frames.frames}")
        appendLine("JankStats jank: ${number(frames.jankPercent)}%")
        appendLine("UI-thread ms P50 / P95 / P99: ${number(frames.p50UiMs)} / ${number(frames.p95UiMs)} / ${number(frames.p99UiMs)}")
        append("Local mixed-screen samples; not GPU timing or a release benchmark.")
    }
}
