package com.nuvio.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
        setContent { NuvioTheme(presentation = presentation) { IptvSourcesScreen(onBack = ::finish) } }
    }
}
