package com.nuvio.tv.baselineprofile

import android.content.Intent
import android.view.KeyEvent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val ONBOARDING_FIRST_LABEL = "Continue without account"

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generateStartup() {
        val targetPackage = InstrumentationRegistry.getArguments()
            .getString("nuvioPackage", "com.nuvio.tv.v2.validation")
        require(targetPackage in setOf("com.nuvio.tv.v2.validation", "com.nuvio.tv.v2.baseline"))
        rule.collect(packageName = targetPackage, includeInStartupProfile = true, maxIterations = 10,
            filterPredicate = { it.contains("Lcom/nuvio/tv/") }) {
            openPreparedHome(targetPackage)
        }
    }

    @Test
    fun generateBrowsing() {
        val targetPackage = InstrumentationRegistry.getArguments()
            .getString("nuvioPackage", "com.nuvio.tv.v2.validation")
        require(targetPackage in setOf("com.nuvio.tv.v2.validation", "com.nuvio.tv.v2.baseline"))
        rule.collect(
            packageName = targetPackage,
            includeInStartupProfile = false,
            maxIterations = 10,
            filterPredicate = { it.contains("Lcom/nuvio/tv/") }
        ) {
            openPreparedHome(targetPackage)

            // Move focus off the sidebar into the content grid.
            device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_RIGHT)
            device.waitForIdle()

            // Scroll across the focused row to compile the row and card rendering paths.
            repeat(6) {
                device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_RIGHT)
                device.waitForIdle()
                Thread.sleep(120)
            }

            // Return along the row so the following vertical pairs end on a title.
            repeat(6) {
                device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_LEFT)
                device.waitForIdle()
                Thread.sleep(120)
            }
            // Balanced pairs avoid finishing on See All or a loading/footer action.
            repeat(5) {
                device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_DOWN)
                device.waitForIdle()
                Thread.sleep(150)
                device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_UP)
                device.waitForIdle()
                Thread.sleep(150)
            }

            // Open a title to compile the detail screen render path.
            device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_CENTER)
            device.waitForIdle()
            check(device.wait(Until.hasObject(By.text("Play")), 15_000) == true) {
                "Profile journey did not reach Details"
            }
            Thread.sleep(2_500)

            // Return to the home screen.
            device.profileKey(targetPackage, KeyEvent.KEYCODE_BACK)
            device.waitForIdle()
            check(device.wait(Until.hasObject(By.text("Popular - Movie")), 5_000) == true)
            Thread.sleep(800)
        }
    }
}

// Startup layout is collected separately from browsing to avoid placing every
// navigation path in the primary startup DEX layout.
private fun MacrobenchmarkScope.openPreparedHome(targetPackage: String) {
    pressHome()
    startActivityAndWait(Intent(Intent.ACTION_MAIN).apply {
        setClassName(targetPackage, "com.nuvio.tv.MainActivity")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    })
    device.wait(Until.hasObject(By.pkg(targetPackage)), 5_000)
    device.waitForIdle()
    if (device.wait(Until.hasObject(By.text(ONBOARDING_FIRST_LABEL)), 2_000) == true) {
        check(device.focusAndSelect(ONBOARDING_FIRST_LABEL))
        check(device.focusAndSelect("Advanced"))
    }
    if (device.wait(Until.hasObject(By.text("Popular - Movie")), 30_000) != true) {
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        if (output != null) {
            val directory = File(output).apply { mkdirs() }
            val name = "home-failure-${System.currentTimeMillis()}"
            device.takeScreenshot(File(directory, "$name.png"))
            device.dumpWindowHierarchy(File(directory, "$name.xml"))
        }
        error("Prepare the isolated validation app with English labels and populated Home; foreground=${device.currentPackageName}")
    }
    device.waitForIdle()
    Thread.sleep(3_000)
}

// Moves D-pad focus until the control carrying the given label is focused and then selects it, since TV devices have no touch input.
private fun UiDevice.focusAndSelect(label: String, maxSteps: Int = 24): Boolean {
    val pattern = Pattern.compile(Pattern.quote(label), Pattern.CASE_INSENSITIVE)
    if (wait(Until.hasObject(By.text(pattern)), 10_000) != true) return false
    repeat(maxSteps) { step ->
        if (isFocusedLabel(label, pattern)) {
            pressDPadCenter()
            waitForIdle()
            Thread.sleep(1_500)
            // Treat the step as done only once the label is gone, so a mis-selection keeps searching instead of moving on.
            if (wait(Until.gone(By.text(pattern)), 5_000) == true) return true
        }
        // Alternate directions so both vertical lists and side-by-side cards are reachable.
        if (step % 2 == 0) pressDPadDown() else pressDPadRight()
        waitForIdle()
        Thread.sleep(250)
    }
    return false
}

// Reports whether the currently focused control carries the label, treating nodes that disappear mid-query as a non-match.
private fun UiDevice.isFocusedLabel(label: String, pattern: Pattern): Boolean {
    return try {
        val focused = findObject(By.focused(true)) ?: return false
        if (focused.text?.equals(label, ignoreCase = true) == true) return true
        // Require the focused node to be the clickable control itself so a large parent container is not mistaken for the button.
        focused.isClickable && focused.findObject(By.text(pattern)) != null
    } catch (e: StaleObjectException) {
        false
    }
}

private fun UiDevice.profileKey(targetPackage: String, key: Int) {
    check(currentPackageName == targetPackage) { "Another app took focus during profile collection" }
    pressKeyCode(key)
}
