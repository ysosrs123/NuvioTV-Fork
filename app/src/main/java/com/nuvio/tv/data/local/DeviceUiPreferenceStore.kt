package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nuvio.tv.domain.model.DeviceUiPreferences
import com.nuvio.tv.domain.model.InterfaceExperience
import com.nuvio.tv.domain.model.UiScaleMode
import com.nuvio.tv.domain.model.VisualQualityMode
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.deviceUiDataStore by preferencesDataStore(
    name = "device_ui_preferences_v2",
    produceMigrations = { context ->
        listOf(DeviceUiMigration { UiScalePreference.storedPercent(context).first() })
    }
)

internal object DeviceUiPreferenceCodec {
    val version = intPreferencesKey("schema_version")
    val experience = stringPreferencesKey("interface_experience")
    val scaleMode = stringPreferencesKey("ui_scale_mode")
    val manualScale = intPreferencesKey("manual_ui_scale_percent")
    val fineTune = intPreferencesKey("auto_scale_fine_tune_percent")
    val quality = stringPreferencesKey("visual_quality_mode")
    val automaticTier = stringPreferencesKey("automatic_quality_tier")
    val assessmentKey = stringPreferencesKey("quality_assessment_key")

    fun decode(p: Preferences) = DeviceUiPreferences(
        interfaceExperience = InterfaceExperience.entries.firstOrNull { it.name == p[experience] }
            ?: InterfaceExperience.ORIGINAL_NUVIO,
        uiScaleMode = UiScaleMode.entries.firstOrNull { it.name == p[scaleMode] } ?: UiScaleMode.AUTOMATIC,
        manualUiScalePercent = (p[manualScale] ?: 100).coerceIn(75, 115),
        autoScaleFineTunePercent = (p[fineTune] ?: 0).coerceIn(-10, 10),
        visualQualityMode = VisualQualityMode.entries.firstOrNull { it.name == p[quality] }
            ?: VisualQualityMode.AUTOMATIC,
        automaticQualityTier = p[automaticTier],
        qualityAssessmentKey = p[assessmentKey]
    )
}

/** Seed once, without modifying the Original renderer's existing scale store. */
internal class DeviceUiMigration(private val legacyPercent: suspend () -> Int?) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences) = currentData[DeviceUiPreferenceCodec.version] == null

    override suspend fun migrate(currentData: Preferences): Preferences {
        val oldScale = legacyPercent()
        return currentData.toMutablePreferences().apply {
            this[DeviceUiPreferenceCodec.version] = 1
            this[DeviceUiPreferenceCodec.scaleMode] =
                (if (oldScale == null) UiScaleMode.AUTOMATIC else UiScaleMode.MANUAL).name
            this[DeviceUiPreferenceCodec.manualScale] = (oldScale ?: 100).coerceIn(85, 115)
        }
    }

    override suspend fun cleanUp() = Unit
}

object DeviceUiPreferenceStore {
    fun flow(context: Context): Flow<DeviceUiPreferences> = context.deviceUiDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map(DeviceUiPreferenceCodec::decode)

    suspend fun update(context: Context, transform: (DeviceUiPreferences) -> DeviceUiPreferences) {
        context.deviceUiDataStore.edit { p ->
            val next = transform(DeviceUiPreferenceCodec.decode(p))
            p[DeviceUiPreferenceCodec.experience] = next.interfaceExperience.name
            p[DeviceUiPreferenceCodec.scaleMode] = next.uiScaleMode.name
            p[DeviceUiPreferenceCodec.manualScale] = next.manualUiScalePercent.coerceIn(75, 115)
            p[DeviceUiPreferenceCodec.fineTune] = next.autoScaleFineTunePercent.coerceIn(-10, 10)
            p[DeviceUiPreferenceCodec.quality] = next.visualQualityMode.name
            next.automaticQualityTier?.let { p[DeviceUiPreferenceCodec.automaticTier] = it }
                ?: p.remove(DeviceUiPreferenceCodec.automaticTier)
            next.qualityAssessmentKey?.let { p[DeviceUiPreferenceCodec.assessmentKey] = it }
                ?: p.remove(DeviceUiPreferenceCodec.assessmentKey)
        }
    }
}
