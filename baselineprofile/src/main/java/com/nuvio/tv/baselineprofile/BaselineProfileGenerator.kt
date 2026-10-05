package com.nuvio.tv.baselineprofile

import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import java.util.regex.Pattern
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "NuvioProfile"
private const val ONBOARDING_FIRST_LABEL = "Continue without account"
private const val STYLE_V2 = "Nuvio V2"
private const val STYLE_ORIGINAL = "Original Nuvio"
private val HOME_ROW: Pattern = Pattern.compile("Popular - (Movie|Series)")
private val DETAILS_MARKER: Pattern = Pattern.compile("Play|Resume.*|Playback unavailable|Add to library|Director: .*")
private val SIDEBAR_LABELS = setOf("Home", "Search", "Library", "Settings")
private val ALLOWED_PACKAGES = setOf("com.nuvio.tv.v2.validation", "com.nuvio.tv.v2.baseline")

private val keepRule: (String) -> Boolean = { it.contains("Lcom/nuvio/tv/") || it.contains("Landroidx/media3/") }

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    private val arguments = InstrumentationRegistry.getArguments()
    private val targetPackage = arguments.getString("nuvioPackage", "com.nuvio.tv.v2.validation")
        .also { require(it in ALLOWED_PACKAGES) }
    private val clipId = arguments.getString("nuvioClipId", "am9dv:fmt-av1-10bit")
    private val clipStream = arguments.getString("nuvioClipStream", "AM9 local")

    private fun collect(iterations: Int, startup: Boolean = false, block: MacrobenchmarkScope.() -> Unit) {
        rule.collect(
            packageName = targetPackage,
            maxIterations = iterations,
            stableIterations = minOf(3, iterations),
            includeInStartupProfile = startup,
            filterPredicate = keepRule,
            profileBlock = block
        )
    }

    // Startup layout is collected separately from browsing so the primary DEX holds only the path to a filled Home.
    @Test
    fun generateStartup() {
        ensureInterface(targetPackage, STYLE_V2)
        collect(iterations = 6, startup = true) { openPreparedHome(targetPackage) }
    }

    @Test
    fun generateHomeV2() {
        ensureInterface(targetPackage, STYLE_V2)
        collect(iterations = 3) { browseHome(targetPackage) }
    }

    @Test
    fun generateHomeOriginal() {
        ensureInterface(targetPackage, STYLE_ORIGINAL)
        collect(iterations = 2) { browseHome(targetPackage) }
    }

    @Test
    fun generateScreens() {
        ensureInterface(targetPackage, STYLE_V2)
        collect(iterations = 2) {
            openPreparedHome(targetPackage)
            visitSearch(targetPackage)
            visitLibrary(targetPackage)
            visitSettings(targetPackage)
            visitProfilePicker(targetPackage)
        }
    }

    @Test
    fun generatePlayer() {
        ensureInterface(targetPackage, STYLE_V2)
        collect(iterations = 3) { playClip(targetPackage, clipId, clipStream) }
    }
}

private fun MacrobenchmarkScope.browseHome(targetPackage: String) {
    openPreparedHome(targetPackage)
    device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_RIGHT)
    device.settle(300)
    repeat(3) { row ->
        repeat(8) { device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_RIGHT); device.settle(140) }
        repeat(8) { device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_LEFT); device.settle(140) }
        if (row < 2) { device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_DOWN); device.settle(300) }
    }
    repeat(4) { device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_DOWN); device.settle(250) }
    repeat(6) { device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_UP); device.settle(250) }
    device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_DOWN)
    device.settle(400)
    openDetailsFromHome(targetPackage)

    repeat(3) { device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_DOWN); device.settle(500) }
    repeat(3) { device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_UP); device.settle(300) }
    device.profileKey(targetPackage, KeyEvent.KEYCODE_BACK)
    check(device.wait(Until.hasObject(By.text(HOME_ROW)), 8_000) == true) { "Back from Details did not return to Home" }
    device.settle(600)

    if (device.openSidebar(targetPackage)) {
        repeat(3) { device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_DOWN); device.settle(250) }
        repeat(3) { device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_UP); device.settle(250) }
        device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_RIGHT)
        device.settle(500)
    }
}

// A Continue watching card opens the source list instead of Details, so step down a row and try again.
private fun MacrobenchmarkScope.openDetailsFromHome(targetPackage: String) {
    repeat(3) {
        device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_CENTER)
        if (device.wait(Until.hasObject(By.text(DETAILS_MARKER)), 10_000) == true) {
            device.settle(2_000)
            return
        }
        device.profileKey(targetPackage, KeyEvent.KEYCODE_BACK)
        device.wait(Until.hasObject(By.text(HOME_ROW)), 5_000)
        device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_DOWN)
        device.settle(500)
    }
    saveFailure("details")
    error("Profile journey did not reach Details")
}

