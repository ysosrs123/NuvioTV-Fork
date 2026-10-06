package com.nuvio.tv.ui.screens.player

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioChainProbeHdmiPortTest {

    private data class Device(val name: String, val type: Int)

    private fun pick(vararg devices: Device): List<String> =
        AudioChainProbe.preferHdmiPort(devices.toList()) { it.type }.map { it.name }

    @Test
    fun theRealHdmiPortWinsOverListedArcOutputs() {
        assertEquals(
            listOf("hdmi"),
            pick(
                Device("arc", AudioDeviceInfo.TYPE_HDMI_ARC),
                Device("hdmi", AudioDeviceInfo.TYPE_HDMI),
                Device("earc", AudioDeviceInfo.TYPE_HDMI_EARC),
                Device("speaker", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            )
        )
    }

    @Test
    fun arcAndEarcAreUsedWhenNoHdmiPortIsListed() {
        assertEquals(
            listOf("arc", "earc"),
            pick(
                Device("arc", AudioDeviceInfo.TYPE_HDMI_ARC),
                Device("speaker", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
                Device("earc", AudioDeviceInfo.TYPE_HDMI_EARC)
            )
        )
    }

    @Test
    fun noHdmiOutputsGiveNothing() {
        assertEquals(emptyList<String>(), pick(Device("speaker", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)))
    }
}
