package com.nuvio.tv.core.player

import android.content.ComponentCallbacks2
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocation
import androidx.media3.exoplayer.upstream.DefaultAllocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferMemoryKeeperTest {

    private val mb = 1024L * 1024L
    private val segment = C.DEFAULT_BUFFER_SEGMENT_SIZE
    private val playerId = PlayerId("test")
    private val allocator = DefaultAllocator(true, segment, 0, false)
    private var now = 1_000_000L
    private var free: BufferSizing.FreeMemory? = BufferSizing.FreeMemory(800 * mb, 130 * mb, false)
    private val delayed = mutableListOf<() -> Unit>()

    private val worker = object : BufferMemoryKeeper.Worker {
        override fun run(task: () -> Unit) = task()
        override fun runLater(delayMs: Long, task: () -> Unit) {
            delayed += task
        }
    }

    private fun params(bufferedMs: Long) = LoadControl.Parameters(
        playerId, Timeline.EMPTY, MediaPeriodId(Any()), 0L, bufferedMs * 1000L, 1f,
        true, false, C.TIME_UNSET, C.TIME_UNSET
    )

    private fun customControl(budgetMb: Long = 100) = BitrateAwareLoadControl(
        minBufferMs = 15_000,
        maxBufferMs = 45_000,
        bufferForPlaybackMs = 5_000,
        bufferForPlaybackAfterRebufferMs = 3_000,
        prioritizeTimeOverSizeThresholds = true,
        backBufferDurationMs = 0,
        retainBackBufferFromKeyframe = true,
        budgetBytes = budgetMb * mb,
        allocator = allocator,
    ).also {
        it.onPrepared(playerId)
        it.onTracksSelected(params(0), TrackGroupArray.EMPTY, arrayOf<ExoTrackSelection?>())
    }

    private fun performanceControl(
        budgetMb: Int = 100,
        minBufferMs: Int = 15_000,
        maxBufferMs: Int = 45_000,
        backBufferMs: Int = 0,
    ) = ReservableLoadControl(
        allocator = allocator,
        minBufferMs = minBufferMs,
        maxBufferMs = maxBufferMs,
        bufferForPlaybackMs = 3_000,
        bufferForPlaybackAfterRebufferMs = 3_000,
        targetBufferBytes = budgetMb * 1024 * 1024,
        prioritizeTimeOverSizeThresholds = false,
        backBufferDurationMs = backBufferMs,
        retainBackBufferFromKeyframe = true,
    ).also {
        it.onPrepared(playerId)
        it.onTracksSelected(params(0), TrackGroupArray.EMPTY, arrayOf<ExoTrackSelection?>())
    }

    private var swapFree = -1L

    private var lastWarnings = 0

    private fun keeper(
        control: BufferBudgetControl,
        budgetManaged: Boolean = true,
        ceilingMb: Long = 0L,
        floorMb: Int = BufferSizing.MIN_SIZED_BUFFER_MB,
        oneGbBox: Boolean = false,
    ) = BufferMemoryKeeper(
        control, { free }, { now }, worker, budgetManaged, ceilingMb * mb,
        swapFreeMb = { swapFree },
        floorBytes = floorMb * mb,
        oneGbBox = oneGbBox,
        onLastWarning = { lastWarnings++ },
    )

    private fun take(megabytes: Long): List<Allocation> =
        List((megabytes * mb / segment).toInt()) { allocator.allocate() }

    private fun giveBack(allocations: List<Allocation>) = allocations.forEach(allocator::release)

    /** Android's warning on a box that is really short: inside the margin above its low-memory line. */
    private fun warn(k: BufferMemoryKeeper, level: Int) {
        val before = free
        free = BufferSizing.FreeMemory(200 * mb, 130 * mb, false)
        k.onTrimMemory(level)
        free = before
    }

    @Test
    fun `a seek gives back what the new position does not use`() {
        val k = keeper(customControl())
        val old = take(60)
        giveBack(old)
        take(10)
        assertEquals(60 * mb, allocator.memoryFootprint.toLong())
        k.onSeek()
        now += 1_000
        k.onSeek()
        assertEquals(1, delayed.size)
        now += BufferMemoryKeeper.SEEK_SETTLE_MS
        delayed.removeAt(0)()
        assertEquals(10 * mb, allocator.totalBytesAllocated.toLong())
        assertTrue(allocator.availableBytes <= BufferMemoryKeeper.KEEP_IDLE_BYTES)
    }

    @Test
    fun `a seek that is still settling waits`() {
        val k = keeper(customControl())
        giveBack(take(40))
        k.onSeek()
        now += 500
        delayed.removeAt(0)()
        assertEquals(40 * mb, allocator.availableBytes.toLong())
        assertEquals(1, delayed.size)
        now += BufferMemoryKeeper.SEEK_SETTLE_MS
        delayed.removeAt(0)()
        assertTrue(allocator.availableBytes <= BufferMemoryKeeper.KEEP_IDLE_BYTES)
    }

    @Test
    fun `running low lowers the budget and the idle pool`() {
        val lc = customControl(budgetMb = 100)
        val k = keeper(lc)
        val held = take(70)
        giveBack(held.take(320))
        warn(k, ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertEquals(BufferMemoryKeeper.LEVEL_LOW, k.pressureLevel)
        assertEquals(60 * mb, k.capBytes(k.pressureLevel))
        assertTrue(allocator.availableBytes <= BufferMemoryKeeper.KEEP_IDLE_BYTES)
        // Capped at 60 MB, loading stops a quarter over it (75 MB).
        take(30)
        assertFalse(lc.shouldContinueLoading(params(bufferedMs = 6_000)))
        assertEquals(100 * mb, lc.uncappedBudgetBytes)
    }

    @Test
    fun `critical cuts deeper but not below the floor`() {
        val k = keeper(customControl(budgetMb = 120))
        warn(k, ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertEquals(BufferMemoryKeeper.LEVEL_CRITICAL, k.pressureLevel)
        assertEquals(50 * mb, k.capBytes(BufferMemoryKeeper.LEVEL_CRITICAL))
        val big = keeper(customControl(budgetMb = 1000))
        assertEquals(350 * mb, big.capBytes(BufferMemoryKeeper.LEVEL_CRITICAL))
    }

    @Test
    fun `memory pressure cuts the buffer ahead, not the back buffer`() {
        // 2 GB box, 200 MiB, 60 Mbit/s film with a 7.5 s back buffer (56.25 MB).
        val rate = BufferSizing.backBufferBytesPerSecond(60_000_000L)
        val lc = performanceControl(budgetMb = 200, backBufferMs = 7_500)
        lc.backBufferBytes = BufferSizing.backBufferBytes(7_500, rate)
        assertEquals(56_250_000L, lc.backBufferBytes)
        val k = keeper(lc)
        val budget = 200 * mb
        val forward = budget - lc.backBufferBytes
        warn(k, ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        val low = k.capBytes(BufferMemoryKeeper.LEVEL_LOW)
        assertEquals(lc.backBufferBytes + forward * 60 / 100, low)
        assertEquals(low, k.effectiveCapBytes())
        assertEquals(low.toInt(), lc.allocatorTargetBytes())
        // 12.3 s ahead under the cut, against 9.3 s when the whole budget was cut to 60 %.
        assertEquals(12_277L, (low - lc.backBufferBytes) * 1000L / rate)
        assertEquals(9_277L, (budget * 60 / 100 - lc.backBufferBytes) * 1000L / rate)
        val critical = k.capBytes(BufferMemoryKeeper.LEVEL_CRITICAL)
        assertEquals(lc.backBufferBytes + forward * 35 / 100, critical)
        assertEquals(7_161L, (critical - lc.backBufferBytes) * 1000L / rate)
    }

    @Test
    fun `the back buffer share keeps the floor and never counts past the budget`() {
        val lc = customControl(budgetMb = 100)
        lc.backBufferBytes = 40 * mb
        val k = keeper(lc)
        assertEquals(40 * mb + 60 * mb * 35 / 100, k.capBytes(BufferMemoryKeeper.LEVEL_CRITICAL))
        lc.backBufferBytes = 0L
        assertEquals(50 * mb, k.capBytes(BufferMemoryKeeper.LEVEL_CRITICAL))
        lc.backBufferBytes = 300 * mb
        assertEquals(100 * mb, k.capBytes(BufferMemoryKeeper.LEVEL_CRITICAL))
        lc.backBufferBytes = 40 * mb
        warn(k, ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        k.setLentBytes(48 * mb)
        assertEquals(52 * mb, k.effectiveCapBytes())
    }

    @Test
    fun `hiding the ui only gives idle memory back`() {
        val k = keeper(customControl())
        giveBack(take(30))
        k.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertEquals(BufferMemoryKeeper.LEVEL_NONE, k.pressureLevel)
        assertTrue(allocator.availableBytes <= BufferMemoryKeeper.KEEP_IDLE_BYTES)
    }

    @Test
    fun `performance mode stops loading at the lowered budget once 5 s are ahead, and may start playing`() {
        val lc = performanceControl(budgetMb = 100)
        val k = keeper(lc)
        take(65)
        assertTrue(lc.shouldContinueLoading(params(bufferedMs = 6_000)))
        warn(k, ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertTrue(lc.shouldContinueLoading(params(bufferedMs = 2_000)))
        assertFalse(lc.shouldContinueLoading(params(bufferedMs = 6_000)))
        assertTrue(lc.shouldStartPlayback(params(bufferedMs = 6_000)))
    }

    @Test
    fun `a 12 s back buffer at 80 Mbit s in a small target never stops loading before 5 s are ahead`() {
        // Native memory and Custom playback buffers on (managed) on a 2 GB box with apps in the background: the
        // target is sized to 62 MB, the back buffer is 12 s, about 120 MB of an 80 Mbit/s remux, and Android
        // reports memory pressure.
        val lc = performanceControl(budgetMb = 62, minBufferMs = 200_000, maxBufferMs = 280_000, backBufferMs = 12_000)
        val k = keeper(lc)
        take(120)
        warn(k, ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertEquals(50 * mb, k.capBytes(k.pressureLevel))
        // media3 warns below 500 ms ahead, and its log cannot print in unit tests.
        androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF)
        try {
            for (aheadMs in listOf(0L, 400L, 2_000L, 3_000L, 4_900L)) {
                assertTrue("$aheadMs ms ahead", lc.shouldContinueLoading(params(bufferedMs = aheadMs)))
            }
        } finally {
            androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_ALL)
        }
        take(50)
        assertFalse(lc.shouldContinueLoading(params(bufferedMs = 5_000)))
        assertTrue(lc.shouldStartPlayback(params(bufferedMs = 5_000)))
        // Without pressure the full target alone does not stop it either.
        val calm = performanceControl(budgetMb = 62, minBufferMs = 200_000, maxBufferMs = 280_000, backBufferMs = 12_000)
        assertTrue(calm.shouldContinueLoading(params(bufferedMs = 2_000)))
        assertFalse(calm.shouldContinueLoading(params(bufferedMs = 5_000)))
        // The back buffer the app builds for this target would be under 10 s, so it goes to the buffer ahead.
        assertEquals(0, BufferSizing.backBufferMsForTarget(12_000, 62 * mb))
    }

    @Test
    fun `swap staying used up beside little free memory steps the cut up like low memory does`() {
        val k = keeper(customControl(budgetMb = 100))
        free = BufferSizing.FreeMemory(188 * mb, 144 * mb, false)
        swapFree = 0
        k.check()
        assertEquals(BufferMemoryKeeper.LEVEL_LOW, k.pressureLevel)
        now += BufferMemoryKeeper.ESCALATE_AFTER_MS - 1
        k.check()
        assertEquals(BufferMemoryKeeper.LEVEL_LOW, k.pressureLevel)
        now += 1
        k.check()
        assertEquals(BufferMemoryKeeper.LEVEL_CRITICAL, k.pressureLevel)
    }

    @Test
    fun `swap used up with room left above the line stays at the first cut`() {
        val k = keeper(customControl(budgetMb = 100))
        free = BufferSizing.FreeMemory(230 * mb, 144 * mb, false)
        swapFree = 4
        k.check()
        now += BufferMemoryKeeper.ESCALATE_AFTER_MS * 3
        k.check()
        assertEquals(BufferMemoryKeeper.LEVEL_LOW, k.pressureLevel)
    }

    @Test
    fun `the budget comes back once the box has room again`() {
        val lc = customControl(budgetMb = 100)
        val k = keeper(lc)
        warn(k, ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        now += BufferMemoryKeeper.RESTORE_QUIET_MS - 1
        k.check()
        assertEquals(BufferMemoryKeeper.LEVEL_CRITICAL, k.pressureLevel)
        now += 1
        k.check()
        assertEquals(BufferMemoryKeeper.LEVEL_LOW, k.pressureLevel)
        now += BufferMemoryKeeper.RESTORE_QUIET_MS
        k.check()
        assertEquals(BufferMemoryKeeper.LEVEL_NONE, k.pressureLevel)
        take(110)
        assertTrue(lc.shouldContinueLoading(params(bufferedMs = 6_000)))
    }

    @Test
    fun `no room means the budget stays down`() {
        val k = keeper(customControl(budgetMb = 100))
        warn(k, ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        free = BufferSizing.FreeMemory(250 * mb, 130 * mb, false)
        now += BufferMemoryKeeper.RESTORE_QUIET_MS
        k.check()
        assertEquals(BufferMemoryKeeper.LEVEL_LOW, k.pressureLevel)
    }

    @Test
    fun `android saying memory is low has the same effect, and cuts deeper if it lasts`() {
        val k = keeper(customControl(budgetMb = 100))
        free = BufferSizing.FreeMemory(200 * mb, 130 * mb, true)
        k.onTick()
        assertEquals(BufferMemoryKeeper.LEVEL_LOW, k.pressureLevel)
        now += 10_000
        k.onTick()
        assertEquals(BufferMemoryKeeper.LEVEL_LOW, k.pressureLevel)
        now += BufferMemoryKeeper.ESCALATE_AFTER_MS
        k.onTick()
        assertEquals(BufferMemoryKeeper.LEVEL_CRITICAL, k.pressureLevel)
    }

    @Test
    fun `trim signals reach the player through the app's one callback`() {
        val k = keeper(customControl())
        k.attach()
        free = BufferSizing.FreeMemory(200 * mb, 130 * mb, false)
        try {
            MemoryPressure.dispatch(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
            assertEquals(BufferMemoryKeeper.LEVEL_NONE, k.pressureLevel)
            MemoryPressure.dispatch(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
            assertEquals(BufferMemoryKeeper.LEVEL_LOW, k.pressureLevel)
        } finally {
            k.detach()
        }
        MemoryPressure.dispatch(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertEquals(BufferMemoryKeeper.LEVEL_LOW, k.pressureLevel)
    }

    @Test
    fun `a lowered budget shrinks the pool`() {
        val lc = customControl(budgetMb = 100)
        val k = keeper(lc)
        giveBack(take(80))
        lc.setBudgetBytesOverride(40 * mb)
        k.onBudgetLowered()
        assertTrue(allocator.availableBytes <= BufferMemoryKeeper.KEEP_IDLE_BYTES)
    }

    @Test
    fun `extra downloads take their memory off the buffer budget and give it back`() {
        val control = customControl(budgetMb = 200)
        val keeper = keeper(control)
        keeper.setLentBytes(48 * mb)
        assertEquals(152 * mb, keeper.effectiveCapBytes())
        assertEquals((152 * mb).toInt(), control.allocatorTargetBytes())
        keeper.setLentBytes(0L)
        assertEquals(-1L, keeper.effectiveCapBytes())
        assertEquals((200 * mb).toInt(), control.allocatorTargetBytes())
    }

    @Test
    fun `lent memory never takes the buffer below its floor`() {
        val keeper = keeper(customControl(budgetMb = 100))
        keeper.setLentBytes(96 * mb)
        assertEquals(BufferSizing.MIN_SIZED_BUFFER_MB * mb, keeper.effectiveCapBytes())
    }

    @Test
    fun `lent memory leaves eight seconds of film plus a piece`() {
        val control = customControl(budgetMb = 108)
        val keeper = keeper(control)
        val bps = 82_373_000L
        val floor = BufferMemoryKeeper.lendFloorBytes(bps, 2 * mb)
        assertEquals(bps / 8 * 8 + 2 * mb, floor)
        keeper.setLendFloorBytes(floor)
        keeper.setLentBytes(56 * mb)
        assertEquals(floor, keeper.effectiveCapBytes())
        assertEquals(floor.toInt(), control.allocatorTargetBytes())
        // A light film keeps the sized floor.
        keeper.setLendFloorBytes(BufferMemoryKeeper.lendFloorBytes(8_000_000L, 2 * mb))
        assertEquals(52 * mb, keeper.effectiveCapBytes())
        // No bitrate yet: the sized floor alone.
        assertEquals(0L, BufferMemoryKeeper.lendFloorBytes(0L, 2 * mb))
        keeper.setLendFloorBytes(0L)
        keeper.setLentBytes(96 * mb)
        assertEquals(BufferSizing.MIN_SIZED_BUFFER_MB * mb, keeper.effectiveCapBytes())
    }

    @Test
    fun `a floor above the budget means nothing is lent`() {
        val control = customControl(budgetMb = 60)
        val keeper = keeper(control)
        keeper.setLendFloorBytes(84 * mb)
        keeper.setLentBytes(30 * mb)
        assertEquals(60 * mb, keeper.effectiveCapBytes())
        assertEquals((60 * mb).toInt(), control.allocatorTargetBytes())
    }

    @Test
    fun `lending waits for the first picture and the start cushion`() {
        val control = customControl(budgetMb = 108)
        val keeper = keeper(control)
        keeper.setLendingHeld(true)
        keeper.setLentBytes(56 * mb)
        assertEquals(56 * mb, keeper.lentBytes)
        assertEquals(-1L, keeper.effectiveCapBytes())
        assertEquals((108 * mb).toInt(), control.allocatorTargetBytes())
        take(100)
        assertTrue(control.shouldContinueLoading(params(bufferedMs = 5_000)))
        keeper.setLendingHeld(false)
        assertEquals(52 * mb, keeper.effectiveCapBytes())
        assertEquals((52 * mb).toInt(), control.allocatorTargetBytes())
        assertFalse(control.shouldContinueLoading(params(bufferedMs = 5_000)))
        keeper.setLendingHeld(true)
        assertEquals(-1L, keeper.effectiveCapBytes())
        keeper.setLentBytes(0L)
        keeper.setLendingHeld(false)
        assertEquals(-1L, keeper.effectiveCapBytes())
    }

    @Test
    fun `nothing is lent before the buffer has a size`() {
        val control = ReservableLoadControl(
            allocator = allocator,
            minBufferMs = 15_000,
            maxBufferMs = 45_000,
            bufferForPlaybackMs = 3_000,
            bufferForPlaybackAfterRebufferMs = 3_000,
            targetBufferBytes = C.LENGTH_UNSET,
            prioritizeTimeOverSizeThresholds = false,
            backBufferDurationMs = 0,
            retainBackBufferFromKeyframe = true,
        )
        val keeper = keeper(control)
        keeper.setLentBytes(48 * mb)
        assertEquals(-1L, keeper.effectiveCapBytes())
    }

    @Test
    fun `a hand-set size is not cut while memory is low, but idle memory is still given back`() {
        val lc = customControl(budgetMb = 100)
        val k = keeper(lc, budgetManaged = false)
        giveBack(take(30))
        warn(k, ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertEquals(BufferMemoryKeeper.LEVEL_NONE, k.pressureLevel)
        assertTrue(allocator.availableBytes <= BufferMemoryKeeper.KEEP_IDLE_BYTES)
        free = BufferSizing.FreeMemory(140 * mb, 130 * mb, true)
        repeat(5) {
            now += BufferMemoryKeeper.ESCALATE_AFTER_MS
            k.onTick()
        }
        assertEquals(BufferMemoryKeeper.LEVEL_NONE, k.pressureLevel)
        assertEquals(-1L, k.effectiveCapBytes())
        assertEquals((100 * mb).toInt(), lc.allocatorTargetBytes())
        take(90)
        assertTrue(lc.shouldContinueLoading(params(bufferedMs = 6_000)))
    }

    @Test
    fun `a hand-set size is still cut when memory is critical`() {
        val lc = customControl(budgetMb = 200)
        val k = keeper(lc, budgetManaged = false)
        warn(k, ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertEquals(BufferMemoryKeeper.LEVEL_CRITICAL, k.pressureLevel)
        assertEquals(70 * mb, k.effectiveCapBytes())
        take(90)
        assertFalse(lc.shouldContinueLoading(params(bufferedMs = 6_000)))
    }

    @Test
    fun `a critical cut on a hand-set size stays while free memory cannot be read`() {
        val k = keeper(customControl(budgetMb = 100), budgetManaged = false)
        warn(k, ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        free = null
        now += BufferMemoryKeeper.RESTORE_QUIET_MS
        k.check()
        assertEquals(BufferMemoryKeeper.LEVEL_CRITICAL, k.pressureLevel)
    }

    @Test
    fun `a critical cut on a hand-set size comes back in full once memory is no longer low`() {
        val lc = customControl(budgetMb = 100)
        val k = keeper(lc, budgetManaged = false)
        warn(k, ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        free = BufferSizing.FreeMemory(140 * mb, 130 * mb, true)
        now += BufferMemoryKeeper.RESTORE_QUIET_MS
        k.check()
        assertEquals(BufferMemoryKeeper.LEVEL_CRITICAL, k.pressureLevel)
        free = BufferSizing.FreeMemory(200 * mb, 130 * mb, false)
        now += BufferMemoryKeeper.RESTORE_QUIET_MS - 1
        k.check()
        assertEquals(BufferMemoryKeeper.LEVEL_CRITICAL, k.pressureLevel)
        now += 1
        k.check()
        assertEquals(BufferMemoryKeeper.LEVEL_NONE, k.pressureLevel)
        assertEquals((100 * mb).toInt(), lc.allocatorTargetBytes())
    }

    @Test
    fun `turning Managed memory budget off lifts a low-memory cut but keeps a critical one`() {
        val lc = customControl(budgetMb = 100)
        val k = keeper(lc)
        warn(k, ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertEquals((60 * mb).toInt(), lc.allocatorTargetBytes())
        k.setBudgetManaged(false)
        assertEquals(BufferMemoryKeeper.LEVEL_NONE, k.pressureLevel)
        assertEquals((100 * mb).toInt(), lc.allocatorTargetBytes())

        val critical = keeper(customControl(budgetMb = 100))
        warn(critical, ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        critical.setBudgetManaged(false)
        assertEquals(BufferMemoryKeeper.LEVEL_CRITICAL, critical.pressureLevel)
    }

    @Test
    fun `a hand-set size still gives memory back after seeks and lends it to extra downloads`() {
        val k = keeper(customControl(budgetMb = 200), budgetManaged = false)
        giveBack(take(40))
        k.onSeek()
        now += BufferMemoryKeeper.SEEK_SETTLE_MS
        delayed.removeAt(0)()
        assertTrue(allocator.availableBytes <= BufferMemoryKeeper.KEEP_IDLE_BYTES)
        k.setLentBytes(48 * mb)
        assertEquals(152 * mb, k.effectiveCapBytes())
    }

    @Test
    fun `only a size set by hand counts as unmanaged`() {
        assertFalse(BufferMemoryKeeper.budgetManaged(customBuffers = true, budgetManaged = false))
        assertTrue(BufferMemoryKeeper.budgetManaged(customBuffers = true, budgetManaged = true))
        assertTrue(BufferMemoryKeeper.budgetManaged(customBuffers = false, budgetManaged = false))
        assertTrue(BufferMemoryKeeper.budgetManaged(customBuffers = false, budgetManaged = true))
    }

    @Test
    fun `a start-sized budget grows back a step at a time while the box has room, and loading follows`() {
        val lc = performanceControl(budgetMb = 100)
        val k = keeper(lc, ceilingMb = 200)
        take(105)
        assertFalse(lc.shouldContinueLoading(params(bufferedMs = 6_000)))
        k.check()
        assertEquals(132 * mb, lc.uncappedBudgetBytes)
        assertEquals(32 * mb, k.grownBytes)
        assertTrue(lc.shouldContinueLoading(params(bufferedMs = 6_000)))
        k.check(); k.check()
        assertEquals(196 * mb, lc.uncappedBudgetBytes)
        k.check()
        assertEquals(200 * mb, lc.uncappedBudgetBytes)
        k.check()
        assertEquals(200 * mb, lc.uncappedBudgetBytes)
    }

    @Test
    fun `no growth while the start squeeze is still on, or beside a nearly full swap`() {
        val lc = customControl(budgetMb = 90)
        val k = keeper(lc, ceilingMb = 200)
        // First frame on a Fire TV Stick: 283 MB available against a 144 MB line.
        free = BufferSizing.FreeMemory(283 * mb, 144 * mb, false)
        k.check()
        assertEquals(90 * mb, lc.uncappedBudgetBytes)
        free = BufferSizing.FreeMemory(520 * mb, 144 * mb, false)
        swapFree = 50
        k.check()
        assertEquals(90 * mb, lc.uncappedBudgetBytes)
        swapFree = 200
        k.check()
        assertEquals(122 * mb, lc.uncappedBudgetBytes)
        // A Google TV box keeps swap nearly full while plenty of RAM is free.
        swapFree = 60
        free = BufferSizing.FreeMemory(658 * mb, 144 * mb, false)
        k.check()
        assertEquals(154 * mb, lc.uncappedBudgetBytes)
    }

    @Test
    fun `memory pressure gives the growth back first and caps the built size`() {
        val lc = customControl(budgetMb = 100)
        val k = keeper(lc, ceilingMb = 200)
        k.check(); k.check()
        assertEquals(164 * mb, lc.uncappedBudgetBytes)
        warn(k, ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertEquals(0L, k.grownBytes)
        assertEquals(100 * mb, lc.uncappedBudgetBytes)
        assertEquals(60 * mb, k.capBytes(BufferMemoryKeeper.LEVEL_LOW))
        // Growth waits for the quiet minute after pressure has eased.
        now += BufferMemoryKeeper.RESTORE_QUIET_MS
        k.check()
        assertEquals(BufferMemoryKeeper.LEVEL_NONE, k.pressureLevel)
        assertEquals(100 * mb, lc.uncappedBudgetBytes)
        now += BufferMemoryKeeper.RESTORE_QUIET_MS
        k.check()
        assertEquals(132 * mb, lc.uncappedBudgetBytes)
    }

    @Test
    fun `room gone without pressure takes a step back, never below the built size`() {
        val lc = customControl(budgetMb = 100)
        val k = keeper(lc, ceilingMb = 200)
        k.check()
        assertEquals(132 * mb, lc.uncappedBudgetBytes)
        giveBack(take(40))
        free = BufferSizing.FreeMemory(220 * mb, 144 * mb, false)
        k.check()
        assertEquals(100 * mb, lc.uncappedBudgetBytes)
        assertTrue(allocator.availableBytes <= BufferMemoryKeeper.KEEP_IDLE_BYTES)
        k.check()
        assertEquals(100 * mb, lc.uncappedBudgetBytes)
    }

    @Test
    fun `a hand-set size and a lowered DV7 budget do not grow`() {
        val k = keeper(customControl(budgetMb = 100), budgetManaged = false, ceilingMb = 200)
        k.check()
        assertEquals(0L, k.grownBytes)
        val lc = customControl(budgetMb = 100)
        val g = keeper(lc, ceilingMb = 200)
        g.check()
        assertEquals(132 * mb, lc.uncappedBudgetBytes)
        lc.setBudgetBytesOverride(70 * mb)
        g.onBudgetLowered()
        assertEquals(70 * mb, lc.uncappedBudgetBytes)
        g.check()
        assertEquals(70 * mb, lc.uncappedBudgetBytes)
    }
    @Test
    fun `a warning the box's own numbers do not confirm only gives idle memory back`() {
        val lc = customControl(budgetMb = 100)
        val k = keeper(lc, ceilingMb = 200)
        k.check()
        assertEquals(132 * mb, lc.uncappedBudgetBytes)
        giveBack(take(30))
        // A Fire TV Stick at a 4K film's first frame: Android says critical with 273 MB free against a 144 MB line.
        free = BufferSizing.FreeMemory(273 * mb, 144 * mb, false)
        swapFree = 68
        k.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertEquals(BufferMemoryKeeper.LEVEL_NONE, k.pressureLevel)
        assertEquals(132 * mb, lc.uncappedBudgetBytes)
        assertEquals(-1L, k.effectiveCapBytes())
        assertTrue(allocator.availableBytes <= BufferMemoryKeeper.KEEP_IDLE_BYTES)
        // The warning was not pressure: growth goes on at the next check without a quiet minute.
        free = BufferSizing.FreeMemory(800 * mb, 130 * mb, false)
        swapFree = -1
        k.check()
        assertEquals(164 * mb, lc.uncappedBudgetBytes)
    }

    @Test
    fun `the warning stands inside the margin, past the line, nearly out of swap, or with memory unreadable`() {
        assertFalse(BufferMemoryKeeper.warningStands(BufferSizing.FreeMemory(273 * mb, 144 * mb, false), 68))
        assertTrue(BufferMemoryKeeper.warningStands(BufferSizing.FreeMemory(240 * mb, 144 * mb, false), 68))
        assertTrue(BufferMemoryKeeper.warningStands(BufferSizing.FreeMemory(400 * mb, 144 * mb, true), 68))
        assertTrue(BufferMemoryKeeper.warningStands(BufferSizing.FreeMemory(400 * mb, 144 * mb, false), 20))
        assertFalse(BufferMemoryKeeper.warningStands(BufferSizing.FreeMemory(400 * mb, 144 * mb, false), -1))

        val inside = keeper(customControl(budgetMb = 100))
        free = BufferSizing.FreeMemory(240 * mb, 144 * mb, false)
        inside.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertEquals(BufferMemoryKeeper.LEVEL_LOW, inside.pressureLevel)

        val swap = keeper(customControl(budgetMb = 100))
        free = BufferSizing.FreeMemory(400 * mb, 144 * mb, false)
        swapFree = 20
        swap.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertEquals(BufferMemoryKeeper.LEVEL_CRITICAL, swap.pressureLevel)

        val unknown = keeper(customControl(budgetMb = 100))
        free = null
        swapFree = -1
        unknown.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertEquals(BufferMemoryKeeper.LEVEL_LOW, unknown.pressureLevel)
    }

    /** A 904 MB TV while playing: 229 MB free against a 64 MB line, 160 MB of swap left. */
    private fun oneGbTvNumbers() {
        free = BufferSizing.FreeMemory(229 * mb, 64 * mb, false)
        swapFree = 160
    }

    @Test
    fun `on a 1 GB box Android's last warning stands even with free memory`() {
        oneGbTvNumbers()
        assertFalse(BufferMemoryKeeper.warningStands(free!!, swapFree))
        assertTrue(BufferMemoryKeeper.oneGbLastWarning(ComponentCallbacks2.TRIM_MEMORY_COMPLETE, oneGbBox = true))
        assertFalse(BufferMemoryKeeper.oneGbLastWarning(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, oneGbBox = true))
        assertFalse(BufferMemoryKeeper.oneGbLastWarning(ComponentCallbacks2.TRIM_MEMORY_COMPLETE, oneGbBox = false))

        val floorMb = BufferSizing.sizedFloorMb(oneGbBox = true, bitrateBps = 8_000_000L)
        val lc = customControl(budgetMb = 25)
        val k = keeper(lc, floorMb = floorMb, oneGbBox = true)
        giveBack(take(10))
        k.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        assertEquals(BufferMemoryKeeper.LEVEL_CRITICAL, k.pressureLevel)
        assertEquals(16 * mb, k.effectiveCapBytes())
        assertEquals(1, lastWarnings)
        assertTrue(allocator.availableBytes <= BufferMemoryKeeper.KEEP_IDLE_BYTES)
    }

    @Test
    fun `on a 1 GB box the earlier warnings are still judged by the box's numbers`() {
        oneGbTvNumbers()
        val k = keeper(customControl(budgetMb = 25), floorMb = 16, oneGbBox = true)
        k.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertEquals(BufferMemoryKeeper.LEVEL_NONE, k.pressureLevel)
        assertEquals(0, lastWarnings)
    }

    @Test
    fun `a 1 GB box with a hand-set size still takes the last warning, as critical always did`() {
        oneGbTvNumbers()
        val k = keeper(customControl(budgetMb = 60), budgetManaged = false, floorMb = 16, oneGbBox = true)
        k.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        assertEquals(BufferMemoryKeeper.LEVEL_CRITICAL, k.pressureLevel)
        assertEquals(21 * mb, k.effectiveCapBytes())
    }

    @Test
    fun `2 GB and 4 GB boxes set the same warning aside as before`() {
        for ((totalMem, budgetMb) in listOf((1.9 * 1024 * mb).toLong() to 200L, (3.8 * 1024 * mb).toLong() to 1000L)) {
            val oneGb = BufferSizing.isOneGbBox(totalMem, lowRamDevice = true)
            assertFalse(oneGb)
            val floorMb = BufferSizing.sizedFloorMb(oneGb, 8_000_000L)
            assertEquals(BufferSizing.MIN_SIZED_BUFFER_MB, floorMb)
            oneGbTvNumbers()
            lastWarnings = 0
            val lc = customControl(budgetMb = budgetMb)
            val k = keeper(lc, floorMb = floorMb, oneGbBox = oneGb)
            k.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
            assertEquals(BufferMemoryKeeper.LEVEL_NONE, k.pressureLevel)
            assertEquals(-1L, k.effectiveCapBytes())
            assertEquals(budgetMb * mb, lc.uncappedBudgetBytes)
            assertEquals(0, lastWarnings)
            // A warning the numbers confirm cuts to the same caps as before.
            free = BufferSizing.FreeMemory(200 * mb, 130 * mb, false)
            k.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
            assertEquals(BufferMemoryKeeper.LEVEL_CRITICAL, k.pressureLevel)
            val expected = maxOf(budgetMb * mb * BufferMemoryKeeper.CRITICAL_SHARE_PERCENT / 100, 50 * mb)
            assertEquals(expected, k.effectiveCapBytes())
            assertEquals(0, lastWarnings)
        }
    }

    @Test
    fun `swap nearly gone with thin room lowers the budget without a warning`() {
        // A 2 GB Xiaomi box during a 4K film: 276 MB available against a 144 MB line, swap empty, no trim warning.
        val lc = customControl(budgetMb = 200)
        val k = keeper(lc)
        free = BufferSizing.FreeMemory(276 * mb, 144 * mb, false)
        swapFree = 0
        k.check()
        assertEquals(BufferMemoryKeeper.LEVEL_LOW, k.pressureLevel)
        assertTrue(k.effectiveCapBytes() in 1 until 200 * mb)

        val roomy = keeper(customControl(budgetMb = 200))
        free = BufferSizing.FreeMemory(658 * mb, 144 * mb, false)
        roomy.check()
        assertEquals(BufferMemoryKeeper.LEVEL_NONE, roomy.pressureLevel)

        val swapLeft = keeper(customControl(budgetMb = 200))
        free = BufferSizing.FreeMemory(276 * mb, 144 * mb, false)
        swapFree = 300
        swapLeft.check()
        assertEquals(BufferMemoryKeeper.LEVEL_NONE, swapLeft.pressureLevel)

        val userSize = keeper(customControl(budgetMb = 200), budgetManaged = false)
        swapFree = 0
        userSize.check()
        assertEquals(BufferMemoryKeeper.LEVEL_NONE, userSize.pressureLevel)
    }

    @Test
    fun `a box without swap reads -1 and never arms the swap rule`() {
        val thin = BufferSizing.FreeMemory(276 * mb, 144 * mb, false)
        assertFalse(BufferMemoryKeeper.swapRunsOut(thin, -1L))
        assertTrue(BufferMemoryKeeper.swapRunsOut(thin, 0L))
    }

    @Test
    fun `a budget that is not filled yet does not grow on memory the fill will use`() {
        val lc = performanceControl(budgetMb = 731)
        val k = keeper(lc, ceilingMb = 1000)
        // An AM9 at the start of a 4K film: 891 MB available against a 216 MB line, the buffer nearly empty.
        free = BufferSizing.FreeMemory(891 * mb, 216 * mb, false)
        take(10)
        k.check()
        assertEquals(0L, k.grownBytes)
        assertEquals(731 * mb, lc.uncappedBudgetBytes)
    }

    @Test
    fun `a filled budget grows while the box has room beside it`() {
        val lc = performanceControl(budgetMb = 100)
        val k = keeper(lc, ceilingMb = 200)
        free = BufferSizing.FreeMemory(700 * mb, 216 * mb, false)
        take(100)
        k.check()
        assertEquals(32 * mb, k.grownBytes)
        k.check()
        assertEquals(64 * mb, k.grownBytes)
    }

    @Test
    fun `growth stops when the unfilled steps would take the room`() {
        val lc = performanceControl(budgetMb = 100)
        val k = keeper(lc, ceilingMb = 400)
        // Room above the line and margin: 180 MB. Each step adds 32 MB that is not filled.
        free = BufferSizing.FreeMemory(496 * mb, 216 * mb, false)
        take(100)
        repeat(6) { k.check() }
        assertEquals(96 * mb, k.grownBytes)
    }

    @Test
    fun `a paused film does not grow the budget`() {
        val lc = performanceControl(budgetMb = 100)
        val k = keeper(lc, ceilingMb = 200)
        free = BufferSizing.FreeMemory(700 * mb, 216 * mb, false)
        take(100)
        repeat(3) { k.check(playing = false) }
        assertEquals(0L, k.grownBytes)
        k.check(playing = true)
        assertEquals(32 * mb, k.grownBytes)
    }

    @Test
    fun `paused with little room, loading stops at what is held until play`() {
        val lc = performanceControl(budgetMb = 400)
        val k = keeper(lc, ceilingMb = 400)
        // The AM9 paused on a 4K film: 452 MB available against a 216 MB line.
        free = BufferSizing.FreeMemory(452 * mb, 216 * mb, false)
        take(300)
        k.check(playing = false)
        assertEquals(300 * mb, k.pausedCapBytes)
        assertEquals(300 * mb, k.effectiveCapBytes())
        assertEquals(300 * mb, lc.allocatorTargetBytes().toLong())
        take(20)
        k.check(playing = false)
        assertEquals(300 * mb, k.pausedCapBytes)
        k.check(playing = true)
        assertEquals(-1L, k.pausedCapBytes)
        assertEquals(400 * mb, lc.allocatorTargetBytes().toLong())
    }

    @Test
    fun `paused with room to spare keeps loading`() {
        val lc = performanceControl(budgetMb = 400)
        val k = keeper(lc, ceilingMb = 400)
        free = BufferSizing.FreeMemory(1200 * mb, 216 * mb, false)
        take(300)
        k.check(playing = false)
        assertEquals(-1L, k.pausedCapBytes)
        assertEquals(400 * mb, lc.allocatorTargetBytes().toLong())
    }

    @Test
    fun `the paused cap never goes below the floor and joins a pressure cut`() {
        val lc = performanceControl(budgetMb = 400)
        val k = keeper(lc, ceilingMb = 400)
        free = BufferSizing.FreeMemory(452 * mb, 216 * mb, false)
        take(10)
        k.check(playing = false)
        assertEquals(BufferSizing.MIN_SIZED_BUFFER_MB * mb, k.pausedCapBytes)
        warn(k, ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertEquals(minOf(k.capBytes(BufferMemoryKeeper.LEVEL_LOW), k.pausedCapBytes), k.effectiveCapBytes())
    }

    @Test
    fun `leaving the app frees what the teardown hands back as it arrives`() {
        val queued = mutableListOf<Runnable>()
        val stopTrim = AllocatorStopTrim(allocator, background = { queued += it })
        val held = take(64)
        var waits = 0
        stopTrim.trimUntilEmpty(steps = 6, pause = { waits++; if (waits == 2) giveBack(held) })
        assertEquals(1, queued.size)
        queued.single().run()
        assertEquals(0, allocator.totalBytesAllocated)
        assertEquals(0L, allocator.memoryFootprint.toLong())
        assertTrue(waits in 2..3)
    }
}
