package com.nuvio.tv.baselineprofile

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
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
import java.io.File
import java.util.regex.Pattern
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "NuvioProfile"
private const val ONBOARDING_FIRST_LABEL = "Continue without account"
private const val STYLE_V2 = "Nuvio V2"
private const val STYLE_ORIGINAL = "Original Nuvio"
private val HOME_ROW: Pattern = Pattern.compile("Popular - (Movie|Series)|Continue watching|Latest movies for you|Upcoming")
private val DETAILS_MARKER: Pattern = Pattern.compile("Play|Resume.*|Playback unavailable|Add to library|Director: .*")
private val SIDEBAR_LABELS = setOf("Home", "Search", "Library", "Settings")
private val ALLOWED_PACKAGES = setOf("com.nuvio.tv.v2.validation", "com.nuvio.tv.v2.baseline")
private const val INTRO_DESC = "Nuvio Arc"
private val CALENDAR_READY: Pattern = Pattern.compile("Just aired|Today|Tomorrow|Later|No date yet")
private val CALENDAR_EMPTY: Pattern = Pattern.compile("Nothing followed yet|All quiet")
private const val SORT_SOURCE_ORDER = "Source order"
private const val SORT_BEST_FIRST = "Best quality first"
private val PLAYER_CONTROLS: Pattern = Pattern.compile("Play|Pause")
private val THUMBNAIL_PREPARE: Pattern = Pattern.compile("Generating thumbnails|Thumbnails ready")

// The libraries every cold start runs through are kept too. Most of them (OkHttp, Moshi, Retrofit) ship
// no profile of their own, so without these rules the Home loads through JIT-only code for its first seconds.
// Keep in step with the baselineProfile filter in app/build.gradle.kts.
private val KEPT_PACKAGES = listOf(
    "Lcom/nuvio/tv/", "Landroidx/", "Lkotlin/", "Lkotlinx/coroutines/", "Lokhttp3/", "Lokio/",
    "Lretrofit2/", "Lcom/squareup/moshi/", "Lcoil3/", "Ldagger/"
)
private val keepRule: (String) -> Boolean = { rule -> KEPT_PACKAGES.any { rule.contains(it) } }

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    private val arguments = InstrumentationRegistry.getArguments()
    private val targetPackage = arguments.getString("nuvioPackage", "com.nuvio.tv.v2.validation")
        .also { require(it in ALLOWED_PACKAGES) }
    private val clipId = arguments.getString("nuvioClipId", "am9dv:fmt-av1-10bit")
    private val clipStream = arguments.getString("nuvioClipStream", "AM9 local")
    private val clipWatchMs = arguments.getString("nuvioClipWatchMs", "45000").toLong()
    private val calendarSeries = arguments.getString("nuvioCalendarSeries", "tt0096697")

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
        collect(iterations = 3) { playClip(targetPackage, clipId, clipStream, clipWatchMs) }
    }

    // A followed series gives the Calendar rows and a month; it is added before collection.
    @Test
    fun generateMenuAndCalendar() {
        ensureInterface(targetPackage, STYLE_V2)
        prepareCalendar(targetPackage, calendarSeries)
        collect(iterations = 2) {
            openPreparedHome(targetPackage)
            step("menu") { visitMainMenu(targetPackage) }
            step("calendar") { visitCalendar(targetPackage) }
            step("profile-intro") { visitProfilePicker(targetPackage) }
        }
    }

    @Test
    fun generateSources() {
        ensureInterface(targetPackage, STYLE_V2)
        setSortSources(targetPackage, SORT_BEST_FIRST)
        try {
            collect(iterations = 2) {
                openPreparedHome(targetPackage)
                step("playback-settings") { visitPlaybackSettings(targetPackage) }
                step("sources") { browseSources(targetPackage, clipId, clipStream) }
            }
        } finally {
            setSortSources(targetPackage, SORT_SOURCE_ORDER)
        }
    }
}

