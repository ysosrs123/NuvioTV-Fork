package com.nuvio.tv.core.player.dvfel

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.nuvio.tv.data.local.Dv7HandlingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GazelleDvFelVideoRendererTest {
    @Test fun profile7CodecsBecomeProfile8OnTheDolbyVisionMime() {
        val f = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs("dvhe.07.06").build()
        val p8 = GazelleDvFelVideoRenderer.asProfile8(f)
        assertEquals("dvhe.08.06", p8.codecs)
        assertEquals(MimeTypes.VIDEO_DOLBY_VISION, p8.sampleMimeType)
        assertEquals("dvh1.08.06,hvc1.2.4.L153", GazelleDvFelVideoRenderer.asProfile8(
            Format.Builder().setCodecs("dvh1.07.06,hvc1.2.4.L153").build()).codecs)
        // other profiles and codecs are left alone
        assertEquals("dvhe.08.06", GazelleDvFelVideoRenderer.asProfile8(Format.Builder().setCodecs("dvhe.08.06").build()).codecs)
    }

    @Test fun cube3ModeIsStoredAndCountsAsNativeFel() {
        assertEquals(Dv7HandlingMode.NATIVE_FEL_CUBE3, Dv7HandlingMode.fromStoredString("NATIVE_FEL_CUBE3"))
        assertTrue(Dv7HandlingMode.NATIVE_FEL_CUBE3.isNativeFel)
        assertTrue(Dv7HandlingMode.NATIVE_FEL.isNativeFel)
        assertFalse(Dv7HandlingMode.AUTO.isNativeFel)
        assertEquals(Dv7HandlingMode.NATIVE_FEL_CUBE3, Dv7HandlingMode.entries.last())
    }
}
