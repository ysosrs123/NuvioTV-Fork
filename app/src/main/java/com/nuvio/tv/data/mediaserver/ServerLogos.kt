package com.nuvio.tv.data.mediaserver

import androidx.annotation.RawRes
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R

@RawRes
fun serverLogoRes(providerId: String): Int? = when (providerId) {
    "jellyfin" -> R.raw.jellyfin_logo
    "emby" -> R.raw.emby_logo
    "silo" -> R.raw.silo_logo
    else -> null
}

fun serverLogoUri(providerId: String, packageName: String = BuildConfig.APPLICATION_ID): String? =
    serverLogoRes(providerId)?.let { "android.resource://$packageName/$it" }