// A failed optional step is logged with a screenshot and the journey carries on.
private inline fun MacrobenchmarkScope.step(name: String, block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        Log.w(TAG, "Step $name skipped", e)
        saveFailure("step-$name")
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
    check(device.waitFor(By.text(HOME_ROW), 8_000) == true) { "Back from Details did not return to Home" }
    device.settle(600)

    if (device.openSidebar(targetPackage)) {
        repeat(3) { device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_DOWN); device.settle(250) }
        repeat(3) { device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_UP); device.settle(250) }
        device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_RIGHT)
        device.settle(500)
    }
    step("home-holds") { holdHome(targetPackage) }
}

// Held directions run the fast scroll and the repeat throttle, which single presses never reach.
private fun MacrobenchmarkScope.holdHome(targetPackage: String) {
    device.holdKey(targetPackage, KeyEvent.KEYCODE_DPAD_DOWN, 2_500)
    device.settle(800)
    device.holdKey(targetPackage, KeyEvent.KEYCODE_DPAD_UP, 3_500)
    device.settle(800)
    device.holdKey(targetPackage, KeyEvent.KEYCODE_DPAD_RIGHT, 2_500)
    device.settle(600)
    device.holdKey(targetPackage, KeyEvent.KEYCODE_DPAD_LEFT, 2_000)
    device.settle(600)
    device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_DOWN)
    device.settle(400)
    device.holdKey(targetPackage, KeyEvent.KEYCODE_DPAD_RIGHT, 1_500)
    device.settle(600)
    device.holdKey(targetPackage, KeyEvent.KEYCODE_DPAD_DOWN, 1_500)
    device.settle(600)
    device.holdKey(targetPackage, KeyEvent.KEYCODE_DPAD_UP, 3_000)
    device.settle(800)
    if (device.sidebarFocused()) {
        device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_RIGHT)
        device.settle(500)
    }
}

// The V2 main menu: profile on top, every page, closed again with Right, twice over different cards.
private fun MacrobenchmarkScope.visitMainMenu(targetPackage: String) {
    repeat(2) { round ->
        if (!device.openSidebar(targetPackage)) {
            Log.w(TAG, "Main menu did not open")
            return
        }
        device.settle(500)
        repeat(3) { device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_UP); device.settle(250) }
        repeat(7) { device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_DOWN); device.settle(250) }
        repeat(4) { device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_UP); device.settle(250) }
        device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_RIGHT)
        device.settle(800)
        if (round == 0) {
            repeat(2) { device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_DOWN); device.settle(300) }
        }
    }
    repeat(2) { device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_UP); device.settle(300) }
}

private fun MacrobenchmarkScope.visitCalendar(targetPackage: String) {
    if (!device.openSection(targetPackage, "Calendar")) {
        Log.w(TAG, "Calendar not in the menu")
        return
    }
    if (device.waitFor(By.text("Month"), 8_000) != true) {
        Log.w(TAG, "Calendar did not open")
        return
    }
    device.waitForAny(listOf(By.text(CALENDAR_READY), By.text(CALENDAR_EMPTY)), 20_000)
    device.settle(1_000)
    if (!device.freshHas(By.text(CALENDAR_READY))) {
        Log.w(TAG, "Calendar has no dated titles; only its empty page is recorded")
        return
    }
    repeat(6) { device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_DOWN); device.settle(300) }
    repeat(6) { device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_UP); device.settle(250) }
    if (!device.moveFocusTo(targetPackage, "Month", KeyEvent.KEYCODE_DPAD_UP, 6) &&
        !device.moveFocusTo(targetPackage, "Month", KeyEvent.KEYCODE_DPAD_RIGHT, 2)) {
        Log.w(TAG, "Month switch not reachable")
        return
    }
    device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_CENTER)
    device.settle(1_500)
    device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_DOWN)
    device.settle(400)
    repeat(4) { device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_RIGHT); device.settle(250) }
    repeat(2) { device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_DOWN); device.settle(300) }
    repeat(3) { device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_LEFT); device.settle(250) }
    device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_UP)
    device.settle(300)
    // OK on a day goes back to the list on that day.
    device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_CENTER)
    device.settle(1_500)
    repeat(3) { device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_DOWN); device.settle(300) }
}

