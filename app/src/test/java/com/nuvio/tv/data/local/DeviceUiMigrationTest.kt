package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.nuvio.tv.domain.model.InterfaceExperience
import com.nuvio.tv.domain.model.UiScaleMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceUiMigrationTest {
    @Test
    fun `existing explicit scale including 100 percent remains manual`() = runTest {
        for (saved in listOf(85, 95, 100, 115)) {
            val migration = DeviceUiMigration { saved }
            val migrated = migration.migrate(emptyPreferences())
            val value = DeviceUiPreferenceCodec.decode(migrated)
            assertEquals(saved, value.manualUiScalePercent)
            assertEquals(UiScaleMode.MANUAL, value.uiScaleMode)
            assertEquals(InterfaceExperience.ORIGINAL_NUVIO, value.interfaceExperience)
            assertFalse(migration.shouldMigrate(migrated))
        }
    }

    @Test
    fun `new install defaults to automatic and future migrations do not re-read legacy settings`() = runTest {
        val migration = DeviceUiMigration { null }
        assertTrue(migration.shouldMigrate(emptyPreferences()))
        val migrated = migration.migrate(emptyPreferences())
        assertEquals(UiScaleMode.AUTOMATIC, DeviceUiPreferenceCodec.decode(migrated).uiScaleMode)
        val restored = DeviceUiMigration { error("Legacy store must not be read again") }
        assertFalse(restored.shouldMigrate(migrated))
    }

    @Test
    fun `unknown preferences and corrupt percentages have safe defaults`() {
        val p = mutablePreferencesOf(
            DeviceUiPreferenceCodec.experience to "future_renderer",
            DeviceUiPreferenceCodec.scaleMode to "future_scale",
            DeviceUiPreferenceCodec.manualScale to 1,
            DeviceUiPreferenceCodec.fineTune to 999
        )
        val decoded = DeviceUiPreferenceCodec.decode(p)
        assertEquals(InterfaceExperience.ORIGINAL_NUVIO, decoded.interfaceExperience)
        assertEquals(UiScaleMode.AUTOMATIC, decoded.uiScaleMode)
        assertEquals(75, decoded.manualUiScalePercent)
        assertEquals(10, decoded.autoScaleFineTunePercent)
    }
}
