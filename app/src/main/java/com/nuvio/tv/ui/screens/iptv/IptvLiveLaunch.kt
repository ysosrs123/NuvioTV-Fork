package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.data.iptv.IptvSourceRef
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow

@Singleton
class IptvLiveLaunch @Inject constructor() {
    val source = MutableStateFlow<IptvSourceRef?>(null)
    val channel = MutableStateFlow<IptvHomeTune?>(null)
}
