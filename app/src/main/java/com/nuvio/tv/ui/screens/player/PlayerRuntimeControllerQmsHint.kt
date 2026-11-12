package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import com.nuvio.tv.R
import com.nuvio.tv.core.player.DisplayRefreshMeter
import com.nuvio.tv.data.local.FrameRateMatchingMode
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A reading that is off brings the display info back with the measured rate, and arms the one-time card. */
internal fun PlayerRuntimeController.observeDisplayRefresh() {
    scope.launch {
        DisplayRefreshMeter.readings.collect { reading ->
            if (!DisplayRefreshMeter.differs(reading)) {
                refreshMismatch = null
                return@collect
            }
            refreshMismatch = reading
            val shown = _uiState.value.displayModeInfo
            onEvent(
                PlayerEvent.OnShowDisplayModeInfo(
                    DisplayModeInfo(
                        width = shown?.width ?: 0,
                        height = shown?.height ?: 0,
                        refreshRate = reading.androidHz,
                        statusMessage = context.getString(
                            R.string.display_mode_measured_off,
                            DisplayRefreshMeter.format(reading.measuredHz),
                            DisplayRefreshMeter.format(reading.androidHz)
                        ),
                        measuredHz = reading.measuredHz,
                        seamless = reading.seamless == true
                    )
                ),
                showPlaybackControls = false
            )
        }
    }
}

/** Called when the viewer pauses: the card never covers a playing film, and comes once per TV. */
internal fun PlayerRuntimeController.maybeShowQmsHint() {
    val reading = refreshMismatch ?: return
    if (_uiState.value.qmsHint != null) return
    if (!QmsHintMemory.takeFirstTime(context)) return
    _uiState.update { it.copy(qmsHint = reading) }
}

internal fun PlayerRuntimeController.qmsHintAnswered(turnOff: Boolean) {
    _uiState.update { it.copy(qmsHint = null) }
    if (turnOff) scope.launch { playerSettingsDataStore.setFrameRateMatchingMode(FrameRateMatchingMode.OFF) }
}

internal object QmsHintMemory {
    private const val PREFS = "arc_display_hints"

    /** True the first time for the connected TV, false ever after. */
    fun takeFirstTime(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "qms_told_" + displayKey(context)
        if (prefs.getBoolean(key, false)) return false
        prefs.edit().putBoolean(key, true).apply()
        return true
    }

    private fun displayKey(context: Context): String = runCatching {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        val product = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) display?.deviceProductInfo else null
        keyOf(product?.manufacturerPnpId, product?.productId, product?.name ?: display?.name)
    }.getOrDefault("screen")

    internal fun keyOf(maker: String?, product: String?, name: String?): String =
        listOf(maker, product, name).map { it.orEmpty().trim() }.joinToString("|").ifBlank { "screen" }
            .let { if (it == "||") "screen" else it }
}
