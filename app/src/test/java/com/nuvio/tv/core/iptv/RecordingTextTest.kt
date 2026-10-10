package com.nuvio.tv.core.iptv

import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class RecordingTextTest {
    private val now = 1_791_331_200_000L
    private val zone = ZoneId.of("Australia/Sydney")

    @Test fun displayNamesLoseDecorativeCharacters() {
        listOf(
            "ꜱᴋʏ ꜱᴘᴏʀᴛꜱ ᴍᴀɪɴ ᴇᴠᴇɴᴛ ᴴᴰ" to "sky sports main event HD",
            "UK | Sky Sports Premier League ★ FHD" to "UK Sky Sports Premier League FHD",
            "|EN| beIN SPORTS 1 ⚽" to "EN beIN SPORTS 1",
            "𝐒𝐤𝐲 𝐒𝐩𝐨𝐫𝐭𝐬 𝐅𝟏" to "Sky Sports F1",
            "𝕆𝕡𝕥𝕦𝕤 𝕊𝕡𝕠𝕣𝕥 ①" to "Optus Sport 1",
            "𝓕𝓸𝔁 𝓛𝓮𝓪𝓰𝓾𝓮" to "Fox League",
            "Ⓢⓣⓐⓝ Ⓢⓟⓞⓡⓣ" to "Stan Sport",
            "🅢🅚🅨 🅢🅟🅞🅡🅣🅢" to "SKY SPORTS",
            "🄱🄱🄲 🄾🄽🄴" to "BBC ONE",
            "🅱🅱🅲 🅽🅴🆆🆂" to "BBC NEWS",
            "Ｆｏｘ Ｆｏｏｔｙ" to "Fox Footy",
            "Kayo ᴸᶦᵛᵉ ✦ AFL" to "Kayo Live AFL",
            "Sky Sportsᴴᴰ" to "Sky Sports HD",
            "🇬🇧 TNT Sports 1 🇬🇧" to "TNT Sports 1",
            "● LIVE ▶ Arsenal v Leeds United" to "LIVE Arsenal v Leeds United",
            "S̶k̶y̶ N̲e̲w̲s̲" to "Sky News",
            "Atlético Madrid vs Real Sociedad" to "Atlético Madrid vs Real Sociedad",
            "Premier League — Arsenal v Leeds" to "Premier League - Arsenal v Leeds",
            "ʟɪᴠᴇ: ɴʀʟ ꜰɪɴᴀʟꜱ" to "live: nrl finals",
            "❤️ Love Island 🏝️" to "Love Island",
            "Super Rugby • Highlanders ‘v’ Chiefs" to "Super Rugby Highlanders 'v' Chiefs",
            "हिन्दी समाचार" to "हिन्दी समाचार",
            "天気予報々" to "天気予報々",
            "👨‍👩‍👧 Family 🔴" to "Family",
        ).forEach { (raw, plain) -> assertEquals(raw, plain, RecordingText.display(raw)) }
    }

    @Test fun fileNamesUseTheSameCleaning() {
        assertEquals("live nrl finals - Fox League - 07-Oct-26 1100.ts",
            RecordingFiles.name("ʟɪᴠᴇ: ɴʀʟ ꜰɪɴᴀʟꜱ", "𝓕𝓸𝔁 𝓛𝓮𝓪𝓰𝓾𝓮", now, zone))
        assertEquals("UK Sky Sports Main Event HD - LIVE Arsenal v Leeds United - 07-Oct-26 1100.ts",
            RecordingFiles.name("UK | Sky Sports Main Event ᴴᴰ ★", "● LIVE ▶ 🇬🇧 Arsenal v Leeds United ⚽", now, zone))
        assertEquals("BBC ONE - 07-Oct-26 1100.ts", RecordingFiles.name("🄱🄱🄲 🄾🄽🄴 ✦✦✦", "🔴🔴", now, zone))
        val long = RecordingFiles.name("𝐒".repeat(80), "😀".repeat(40) + "𝔸".repeat(200), now, zone)
        assertTrue(long, long.length <= RecordingFiles.MAX_NAME_CHARS && long.all { !it.isSurrogate() })
        val cjk = RecordingFiles.name("𠀋".repeat(70), null, now, zone)
        assertTrue(cjk.toByteArray(Charsets.UTF_8).size <= RecordingFiles.MAX_NAME_BYTES)
        assertFalse(cjk.toCharArray().let { chars -> chars.indices.any { chars[it].isHighSurrogate() && (it + 1 >= chars.size || !chars[it + 1].isLowSurrogate()) } })
    }
}
