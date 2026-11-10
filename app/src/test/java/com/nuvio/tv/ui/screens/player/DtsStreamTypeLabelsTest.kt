package com.nuvio.tv.ui.screens.player

import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import com.nuvio.tv.core.player.dvmkv.DtsStreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DtsStreamTypeLabelsTest {

    private fun dtsHd(type: DtsStreamType?, label: String? = null) = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_DTS_HD)
        .setLabel(label)
        .setMetadata(type?.let { Metadata(it) })
        .build()

    @Test
    fun `frames decide the profile`() {
        assertEquals(listOf("DTS-HD MA"), exoAudioProfiles(dtsHd(DtsStreamType.MASTER_AUDIO)))
        assertEquals(listOf("DTS:X", "DTS-HD MA"), exoAudioProfiles(dtsHd(DtsStreamType.DTS_X)))
        assertEquals(listOf("DTS-HD"), exoAudioProfiles(dtsHd(DtsStreamType.HIGH_RESOLUTION)))
    }

    @Test
    fun `frames beat a track name that says MA`() {
        assertEquals(listOf("DTS-HD"), exoAudioProfiles(dtsHd(DtsStreamType.HIGH_RESOLUTION, "DTS-HD MA 7.1")))
    }

    @Test
    fun `without an answer from the frames the name still counts`() {
        assertEquals(listOf("DTS-HD MA"), exoAudioProfiles(dtsHd(null, "DTS-HD MA 5.1")))
        assertEquals(emptyList<String>(), exoAudioProfiles(dtsHd(null)))
    }

    @Test
    fun `the audio list shows the exact name`() {
        val track = TrackInfo(index = 0, name = "x", language = "en", codec = "DTS-HD", codecDetail = "DTS:X", channelCount = 8)
        val subtitle = audioTrackRowText(track, "English", "Audio 1", "Commentary", "Audio description").subtitle.orEmpty()
        assertTrue(subtitle, subtitle.contains("DTS:X"))
        assertFalse(subtitle, subtitle.contains("DTS-HD"))
    }

    @Test
    fun `track choice keeps comparing the plain codec`() {
        assertEquals(
            LosslessAudioTrackDefault.TIER_DTS_HD_MA,
            LosslessAudioTrackDefault.losslessTier(
                TrackInfo(index = 0, name = "x", language = "en", codec = "DTS-HD", codecDetail = "DTS:X").codec,
                "x"
            )
        )
    }
}