private fun MacrobenchmarkScope.visitPlaybackSettings(targetPackage: String) {
    if (!device.openPlaybackPage(targetPackage)) {
        Log.w(TAG, "Playback settings not reachable")
        return
    }
    if (device.expandSection(targetPackage, "Stream Selection")) {
        if (device.moveFocusTo(targetPackage, "Sort sources", KeyEvent.KEYCODE_DPAD_DOWN, 10)) {
            device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_CENTER)
            if (device.waitForSortDialog()) {
                device.settle(800)
                device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_DOWN)
                device.settle(300)
                device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_UP)
                device.settle(300)
            } else {
                Log.w(TAG, "Sort sources dialog did not open")
            }
            device.profileKey(targetPackage, KeyEvent.KEYCODE_BACK)
            device.settle(700)
        } else {
            Log.w(TAG, "Sort sources row not found")
        }
        device.collapseSection(targetPackage, "Stream Selection")
    }
    if (device.expandSection(targetPackage, "Player Interface")) {
        repeat(12) { device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_DOWN); device.settle(300) }
        repeat(4) { device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_UP); device.settle(250) }
        device.collapseSection(targetPackage, "Player Interface")
    }
    device.backToSettingsRail(targetPackage)
}

// The source list of the local clip, ordered by Sort sources; the filter row above switches between sources.
private fun MacrobenchmarkScope.browseSources(targetPackage: String, clipId: String, streamLabel: String) {
    openSourceList(targetPackage, clipId)
    if (device.waitFor(By.text(streamLabel), 20_000) != true) {
        Log.w(TAG, "No '$streamLabel' source; the source list is recorded without links")
        device.settle(1_500)
        device.profileKey(targetPackage, KeyEvent.KEYCODE_BACK)
        device.settle(1_000)
        return
    }
    device.settle(1_000)
    repeat(4) { device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_DOWN); device.settle(300) }
    repeat(5) { device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_UP); device.settle(300) }
    repeat(3) { device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_RIGHT); device.settle(400) }
    repeat(3) { device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_LEFT); device.settle(400) }
    device.profileKey(targetPackage, KeyEvent.KEYCODE_BACK)
    device.settle(1_000)
}

// A Continue watching card opens the source list instead of Details, so step down a row and try again.
private fun MacrobenchmarkScope.openDetailsFromHome(targetPackage: String) {
    repeat(3) {
        device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_CENTER)
        if (device.waitFor(By.text(DETAILS_MARKER), 10_000) == true) {
            device.settle(2_000)
            return
        }
        device.profileKey(targetPackage, KeyEvent.KEYCODE_BACK)
        device.waitFor(By.text(HOME_ROW), 5_000)
        device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_DOWN)
        device.settle(500)
    }
    saveFailure("details")
    error("Profile journey did not reach Details")
}

private fun MacrobenchmarkScope.visitSearch(targetPackage: String) {
    check(device.openSection(targetPackage, "Search")) { "Search not reachable" }
    val editText = By.clazz("android.widget.EditText")
    val field = if (device.waitFor(editText, 5_000)) device.findObject(editText) else null
    if (field == null) {
        Log.w(TAG, "Search field not found")
        return
    }
    runCatching { field.text = "batman" }.onFailure { Log.w(TAG, "Search text not set", it) }
    device.waitFor(By.desc(Pattern.compile(".*Batman.*", Pattern.CASE_INSENSITIVE)), 10_000)
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
    check(device.waitFor(By.text("Account and sync status"), 10_000) == true) { "Settings did not open" }
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
            if (device.waitFor(By.text("Install addon"), 5_000) == true) {
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
    if (device.waitFor(By.text("Who's watching?"), 6_000) != true) {
        Log.w(TAG, "Profile picker did not open")
        return
    }
    device.settle(1_500)
    // Picking the profile plays the Arc intro before Home.
    device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_CENTER)
    device.settle(800)
    device.waitIntroGone()
    device.waitFor(By.text(HOME_ROW), 15_000)
    device.settle(1_500)
}

