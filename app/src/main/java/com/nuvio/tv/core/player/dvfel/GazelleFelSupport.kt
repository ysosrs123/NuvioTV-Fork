package com.nuvio.tv.core.player.dvfel

import com.nuvio.tv.core.player.SysfsFiles

/**
 * Capability checks for the Fire TV Cube 3 (Gazelle) FEL path ([GazelleDvFelVideoRenderer]).
 *
 * Needs a rooted Cube with signde's Fire TV AV module, which loads the dvfel kernel helper, opens
 * /dev/dvfel to apps and sets the debug.dvfp.* player properties. The app never installs anything.
 */
internal object GazelleFelSupport {
    private const val MODULE_VERSION = "/sys/module/dvfel/version"

    fun isGazelleBoard(): Boolean = systemProperty("ro.board.platform") == "gazelle"

    @Volatile private var cachedModuleVersion: String? = null

    /** The loaded dvfel helper's version, or null (cached once read: the helper is not reloaded at run time). */
    fun moduleVersion(): String? = cachedModuleVersion
        ?: SysfsFiles.read(MODULE_VERSION, max = 128)?.takeIf { it.isNotEmpty() }?.also { cachedModuleVersion = it }

    /** Fire TV AV sets these last; unset = the module did not finish configuring (tested seek settings missing). */
    fun isModuleConfigured(): Boolean = systemProperty("debug.dvfp.hold_images") == "1"

    /** The Gazelle FEL path can run on this box right now. */
    fun isUsable(): Boolean =
        isGazelleBoard() && isModuleConfigured() && DvFelPlay.isLoaded && DvFelPlay.available()

    private fun systemProperty(name: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, name) as? String
    }.getOrNull()
}