private fun MacrobenchmarkScope.visitSearch(targetPackage: String) {
    check(device.openSection(targetPackage, "Search")) { "Search not reachable" }
    val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)
    if (field == null) {
        Log.w(TAG, "Search field not found")
        return
    }
    runCatching { field.text = "batman" }.onFailure { Log.w(TAG, "Search text not set", it) }
    device.wait(Until.hasObject(By.desc(Pattern.compile(".*Batman.*", Pattern.CASE_INSENSITIVE))), 10_000)
    device.settle(1_000)
    device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_DOWN)
    device.settle(400)
    repeat(4) { device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_RIGHT); device.settle(200) }
    repeat(2) { device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_DOWN); device.settle(300) }
    repeat(2) { device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_UP); device.settle(300) }
}

private fun MacrobenchmarkScope.visitLibrary(targetPackage: String) {
    check(device.openSection(targetPackage, "Library")) { "Library not reachable" }
    device.settle(1_500)
    repeat(2) { device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_RIGHT); device.settle(300) }
    repeat(2) { device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_DOWN); device.settle(300) }
}

private fun MacrobenchmarkScope.visitSettings(targetPackage: String) {
    check(device.openSection(targetPackage, "Settings")) { "Settings not reachable" }
    check(device.wait(Until.hasObject(By.text("Account and sync status")), 10_000) == true) { "Settings did not open" }
    device.settle(800)
    // Focusing a rail entry renders its page inline.
    repeat(9) { device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_DOWN); device.settle(700) }

    if (device.railTo(targetPackage, "Playback")) {
        device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_RIGHT)
        device.settle(400)
        repeat(6) { device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_DOWN); device.settle(300) }
        device.backToSettingsRail(targetPackage)
    }
    if (device.railTo(targetPackage, "Content & discovery")) {
        device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_RIGHT)
        device.settle(400)
        if (device.moveFocusTo(targetPackage, "Addons", KeyEvent.KEYCODE_DPAD_UP, 4)) {
            device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_CENTER)
            if (device.wait(Until.hasObject(By.text("Install addon")), 5_000) == true) {
                device.settle(800)
                repeat(3) { device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_DOWN); device.settle(300) }
                device.profileKey(targetPackage, KeyEvent.KEYCODE_BACK)
                device.settle(800)
            }
        }
        device.backToSettingsRail(targetPackage)
    }
    if (device.railTo(targetPackage, "Appearance")) {
        device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_RIGHT)
        device.settle(400)
        if (device.focusedHas("Interface experience")) {
            // Opens the choice dialog and closes it again without changing anything.
            device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_CENTER)
            device.settle(1_000)
            device.profileKey(targetPackage, KeyEvent.KEYCODE_BACK)
            device.settle(500)
        }
        repeat(4) { device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_DOWN); device.settle(300) }
        device.backToSettingsRail(targetPackage)
    }
    if (device.railTo(targetPackage, "Profiles")) {
        device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_RIGHT)
        device.settle(300)
        device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_CENTER)
        device.settle(1_500)
        device.profileKey(targetPackage, KeyEvent.KEYCODE_BACK)
        device.settle(800)
    }
}

// The V2 sidebar opens "Who's watching?" from the profile entry above Home.
private fun MacrobenchmarkScope.visitProfilePicker(targetPackage: String) {
    if (!device.openSidebar(targetPackage)) return
    repeat(5) { device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_UP); device.settle(200) }
    device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_CENTER)
    if (device.wait(Until.hasObject(By.text("Who's watching?")), 6_000) != true) {
        Log.w(TAG, "Profile picker did not open")
        return
    }
    device.settle(1_500)
    device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_CENTER)
    device.wait(Until.hasObject(By.text(HOME_ROW)), 15_000)
    device.settle(1_500)
}