private fun MacrobenchmarkScope.openSourceList(targetPackage: String, clipId: String) {
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
    device.settle(800)
    device.waitIntroGone()
}

private fun MacrobenchmarkScope.playClip(targetPackage: String, clipId: String, streamLabel: String, watchMs: Long) {
    openSourceList(targetPackage, clipId)
    if (device.waitFor(By.text(streamLabel), 20_000) != true) {
        saveFailure("streams")
        error("No '$streamLabel' source: install the local clip addon in the validation app and keep adb reverse tcp:8765")
    }
    device.settle(1_000)
    device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_CENTER)
    // Loading screen, first frames and the hidden controls; pausing then brings the control row up.
    device.waitGone(By.text(streamLabel), 10_000)
    // With "Generate thumbnails before play" on, the generating screen holds playback; Back starts it.
    if (device.waitFor(By.text(THUMBNAIL_PREPARE), 12_000) == true) {
        device.settle(5_000)
        if (device.freshHas(By.text(THUMBNAIL_PREPARE))) device.profileKey(targetPackage, KeyEvent.KEYCODE_BACK)
    }
    // About a minute of plain playback, so the per-frame and per-buffer paths are recorded too.
    Thread.sleep(watchMs)
    device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_CENTER)
    // Subtitles and Audio tracks are missing on clips without such tracks; Play / Pause is always there.
    if (device.waitFor(By.desc(PLAYER_CONTROLS), 5_000) != true) {
        device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_DOWN)
        if (device.waitFor(By.desc(PLAYER_CONTROLS), 5_000) != true) {
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
    if (!device.freshHas(By.desc(control))) {
        device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_DOWN)
        device.waitFor(By.desc(control), 3_000)
    }
    if (!device.focusControl(targetPackage, control)) {
        Log.w(TAG, "Player control $control not focusable")
        return
    }
    device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_CENTER)
    if (marker != null && device.waitFor(By.text(marker), 5_000) != true) {
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
    waitFor(By.pkg(targetPackage), 5_000)
    waitForIdle()
    if (waitFor(By.text(ONBOARDING_FIRST_LABEL), 2_000) == true) {
        check(focusAndSelect(ONBOARDING_FIRST_LABEL))
        // Builds without the experience picker go straight to Home.
        if (waitFor(By.text("Advanced"), 4_000)) focusAndSelect("Advanced")
    }
    // Signed-in builds open on the profile picker; the profile that has focus is the primary one.
    val pickerEnd = System.currentTimeMillis() + 20_000
    while (System.currentTimeMillis() < pickerEnd && !hasObject(By.text(HOME_ROW))) {
        if (hasObject(By.text("Who's watching?"))) {
            Thread.sleep(800)
            pressDPadCenter()
            waitForIdle()
            break
        }
        Thread.sleep(500)
    }
    if (waitFor(By.text(HOME_ROW), 30_000) != true) {
        saveFailure("home")
        error("Prepare the isolated validation app with English labels and populated Home; foreground=$currentPackageName")
    }
    waitIntroGone()
    waitForIdle()
    Thread.sleep(3_000)
}

// The Arc intro is a full-screen view named after the app; until it has dissolved into Home it keeps the keys.
private fun UiDevice.waitIntroGone(timeoutMs: Long = 14_000) {
    val end = System.currentTimeMillis() + timeoutMs
    while (introShowing()) {
        if (System.currentTimeMillis() > end) {
            Log.w(TAG, "Intro still showing after $timeoutMs ms")
            return
        }
        Thread.sleep(400)
    }
}

private fun UiDevice.introShowing(): Boolean = try {
    clearAccessibilityCache()
    findObjects(By.desc(INTRO_DESC)).any { it.visibleBounds.width() > displayWidth * 4 / 5 }
} catch (e: StaleObjectException) {
    true
}

