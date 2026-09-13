package com.nuvio.tv.baselineprofile

import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.view.KeyEvent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Optimized, populated-content D-pad journeys. Prepare the isolated app before running. */
@RunWith(AndroidJUnit4::class)
class NuvioNavigationBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()
    private val arguments = InstrumentationRegistry.getArguments()
    private val target = arguments.getString("nuvioPackage", "com.nuvio.tv.v2.validation")
        .also { require(it in setOf("com.nuvio.tv.v2.validation", "com.nuvio.tv.v2.baseline")) }
    private val iterations = arguments.getString("nuvioIterations", "5").toInt().also { require(it in 3..20) }
    private val homeRow = arguments.getString("nuvioHomeRow", "Popular - Movie")
    // Resetting compilation on pre-34 devices reinstalls the target and erases prepared data.
    // The host runner explicitly compiles with `cmd package compile -m speed -f` first.
    @OptIn(androidx.benchmark.macro.ExperimentalMacrobenchmarkApi::class)
    private fun compilation(): CompilationMode {
        check(arguments.getString("nuvioCompilation") == "speed") { "Use scripts/perf_baseline.py to prepare compilation" }
        return CompilationMode.Ignore()
    }

    private fun requireBrowsingDisplay() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val mode = context.getSystemService(DisplayManager::class.java).getDisplay(0).mode
        check(mode.refreshRate in 58f..62f) { "Browsing benchmark requires an already selected ~60 Hz mode: $mode" }
    }

    private fun MacrobenchmarkScope.openHome() {
        startActivityAndWait(Intent(Intent.ACTION_MAIN).apply {
            setClassName(target, "com.nuvio.tv.MainActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        check(device.wait(Until.hasObject(By.text(homeRow)), 30_000) == true) {
            "Prepare guest/Advanced onboarding and populated Home in $target; missing $homeRow"
        }
        device.waitForIdle()
        SystemClock.sleep(1_500) // Artwork/focus settling belongs outside the measured journey.
    }

    private fun MacrobenchmarkScope.key(code: Int, pauseMs: Long = 120) {
        device.pressKeyCode(code)
        SystemClock.sleep(pauseMs)
    }

    private fun measure(journey: MacrobenchmarkScope.() -> Unit) {
        requireBrowsingDisplay()
        benchmark.measureRepeated(
            packageName = target,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = compilation(),
            iterations = iterations,
            startupMode = StartupMode.WARM,
            setupBlock = { pressHome(); openHome() },
            measureBlock = {
                journey()
                check(device.hasObject(By.pkg(target))) { "Journey left the validation app" }
            }
        )
    }

    @Test fun coldStart() {
        requireBrowsingDisplay()
        benchmark.measureRepeated(
            packageName = target,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = compilation(),
            iterations = iterations,
            startupMode = StartupMode.COLD,
            setupBlock = { pressHome() },
            measureBlock = { openHome() }
        )
    }

    @Test fun horizontalRow() = measure {
        repeat(12) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
        repeat(12) { key(KeyEvent.KEYCODE_DPAD_LEFT) }
    }

    @Test fun verticalRows() = measure {
        repeat(5) {
            key(KeyEvent.KEYCODE_DPAD_DOWN, 180)
            key(KeyEvent.KEYCODE_DPAD_UP, 180)
        }
    }

    @Test fun sidebar() = measure {
        repeat(6) {
            key(KeyEvent.KEYCODE_DPAD_LEFT, 250)
            key(KeyEvent.KEYCODE_DPAD_RIGHT, 250)
        }
    }

    @Test fun details() = measure {
        key(KeyEvent.KEYCODE_DPAD_CENTER, 500)
        check(device.wait(Until.hasObject(By.text("Play")), 15_000) == true) { "Details did not expose Play" }
        key(KeyEvent.KEYCODE_DPAD_DOWN, 250)
        key(KeyEvent.KEYCODE_DPAD_UP, 250)
        key(KeyEvent.KEYCODE_BACK, 500)
        check(device.wait(Until.hasObject(By.text(homeRow)), 5_000) == true)
    }

    @Test fun settings() = measure {
        key(KeyEvent.KEYCODE_DPAD_LEFT, 300)
        repeat(3) { key(KeyEvent.KEYCODE_DPAD_DOWN, 180) }
        key(KeyEvent.KEYCODE_DPAD_CENTER, 500)
        check(device.wait(Until.hasObject(By.text("Account and sync status")), 5_000) == true)
        repeat(2) { key(KeyEvent.KEYCODE_DPAD_DOWN, 180) }
        key(KeyEvent.KEYCODE_DPAD_CENTER, 500)
        check(device.wait(Until.hasObject(By.text("Color Theme")), 5_000) == true)
        key(KeyEvent.KEYCODE_BACK, 250)
        key(KeyEvent.KEYCODE_BACK, 500)
    }
}
