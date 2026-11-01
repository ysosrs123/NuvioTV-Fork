package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerlessBoxRouteTest {
    private val speaker = "type:built_in_speaker|name:aftkrt"
    private val hdmi = "type:hdmi|name:aftkrt"

    @Test
    fun fireStickOnItsSpeakerRoute_hasNoHdmiSound() {
        assertTrue(SpeakerlessBoxRoute.hdmiSoundOff("Amazon", "4", speaker))
        assertTrue(SpeakerlessBoxRoute.hdmiSoundOff("amazon", "4", speaker))
    }

    @Test
    fun fireStickOnHdmi_isFine() {
        assertFalse(SpeakerlessBoxRoute.hdmiSoundOff("Amazon", "4", hdmi))
        assertFalse(SpeakerlessBoxRoute.hdmiSoundOff("Amazon", "4", null))
    }

    @Test
    fun fireTvSetsHaveRealSpeakers() {
        assertFalse(SpeakerlessBoxRoute.hdmiSoundOff("Amazon", "0", speaker))
        assertFalse(SpeakerlessBoxRoute.hdmiSoundOff("Amazon", "0,4", speaker))
    }

    @Test
    fun unknownDeviceTypeOrOtherMakers_areLeftAlone() {
        assertFalse(SpeakerlessBoxRoute.hdmiSoundOff("Amazon", null, speaker))
        assertFalse(SpeakerlessBoxRoute.hdmiSoundOff("Ugoos", "4", speaker))
        assertFalse(SpeakerlessBoxRoute.hdmiSoundOff(null, "4", speaker))
    }
}