private fun UiDevice.waitForAny(selectors: List<BySelector>, timeoutMs: Long): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (true) {
        if (selectors.any { freshHas(it) }) return true
        if (System.currentTimeMillis() > end) return false
        Thread.sleep(400)
    }
}

// Presses down, waits past the long-press delay, then repeats every 50 ms like a held remote button.
private fun UiDevice.holdKey(targetPackage: String, key: Int, holdMs: Long) {
    check(currentPackageName == targetPackage) { "Another app took focus during profile collection" }
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val downTime = SystemClock.uptimeMillis()
    fun send(action: Int, repeat: Int, flags: Int, sync: Boolean) {
        val event = KeyEvent(
            downTime, SystemClock.uptimeMillis(), action, key, repeat, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags, InputDevice.SOURCE_DPAD or InputDevice.SOURCE_KEYBOARD
        )
        automation.injectInputEvent(event, sync)
    }
    send(KeyEvent.ACTION_DOWN, 0, 0, true)
    Thread.sleep(450)
    var repeat = 1
    while (SystemClock.uptimeMillis() - downTime < holdMs) {
        send(KeyEvent.ACTION_DOWN, repeat, if (repeat == 1) KeyEvent.FLAG_LONG_PRESS else 0, false)
        repeat++
        Thread.sleep(50)
    }
    send(KeyEvent.ACTION_UP, 0, 0, true)
}

// Outside collection: adds a running series to the library and lets the Calendar find its dates once.
private fun prepareCalendar(targetPackage: String, seriesId: String) {
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    try {
        device.executeShellCommand("input keyevent KEYCODE_WAKEUP")
        device.pressHome()
        device.executeShellCommand(
            "am start -W -n $targetPackage/com.nuvio.tv.MainActivity -f 0x10008000 --es contentId $seriesId --es contentType series"
        )
        device.waitFor(By.pkg(targetPackage), 5_000)
        device.settle(1_000)
        device.waitIntroGone()
        val add = By.desc("Add to library")
        val saved = By.desc("Remove from library")
        if (!device.waitForAny(listOf(add, saved), 25_000)) {
            Log.w(TAG, "Details of $seriesId did not open")
        } else if (!device.freshHas(saved)) {
            if (device.focusControl(targetPackage, "Add to library")) {
                device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_CENTER)
                if (!device.waitFor(saved, 5_000)) Log.w(TAG, "$seriesId was not added to the library")
            } else {
                Log.w(TAG, "Add to library not focusable")
            }
        }
        device.pressHome()
        device.executeShellCommand("am start -W -n $targetPackage/com.nuvio.tv.MainActivity -f 0x10008000")
        device.waitForPreparedHome(targetPackage)
        if (device.openSection(targetPackage, "Calendar") && !device.waitFor(By.text(CALENDAR_READY), 45_000)) {
            Log.w(TAG, "Calendar found no dates while preparing")
        }
    } catch (e: Exception) {
        Log.w(TAG, "Calendar preparation skipped", e)
        device.saveFailure("calendar-prepare")
    }
    device.pressHome()
}

// Outside collection: Settings > Playback > Stream Selection > Sort sources.
private fun setSortSources(targetPackage: String, option: String) {
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    try {
        device.executeShellCommand("input keyevent KEYCODE_WAKEUP")
        device.pressHome()
        device.executeShellCommand("am start -W -n $targetPackage/com.nuvio.tv.MainActivity -f 0x10008000")
        device.waitForPreparedHome(targetPackage)
        check(device.openPlaybackPage(targetPackage)) { "Playback settings not reachable" }
        check(device.expandSection(targetPackage, "Stream Selection")) { "Stream Selection not found" }
        check(device.moveFocusTo(targetPackage, "Sort sources", KeyEvent.KEYCODE_DPAD_DOWN, 10)) { "Sort sources not found" }
        device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_CENTER)
        check(device.waitForSortDialog()) { "Sort sources dialog did not open" }
        if (device.moveFocusTo(targetPackage, option, KeyEvent.KEYCODE_DPAD_DOWN, 3) ||
            device.moveFocusTo(targetPackage, option, KeyEvent.KEYCODE_DPAD_UP, 3)) {
            device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_CENTER)
        } else {
            Log.w(TAG, "Sort option $option not found")
            device.profileKey(targetPackage, KeyEvent.KEYCODE_BACK)
        }
        device.settle(800)
    } catch (e: Exception) {
        Log.w(TAG, "Sort sources not set to $option", e)
        device.saveFailure("sort-sources")
    }
    device.pressHome()
}

