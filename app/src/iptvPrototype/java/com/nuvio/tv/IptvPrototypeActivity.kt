package com.nuvio.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.nuvio.tv.ui.screens.iptv.IptvLiveScreen
import com.nuvio.tv.ui.screens.iptv.IptvSourcesScreen
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.AndroidEntryPoint
import com.nuvio.tv.domain.model.DeviceUiPreferences
import com.nuvio.tv.domain.model.V2AppearancePreferences
import com.nuvio.tv.ui.v2.appearance.ResolvedAppearance
import com.nuvio.tv.ui.v2.scale.UiScaleDecision

/** Isolated QA entry point; this activity and package exist only in the prototype flavor. */
@AndroidEntryPoint
class IptvPrototypeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val presentation = if (intent.getStringExtra("appearance") == "v2")
            ResolvedAppearance(DeviceUiPreferences(), V2AppearancePreferences(), UiScaleDecision(100, "IPTV fixture")) else null
        setContent {
            var live by remember { mutableStateOf(intent.getBooleanExtra("live", false)) }
            NuvioTheme(presentation = presentation?.copy(playbackActive = live)) {
                if (live) IptvLiveScreen(onBack = { live = false }, onSources = { live = false })
                else IptvSourcesScreen(onBack = ::finish, onLive = { live = true })
            }
        }
    }
}