private fun MacrobenchmarkScope.playClip(targetPackage: String, clipId: String, streamLabel: String) {
    pressHome()
    startActivityAndWait(Intent(Intent.ACTION_MAIN).apply {
        setClassName(targetPackage, "com.nuvio.tv.MainActivity")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        putExtra("contentId", clipId)
        putExtra("contentType", "movie")
        putExtra("launchMode", "stream")
        putExtra("videoId", clipId)
        putExtra("name", "Profile clip")
    })
    if (device.wait(Until.hasObject(By.text(streamLabel)), 20_000) != true) {
        saveFailure("streams")
        error("No '$streamLabel' source: install the local clip addon in the validation app and keep adb reverse tcp:8765")
    }
    device.settle(1_000)
    device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_CENTER)
    // Loading screen, first frames and the hidden controls; pausing then brings the control row up.
    device.wait(Until.gone(By.text(streamLabel)), 10_000)
    Thread.sleep(8_000)
    device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_CENTER)
    if (device.wait(Until.hasObject(By.desc("Subtitles")), 5_000) != true) {
        device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_DOWN)
        if (device.wait(Until.hasObject(By.desc("Subtitles")), 5_000) != true) {
            saveFailure("player")
            error("Player controls did not appear")
        }
    }
    device.settle(500)

    openPlayerPanel(targetPackage, "Subtitles", "Subtitle style") {
        repeat(2) { device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_DOWN); device.settle(300) }
    }
    openPlayerPanel(targetPackage, "Audio tracks", "Delay and gain") {
        device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_DOWN)
        device.settle(300)
    }
    openPlayerPanel(targetPackage, "Sources", "Close") {
        device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_DOWN)
        device.settle(400)
    }
    openPlayerPanel(targetPackage, "More", null) {}

    // One seek along the timeline, then play on from there.
    if (device.focusControl(targetPackage, "Play") || device.focusControl(targetPackage, "Pause")) {
        device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_UP)
        device.settle(400)
        repeat(3) { device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_RIGHT); device.settle(250) }
        device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_CENTER)
        Thread.sleep(5_000)
    }
    device.profileKey(targetPackage, KeyEvent.KEYCODE_BACK)
    device.settle(800)
    device.profileKey(targetPackage, KeyEvent.KEYCODE_BACK)
    device.settle(1_500)
}

private fun MacrobenchmarkScope.openPlayerPanel(
    targetPackage: String,
    control: String,
    marker: String?,
    inside: () -> Unit
) {
    if (!device.hasObject(By.desc(control))) {
        device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_DOWN)
        device.wait(Until.hasObject(By.desc(control)), 3_000)
    }
    if (!device.focusControl(targetPackage, control)) {
        Log.w(TAG, "Player control $control not focusable")
        return
    }
    device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_CENTER)
    if (marker != null && device.wait(Until.hasObject(By.text(marker)), 5_000) != true) {
        Log.w(TAG, "Player panel $control did not open")
    }
    device.settle(800)
    inside()
    device.profileKey(targetPackage, KeyEvent.KEYCODE_BACK)
    device.settle(700)
}

private fun MacrobenchmarkScope.openPreparedHome(targetPackage: String) {
    pressHome()
    startActivityAndWait(Intent(Intent.ACTION_MAIN).apply {
        setClassName(targetPackage, "com.nuvio.tv.MainActivity")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    })
    device.waitForPreparedHome(targetPackage)
}

private fun UiDevice.waitForPreparedHome(targetPackage: String) {
    wait(Until.hasObject(By.pkg(targetPackage)), 5_000)
    waitForIdle()
    if (wait(Until.hasObject(By.text(ONBOARDING_FIRST_LABEL)), 2_000) == true) {
        check(focusAndSelect(ONBOARDING_FIRST_LABEL))
        check(focusAndSelect("Advanced"))
    }
    if (wait(Until.hasObject(By.text(HOME_ROW)), 30_000) != true) {
        saveFailure("home")
        error("Prepare the isolated validation app with English labels and populated Home; foreground=$currentPackageName")
    }
    waitForIdle()
    Thread.sleep(3_000)
}

// Runs before collection, so switching the interface never ends up in the recorded profile.
private fun ensureInterface(targetPackage: String, style: String) {
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    device.executeShellCommand("input keyevent KEYCODE_WAKEUP")
    device.pressHome()
    device.executeShellCommand("am start -W -n $targetPackage/com.nuvio.tv.MainActivity -f 0x10008000")
    device.waitForPreparedHome(targetPackage)
    check(device.openSection(targetPackage, "Settings")) { "Settings not reachable for the interface switch" }
    check(device.wait(Until.hasObject(By.text("Account and sync status")), 10_000) == true)
    check(device.moveFocusTo(targetPackage, "Appearance", KeyEvent.KEYCODE_DPAD_DOWN, 6)) { "Appearance not found" }
    Thread.sleep(800)
    val other = if (style == STYLE_V2) STYLE_ORIGINAL else STYLE_V2
    if (device.hasObject(By.text(style)) && !device.hasObject(By.text(other))) {
        device.pressHome()
        return
    }
    device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_RIGHT)
    Thread.sleep(400)
    check(device.focusedHas("Interface experience")) { "Interface experience row not focused" }
    device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_CENTER)
    check(device.wait(Until.hasObject(By.text(other)), 5_000) == true)
    check(device.moveFocusTo(targetPackage, style, KeyEvent.KEYCODE_DPAD_DOWN, 3) ||
        device.moveFocusTo(targetPackage, style, KeyEvent.KEYCODE_DPAD_UP, 3)) { "$style option not found" }
    device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_CENTER)
    check(device.wait(Until.hasObject(By.text(HOME_ROW)), 20_000) == true) { "Home did not return after the switch" }
    Thread.sleep(1_500)
    device.pressHome()
}