// Both choices are only on screen together inside the dialog.
private fun UiDevice.waitForSortDialog(): Boolean {
    val end = System.currentTimeMillis() + 5_000
    while (true) {
        if (freshHas(By.text(SORT_SOURCE_ORDER)) && freshHas(By.text(SORT_BEST_FIRST))) return true
        if (System.currentTimeMillis() > end) return false
        Thread.sleep(400)
    }
}

private fun UiDevice.openPlaybackPage(targetPackage: String): Boolean {
    if (!openSection(targetPackage, "Settings")) return false
    if (!waitFor(By.text("Account and sync status"), 10_000)) return false
    settle(600)
    if (!railTo(targetPackage, "Playback")) return false
    profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_RIGHT)
    settle(600)
    return true
}

// Playback sections are collapsible rows whose value reads Open or Closed.
private fun UiDevice.expandSection(targetPackage: String, label: String): Boolean {
    if (!moveFocusTo(targetPackage, label, KeyEvent.KEYCODE_DPAD_DOWN, 12) &&
        !moveFocusTo(targetPackage, label, KeyEvent.KEYCODE_DPAD_UP, 14)) {
        Log.w(TAG, "Section $label not found")
        return false
    }
    if (focusedHas("Closed")) {
        profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_CENTER)
        settle(700)
    }
    return true
}

private fun UiDevice.collapseSection(targetPackage: String, label: String) {
    if (moveFocusTo(targetPackage, label, KeyEvent.KEYCODE_DPAD_UP, 16) && focusedHas("Open")) {
        profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_CENTER)
        settle(500)
    }
}

// Runs before collection, so switching the interface never ends up in the recorded profile.
private fun ensureInterface(targetPackage: String, style: String) {
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    device.executeShellCommand("input keyevent KEYCODE_WAKEUP")
    device.pressHome()
    device.executeShellCommand("am start -W -n $targetPackage/com.nuvio.tv.MainActivity -f 0x10008000")
    device.waitForPreparedHome(targetPackage)
    check(device.openSection(targetPackage, "Settings")) { "Settings not reachable for the interface switch" }
    check(device.waitFor(By.text("Account and sync status"), 10_000) == true)
    val onAppearance = device.moveFocusTo(targetPackage, "Appearance", KeyEvent.KEYCODE_DPAD_DOWN, 6) || run {
        // A signed-in account page takes the first focus; step back to the category list.
        device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_LEFT)
        device.settle(350)
        device.moveFocusTo(targetPackage, "Appearance", KeyEvent.KEYCODE_DPAD_UP, 10) ||
            device.moveFocusTo(targetPackage, "Appearance", KeyEvent.KEYCODE_DPAD_DOWN, 10)
    }
    if (!onAppearance) {
        Log.w(TAG, "Appearance not reached, keeping the current interface")
        device.pressHome()
        return
    }
    Thread.sleep(800)
    val other = if (style == STYLE_V2) STYLE_ORIGINAL else STYLE_V2
    if (device.freshHas(By.text(style)) && !device.freshHas(By.text(other))) {
        device.pressHome()
        return
    }
    device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_RIGHT)
    Thread.sleep(400)
    check(device.focusedHas("Interface experience")) { "Interface experience row not focused" }
    device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_CENTER)
    check(device.waitFor(By.text(other), 5_000) == true)
    check(device.moveFocusTo(targetPackage, style, KeyEvent.KEYCODE_DPAD_DOWN, 3) ||
        device.moveFocusTo(targetPackage, style, KeyEvent.KEYCODE_DPAD_UP, 3)) { "$style option not found" }
    device.profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_CENTER)
    check(device.waitFor(By.text(HOME_ROW), 20_000) == true) { "Home did not return after the switch" }
    Thread.sleep(1_500)
    device.pressHome()
}

