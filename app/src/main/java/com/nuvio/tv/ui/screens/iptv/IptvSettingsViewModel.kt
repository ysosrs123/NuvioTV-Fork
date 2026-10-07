package com.nuvio.tv.ui.screens.iptv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.iptv.IptvDeviceProfile
import com.nuvio.tv.core.iptv.MultiviewLayout
import com.nuvio.tv.core.iptv.MultiviewQuality
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.iptv.IptvLivePreferences
import com.nuvio.tv.data.iptv.IptvStartView
import com.nuvio.tv.data.iptv.IptvStreamFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class IptvSettingsState(val format: IptvStreamFormat = IptvStreamFormat.AUTO, val timeshift: Boolean = true,
    val stats: Boolean = false, val startView: IptvStartView = IptvStartView.LAST, val sport: Boolean = true,
    val hiddenCategories: Int = 0, val multiview: Boolean = false, val layout: MultiviewLayout = MultiviewLayout.GRID,
    val quality: MultiviewQuality = MultiviewQuality.AUTO, val recordEarly: Int = 1, val recordLate: Int = 2)

@HiltViewModel
class IptvSettingsViewModel @Inject constructor(private val preferences: IptvLivePreferences, private val profiles: ProfileManager,
    private val device: IptvDeviceProfile) : ViewModel() {
    private val mutable = MutableStateFlow(IptvSettingsState())
    val state = mutable.asStateFlow()

    init { reload() }

    private fun reload() {
        viewModelScope.launch {
            val profile = profiles.activeProfileId.value
            mutable.value = withContext(Dispatchers.IO) {
                IptvSettingsState(preferences.defaultFormat, preferences.timeshift, preferences.showStats, preferences.startView, preferences.sport,
                    preferences.hiddenCategoryCount(profile), device.maxTiles >= 2, preferences.multiviewLayout, preferences.multiviewQuality,
                    preferences.recordEarlyMinutes, preferences.recordLateMinutes)
            }
        }
    }

    fun setFormat(value: IptvStreamFormat) { preferences.defaultFormat = value; reload() }
    fun toggleTimeshift() { preferences.timeshift = !preferences.timeshift; reload() }
    fun toggleStats() { preferences.showStats = !preferences.showStats; reload() }
    fun setStartView(value: IptvStartView) { preferences.startView = value; reload() }
    fun toggleSport() { preferences.sport = !preferences.sport; reload() }
    fun setLayout(value: MultiviewLayout) { preferences.multiviewLayout = value; reload() }
    fun setQuality(value: MultiviewQuality) { preferences.multiviewQuality = value; reload() }
    fun setRecordEarly(minutes: Int) { preferences.recordEarlyMinutes = minutes; reload() }
    fun setRecordLate(minutes: Int) { preferences.recordLateMinutes = minutes; reload() }
    fun unhideCategories() {
        val profile = profiles.activeProfileId.value
        viewModelScope.launch { withContext(Dispatchers.IO) { preferences.unhideCategories(profile) }; reload() }
    }
}
