package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins resolveStreamProvider against six real add-on labels and the two
 * collision traps that make bare-substring matching unsafe.
 */
class StreamProviderLabelTest {

    private fun provider(
        name: String?,
        desc: String? = null,
        addon: String? = null,
        host: String? = null
    ) = resolveStreamProvider(name, desc, addon, host)

    // --- Real add-on labels ------------------------------------------------------

    @Test
    fun stremthruTorz_resolvesTorBox() = assertEquals(
        "TorBox",
        provider("\u26a1 [TB]", "Torz\n4320p\nObsession (2025) 4320p HDR Ai Upscale -Mesc.mkv")
    )

    @Test
    fun comet_instant_resolvesTorBox() = assertEquals(
        "TorBox",
        provider("4K TB Instant", "BluRay REMUX HDR+DV | DV | HDR HEVC\nNot Ready (TB)")
    )

    @Test
    fun debridio_resolvesTorBox() = assertEquals(
        "TorBox",
        provider("[TB \u26a1]", "Debridio 8k HDR")
    )

    @Test
    fun torrentio_resolvesAllDebrid_notDebridLinkFromWebDl() = assertEquals(
        "AllDebrid",
        provider(
            "[AD+] Torrentio",
            "4k DV | HDR10+\nBackrooms.2026.2160p.iT.WEB-DL.DV.HDR10+.DDP5.1.Atmos.H265.MP4-BTM"
        )
    )

    @Test
    fun nas_resolvesEmbyLibrary() = assertEquals(
        "Emby",
        provider("Emby", "2160p \u00b7 HEVC \u00b7 DV P7.6 \u00b7 TRUEHD 7.1", addon = "NAS")
    )

    @Test
    fun theTavern_fallsToHost() = assertEquals(
        "emby.example.com",
        provider(
            "The Tavern",
            "4K \u00b7 REMUX \u00b7 TrueHD 7.1 \u00b7 MKV",
            addon = "The Tavern",
            host = "emby.example.com"
        )
    )

    // --- Collision guards --------------------------------------------------------

    @Test
    fun webDl_doesNotMatchDebridLink() = assertEquals(
        "host.example",
        provider("Some WEB-DL release", "1080p WEB-DL", host = "host.example")
    )

    @Test
    fun hdr_doesNotMatchDebrider() = assertEquals(
        "host.example",
        provider("Movie HDR HEVC", "2160p HDR10", host = "host.example")
    )

    // --- Fallback ----------------------------------------------------------------

    @Test
    fun nothingKnown_returnsNull() = assertNull(provider(null, null, null, null))
}
