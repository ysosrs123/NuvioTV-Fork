package com.nuvio.tv.core.player.thumbnail

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class SeekThumbnailPreferencesTest {
    private val modeKey = stringPreferencesKey("seek_thumbnails_mode")
    private val legacyEnabledKey = booleanPreferencesKey("seek_thumbnails_enabled")
    private val legacyFourKKey = stringPreferencesKey("seek_thumbnails_4k_mode")
    private val copyKey = booleanPreferencesKey("seek_thumbnails_from_copy")
    private val prepareKey = stringPreferencesKey("seek_thumbnails_prepare")
    private val beforePlayKey = stringPreferencesKey("seek_thumbnails_before_play")
    private val beforePlayMigration = SeekThumbnailPreferences.beforePlayDefaultMigration

    private fun prefs(block: MutablePreferences.() -> Unit = {}): Preferences =
        emptyPreferences().toMutablePreferences().apply(block).toPreferences()

    private fun migratedBeforePlay(p: Preferences): Preferences = runBlocking {
        if (beforePlayMigration.shouldMigrate(p)) beforePlayMigration.migrate(p) else p
    }

    private fun beforePlay(p: Preferences): ThumbsBeforePlay = SeekThumbnailPreferences.beforePlayOf(p)

    @Test
    fun `untouched store reads as all resolutions`() {
        assertEquals(SeekThumbMode.ALL, SeekThumbnailPreferences.modeOf(emptyPreferences()))
    }

    @Test
    fun `untouched store is on with 3 GB of RAM and off with 2 GB`() {
        val mb = 1024L * 1024
        mapOf(
            1_669L to SeekThumbMode.OFF,
            1_946L to SeekThumbMode.OFF,
            2_048L to SeekThumbMode.OFF,
            2_867L to SeekThumbMode.ALL,
            2_900L to SeekThumbMode.ALL,
            3_174L to SeekThumbMode.ALL,
            3_712L to SeekThumbMode.ALL,
            4_096L to SeekThumbMode.ALL,
        ).forEach { (totalMb, expected) ->
            val default = SeekThumbnailPreferences.defaultMode(totalMb * mb)
            assertEquals("$totalMb MB", expected, SeekThumbnailPreferences.modeOf(emptyPreferences(), default))
        }
        assertEquals(SeekThumbMode.ALL, SeekThumbnailPreferences.defaultMode(0L))
    }

    @Test
    fun `a 3 GB box keeps a stored Off`() {
        val default = SeekThumbnailPreferences.defaultMode(2_900L * 1024 * 1024)
        assertEquals(SeekThumbMode.OFF, SeekThumbnailPreferences.modeOf(prefs { this[modeKey] = SeekThumbMode.OFF.name }, default))
        assertEquals(SeekThumbMode.OFF, SeekThumbnailPreferences.modeOf(prefs { this[legacyEnabledKey] = false }, default))
        assertEquals(SeekThumbMode.ALL, SeekThumbnailPreferences.modeOf(prefs { this[modeKey] = SeekThumbMode.HD.name }, default))
    }

    @Test
    fun `a stored choice is kept whatever the RAM`() {
        listOf(SeekThumbMode.ALL, SeekThumbMode.OFF).forEach { default ->
            SeekThumbMode.entries.forEach { mode ->
                assertEquals(onOrOff(mode), SeekThumbnailPreferences.modeOf(prefs { this[modeKey] = mode.name }, default))
            }
            assertEquals(SeekThumbMode.ALL, SeekThumbnailPreferences.modeOf(prefs { this[legacyEnabledKey] = true }, default))
            assertEquals(SeekThumbMode.OFF, SeekThumbnailPreferences.modeOf(prefs { this[legacyEnabledKey] = false }, default))
        }
    }

    @Test
    fun `stored mode is kept`() {
        SeekThumbMode.entries.forEach { mode ->
            assertEquals(onOrOff(mode), SeekThumbnailPreferences.modeOf(prefs { this[modeKey] = mode.name }))
        }
    }

    @Test
    fun `a stored up to 1080p reads as on`() {
        assertEquals(SeekThumbMode.ALL, SeekThumbnailPreferences.modeOf(prefs { this[modeKey] = SeekThumbMode.HD.name }))
        assertEquals(SeekThumbMode.ALL, SeekThumbnailPreferences.modeOf(prefs { this[modeKey] = SeekThumbMode.HD.name }, SeekThumbMode.OFF))
    }

    private fun onOrOff(mode: SeekThumbMode) = if (mode == SeekThumbMode.OFF) SeekThumbMode.OFF else SeekThumbMode.ALL

    @Test
    fun `unknown stored mode reads as off`() {
        assertEquals(SeekThumbMode.OFF, SeekThumbnailPreferences.modeOf(prefs { this[modeKey] = "FOURK_ONLY" }))
    }

    @Test
    fun `legacy switch on reads as on, with 4K off too`() {
        assertEquals(SeekThumbMode.ALL, SeekThumbnailPreferences.modeOf(prefs { this[legacyEnabledKey] = true }))
        assertEquals(
            SeekThumbMode.ALL,
            SeekThumbnailPreferences.modeOf(prefs { this[legacyEnabledKey] = true; this[legacyFourKKey] = "AUTO" })
        )
        assertEquals(
            SeekThumbMode.ALL,
            SeekThumbnailPreferences.modeOf(prefs { this[legacyEnabledKey] = true; this[legacyFourKKey] = "OFF" })
        )
    }

    @Test
    fun `legacy switch off stays off`() {
        assertEquals(SeekThumbMode.OFF, SeekThumbnailPreferences.modeOf(prefs { this[legacyEnabledKey] = false }))
        assertEquals(
            SeekThumbMode.OFF,
            SeekThumbnailPreferences.modeOf(prefs { this[legacyEnabledKey] = false; this[legacyFourKKey] = "OFF" })
        )
    }

    @Test
    fun `untouched store gets Faster before play`() {
        val out = migratedBeforePlay(emptyPreferences())
        assertEquals(ThumbsBeforePlay.FASTER.name, out[beforePlayKey])
        assertEquals(ThumbsBeforePlay.FASTER, beforePlay(out))
        // An older copy choice stored on an install that never chose a mode: still a new install.
        assertEquals(ThumbsBeforePlay.FASTER, beforePlay(migratedBeforePlay(prefs { this[copyKey] = false })))
    }

    @Test
    fun `store with only other settings gets Faster before play`() {
        val out = migratedBeforePlay(prefs { this[prepareKey] = ThumbPrepareMode.ALWAYS.name; this[copyKey] = true })
        assertEquals(ThumbsBeforePlay.FASTER.name, out[beforePlayKey])
        assertEquals(ThumbPrepareMode.ALWAYS.name, out[prepareKey])
    }

    @Test
    fun `an install that chose a thumbnail mode keeps Full before play`() {
        SeekThumbMode.entries.forEach { mode ->
            assertEquals(ThumbsBeforePlay.FULL, beforePlay(migratedBeforePlay(prefs { this[modeKey] = mode.name })))
        }
        listOf(true, false).forEach { on ->
            assertEquals(ThumbsBeforePlay.FULL, beforePlay(migratedBeforePlay(prefs { this[legacyEnabledKey] = on })))
        }
    }

    @Test
    fun `stored before-play choice is never changed`() = runBlocking {
        ThumbsBeforePlay.entries.forEach { choice ->
            listOf(prefs { this[beforePlayKey] = choice.name }, prefs { this[modeKey] = "ALL"; this[beforePlayKey] = choice.name }).forEach { p ->
                assertFalse(beforePlayMigration.shouldMigrate(p))
                assertEquals(choice, beforePlay(beforePlayMigration.migrate(p)))
            }
        }
    }

    @Test
    fun `before-play migration runs once`() = runBlocking {
        val first = beforePlayMigration.migrate(emptyPreferences())
        assertFalse(beforePlayMigration.shouldMigrate(first))
        assertTrue(beforePlayMigration.shouldMigrate(emptyPreferences()))
    }

    @Test
    fun `changing the mode later keeps Faster for a new install`() {
        val out = migratedBeforePlay(emptyPreferences()).toMutablePreferences().apply {
            this[modeKey] = SeekThumbMode.HD.name
        }.toPreferences()
        assertEquals(ThumbsBeforePlay.FASTER, beforePlay(migratedBeforePlay(out)))
    }

    @Test
    fun `unknown or missing before-play value reads as Faster`() {
        assertEquals(ThumbsBeforePlay.FASTER, beforePlay(emptyPreferences()))
        assertEquals(ThumbsBeforePlay.FASTER, beforePlay(prefs { this[beforePlayKey] = "QUICK" }))
        assertEquals(ThumbsBeforePlay.FULL, beforePlay(prefs { this[beforePlayKey] = "FULL" }))
    }

    @Test
    fun `unreadable store reads as off`() = runBlocking {
        val failing: Flow<Preferences> = flow { throw IOException("disk full") }
        assertEquals(SeekThumbMode.OFF, SeekThumbnailPreferences.modeFlow(failing).first())
        assertEquals(ThumbPrepareMode.OFF, SeekThumbnailPreferences.prepareModeFlow(failing).first())
        assertEquals(ThumbsBeforePlay.FASTER, SeekThumbnailPreferences.beforePlayFlow(failing).first())
    }

    @Test
    fun `hardware decoder is on until switched off`() = runBlocking {
        val hardwareKey = booleanPreferencesKey("seek_thumbnails_hardware")
        fun read(p: Preferences) = runBlocking { SeekThumbnailPreferences.hardwareFlow(flow { emit(p) }).first() }
        assertTrue(read(emptyPreferences()))
        assertTrue(read(prefs { this[modeKey] = "OFF"; this[copyKey] = false }))
        assertFalse(read(prefs { this[hardwareKey] = false }))
        assertTrue(read(prefs { this[hardwareKey] = true }))
        assertTrue(SeekThumbnailPreferences.hardwareFlow(flow { throw IOException("disk full") }).first())
    }

    @Test(expected = IllegalStateException::class)
    fun `other read errors are not swallowed`() = runBlocking<Unit> {
        SeekThumbnailPreferences.modeFlow(flow<Preferences> { throw IllegalStateException() }).first()
    }

    private fun <T> withStore(file: File, block: suspend (Flow<Preferences>) -> T): T = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val store = PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                migrations = listOf(beforePlayMigration),
                scope = scope,
                produceFile = { file }
            )
            block(store.data)
        } finally {
            scope.cancel()
        }
    }

    private fun tempStoreFile(): File =
        File(Files.createTempDirectory("seek-thumbs").toFile(), "seek_thumbnails.preferences_pb")

    @Test
    fun `new store is migrated before the first read and saved`() {
        val file = tempStoreFile()
        withStore(file) { data ->
            assertEquals(SeekThumbMode.ALL, SeekThumbnailPreferences.modeFlow(data).first())
            assertEquals(ThumbsBeforePlay.FASTER, SeekThumbnailPreferences.beforePlayFlow(data).first())
        }
        assertTrue(file.length() > 0)
        file.parentFile?.deleteRecursively()
    }

    @Test
    fun `corrupted store file starts again as a new install`() {
        val file = tempStoreFile()
        file.writeBytes(byteArrayOf(0x7f, 0x01, 0x02, 0x03, 0x04, 0x05))
        withStore(file) { data ->
            assertEquals(SeekThumbMode.ALL, SeekThumbnailPreferences.modeFlow(data).first())
            assertEquals(ThumbPrepareMode.OFF, SeekThumbnailPreferences.prepareModeFlow(data).first())
            assertEquals(ThumbsBeforePlay.FASTER, SeekThumbnailPreferences.beforePlayFlow(data).first())
        }
        file.parentFile?.deleteRecursively()
    }
}