private fun UiDevice.settle(pauseMs: Long) {
    waitForIdle()
    Thread.sleep(pauseMs)
}

// Compose does not always report focus moves to the accessibility cache, so read focus from a fresh tree.
private fun UiDevice.freshFocused(): UiObject2? {
    clearAccessibilityCache()
    return findObject(By.focused(true))
}

private fun clearAccessibilityCache() {
    if (Build.VERSION.SDK_INT >= 34) InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
}

private fun UiDevice.freshHas(selector: BySelector): Boolean {
    clearAccessibilityCache()
    return hasObject(selector)
}

private fun UiDevice.waitFor(selector: BySelector, timeoutMs: Long): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (true) {
        if (freshHas(selector)) return true
        if (System.currentTimeMillis() > end) return false
        Thread.sleep(400)
    }
}

private fun UiDevice.waitGone(selector: BySelector, timeoutMs: Long): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (true) {
        if (!freshHas(selector)) return true
        if (System.currentTimeMillis() > end) return false
        Thread.sleep(400)
    }
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
    val density = InstrumentationRegistry.getInstrumentation().context.resources.displayMetrics.density
    val bounds = freshFocused()?.visibleBounds
    bounds != null && bounds.left < 40 * density && bounds.right < 270 * density &&
        SIDEBAR_LABELS.all { freshHas(By.text(it)) }
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

// Some screens keep Left inside their content (text fields, tabs), so Back is tried before giving up.
private fun UiDevice.openSection(targetPackage: String, label: String): Boolean {
    var attempts = 0
    while (!openSidebar(targetPackage)) {
        if (++attempts > 3) {
            saveFailure("section-$label")
            return false
        }
        profileKey(targetPackage, KeyEvent.KEYCODE_BACK)
        settle(800)
    }
    if (!moveFocusTo(targetPackage, label, KeyEvent.KEYCODE_DPAD_DOWN, 5) &&
        !moveFocusTo(targetPackage, label, KeyEvent.KEYCODE_DPAD_UP, 6)) return false
    profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_CENTER)
    settle(1_500)
    return true
}

private fun UiDevice.railTo(targetPackage: String, label: String): Boolean =
    moveFocusTo(targetPackage, label, KeyEvent.KEYCODE_DPAD_UP, 10) ||
        moveFocusTo(targetPackage, label, KeyEvent.KEYCODE_DPAD_DOWN, 10) || run {
            profileKey(targetPackage, KeyEvent.KEYCODE_DPAD_LEFT)
            settle(350)
            moveFocusTo(targetPackage, label, KeyEvent.KEYCODE_DPAD_UP, 10) ||
                moveFocusTo(targetPackage, label, KeyEvent.KEYCODE_DPAD_DOWN, 10)
        }

private fun UiDevice.backToSettingsRail(targetPackage: String) {
    profileKey(targetPackage, KeyEvent.KEYCODE_BACK)
    settle(600)
    if (!freshHas(By.text("Account"))) {
        openSection(targetPackage, "Settings")
        waitFor(By.text("Account and sync status"), 8_000)
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
    if (waitFor(By.text(pattern), 10_000) != true) return false
    repeat(maxSteps) { step ->
        if (isFocusedLabel(label, pattern)) {
            pressDPadCenter()
            waitForIdle()
            Thread.sleep(1_500)
            // Treat the step as done only once the label is gone, so a mis-selection keeps searching instead of moving on.
            if (waitGone(By.text(pattern), 5_000) == true) return true
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
