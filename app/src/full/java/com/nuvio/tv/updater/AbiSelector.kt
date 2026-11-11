package com.nuvio.tv.updater

import android.os.Build
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.dto.GitHubAssetDto

internal object AbiSelector {
    /**
     * The device's ABIs with the kind this install runs as first, so an update keeps the APK type that is
     * installed (a 32-bit install on a 64-bit box stays 32-bit).
     */
    fun installedFirst(
        deviceAbis: List<String>,
        is64Bit: Boolean = android.os.Process.is64Bit()
    ): List<String> {
        val (same, other) = deviceAbis.partition { it.contains("64") == is64Bit }
        return same + other
    }

    fun chooseBestApkAsset(
        assets: List<GitHubAssetDto>,
        supportedAbis: List<String> = Build.SUPPORTED_ABIS?.toList().orEmpty(),
        // "app-full", or "app-full-fel" in the Fire TV Cube 3 FEL APK, so an update keeps libdvfelplay.
        apkPrefix: String = BuildConfig.UPDATE_APK_PREFIX
    ): GitHubAssetDto? {
        // Only release/full artifacts from our workflow. Never guess an ABI or flavor.
        for (abi in supportedAbis) {
            assets.firstOrNull { it.name == "$apkPrefix-$abi-release.apk" }?.let { return it }
        }
        return assets.firstOrNull { it.name == "$apkPrefix-universal-release.apk" }
    }
}