private fun UiDevice.settle(pauseMs: Long) {
    waitForIdle()
    Thread.sleep(pauseMs)
}

// Compose does not always report focus moves to the accessibility cache, so read focus from a fresh tree.
private fun UiDevice.freshFocused(): UiObject2? {
    if (Build.VERSION.SDK_INT >= 34) InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
    return findObject(By.focused(true))
}

private fun UiDevice.focusedHas(label: String): Boolean = try {
    val focused = freshFocused()
    focused != null && (focused.matches(label) || focused.findObject(labelText(label)) != null)
} catch (e: StaleObjectException) {
    false
}

private fun UiObject2.matches(label: String): Boolean =
    text?.equals(label, ignoreCase = true) == true || contentDescription?.equals(label, ignoreCase = true) == true

private fun labelText(label: String): BySelector {
    val pattern = Pattern.compile(Pattern.quote(label), Pattern.CASE_INSENSITIVE)
    return By.text(pattern)
}

private fun UiDevice.moveFocusTo(targetPackage: String, label: String, key: Int, maxSteps: Int): Boolean {
    repeat(maxSteps + 1) { step ->
        if (focusedHas(label)) return true
        if (step < maxSteps) {
            profileKey(targetPackage, key)
            settle(350)
        }
    }
    return false
}

// The expanded sidebar shows its labels; its entries, including the profile one, sit at the left edge.
private fun UiDevice.sidebarFocused(): Boolean = try {
    val bounds = freshFocused()?.visibleBounds
    bounds != null && bounds.left < 60 && bounds.right < 400 && SIDEBAR_LABELS.all { hasObject(By.text(it)) }
} catch (e: StaleObjectException) {
    false
}

private fun UiDevice.openSidebar(targetPackage: String): Boolean {
    repeat(10) {
        if (sidebarFocused()) return true
        profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_LEFT)
        settle(300)
    }
    return sidebarFocused()
}

private fun UiDevice.openSection(targetPackage: String, label: String): Boolean {
    if (!openSidebar(targetPackage)) return false
    if (!moveFocusTo(targetPackage, label, KeyEvent.KEYCODE_DPAD_DOWN, 5) &&
        !moveFocusTo(targetPackage, label, KeyEvent.KEYCODE_DPAD_UP, 6)) return false
    profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_CENTER)
    settle(1_500)
    return true
}

private fun UiDevice.railTo(targetPackage: String, label: String): Boolean =
    moveFocusTo(targetPackage, label, KeyEvent.KEYCODE_DPAD_UP, 10) ||
        moveFocusTo(targetPackage, label, KeyEvent.KEYCODE_DPAD_DOWN, 10)

private fun UiDevice.backToSettingsRail(targetPackage: String) {
    profileKey(targetPackage, KeyEvent.KEYCODE_BACK)
    settle(600)
    if (!hasObject(By.text("Account"))) {
        openSection(targetPackage, "Settings")
        wait(Until.hasObject(By.text("Account and sync status")), 8_000)
    }
}

// Player controls carry their names as content descriptions on a child of the focusable button.
private fun UiDevice.focusControl(targetPackage: String, desc: String): Boolean {
    repeat(12) {
        val focused = try { freshFocused() } catch (e: StaleObjectException) { null }
        if (focused != null && (focused.contentDescription == desc || focused.findObject(By.desc(desc)) != null)) return true
        val target = findObject(By.desc(desc)) ?: return false
        val focusX = focused?.visibleCenter?.x ?: 0
        profileKey(targetPackage, if (target.visibleCenter.x < focusX) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
        settle(250)
    }
    return false
}

private fun MacrobenchmarkScope.saveFailure(name: String) = device.saveFailure(name)

private fun UiDevice.saveFailure(name: String) {
    val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
    val directory = File(output).apply { mkdirs() }
    val file = "$name-failure-${System.currentTimeMillis()}"
    runCatching {
        takeScreenshot(File(directory, "$file.png"))
        dumpWindowHierarchy(File(directory, "$file.xml"))
    }
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
        val focused = freshFocused() ?: return false
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
