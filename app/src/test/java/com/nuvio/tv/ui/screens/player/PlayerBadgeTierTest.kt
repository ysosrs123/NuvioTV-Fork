package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

class PlayerBadgeTierTest {
    // The web-1, web-3 and a-dv filters of kingsizew colored_outline_badges.json, verbatim.
    private val web1 = "(?i)(?:(?<![A-Za-z0-9])(?:(?:radarr|sonarr)\\s+web\\s+t1|web\\s+t1|anime\\s+web\\s+t1)(?![A-Za-z0-9])|(?<![\\s\\S])(?=[\\s\\S]*?(?:(?<![A-Za-z0-9])(?:web(?:[-_. ]?dl(?:mux)?|[-_. ]?rip|mux)?|webdl|webrip|webmux|amazonhd|amazonsd|ituneshd|maxdomehd|netflixu?hd|hbomaxhd|disneyhd|amzn|dsnp|hmax|hulu|nf)(?![A-Za-z0-9])))(?![\\s\\S]*?(?:(?<![A-Za-z0-9])(?:(?:(?:bd|uhd)[-_. ]?)?remux|bdremux|uhdremux)(?![A-Za-z0-9])|(?<![A-Za-z0-9])(?:blu[-_. ]?ray|bluray|b[dr]rip|bdmux|bd(?:720|1080|2160)p?|hd[-_. ]?dvd|hddvd|dvdrip|ntsc|pal|xvidvd)(?![A-Za-z0-9])))(?=[\\s\\S]*?(?:(?:(?:^|[\\s._\\-\\[(])(?:ZoroSenpai|BLUTONiUM|CasStudio|LostYears|ZeroBuild|LYS1TH3A|Setsugen|Kitsune|McBalls|NOSiViD|TheFarm|CtrlHD|MADSKY|monkee|Z4ST1N|ABBIE|AJP69|BYNDR|Cloud|GNOME|KiNGS|TEPES|TOMMY|ViSUM|Vodes|APEX|Arg0|Arid|Baws|CMRG|CRFW|CRUD|FLUX|HONE|PAXA|PEXA|RAWR|smol|XEPA|FLE|NTb|NTG|QOQ|RTN|sam|SCY|SiC|SoM|T6D)(?=\$|[\\s._\\-\\])]|\\.(?:mkv|mp4|avi|m2ts|ts|wmv|mov|m4v|mpg|mpeg|webm|iso)(?:\$|[?#\\s]))))))"
    private val web3 = "(?i)(?:(?<![A-Za-z0-9])(?:(?:radarr|sonarr)\\s+web\\s+t3|web\\s+t3|anime\\s+web\\s+t3)(?![A-Za-z0-9])|(?<![\\s\\S])(?=[\\s\\S]*?(?:(?<![A-Za-z0-9])(?:web(?:[-_. ]?dl(?:mux)?|[-_. ]?rip|mux)?|webdl|webrip|webmux|amazonhd|amazonsd|ituneshd|maxdomehd|netflixu?hd|hbomaxhd|disneyhd|amzn|dsnp|hmax|hulu|nf)(?![A-Za-z0-9])))(?![\\s\\S]*?(?:(?<![A-Za-z0-9])(?:(?:(?:bd|uhd)[-_. ]?)?remux|bdremux|uhdremux)(?![A-Za-z0-9])|(?<![A-Za-z0-9])(?:blu[-_. ]?ray|bluray|b[dr]rip|bdmux|bd(?:720|1080|2160)p?|hd[-_. ]?dvd|hddvd|dvdrip|ntsc|pal|xvidvd)(?![A-Za-z0-9])))(?=[\\s\\S]*?(?:(?:(?:^|[\\s._\\-\\[(])(?:NINJACENTRAL|GNOMiSSiON|SwAgLaNdEr|SLiGNOME|DRACULA|ViSiON|AnoZu|BLOOM|Dooky|HHWEB|T4H|ZR)(?=\$|[\\s._\\-\\])]|\\.(?:mkv|mp4|avi|m2ts|ts|wmv|mov|m4v|mpg|mpeg|webm|iso)(?:\$|[?#\\s]))))))"
    private val dolbyVision = "(?is)^(?=.*?(?:(?:(?<![a-z0-9])(?:dv|dovi|dolby[-_. ]?vision)(?![a-z0-9]))))(?:(?=.*\\.(?:mkv|mp4|avi|m2ts|webm|mov|m4v)\\s+\\S)|(?=(?:.*(?:2160|1440|1080|720|576|480)[pi]?){2})|(?=.*[\\r\\n])|(?=^[a-f0-9]{40,64}\\s+\\S))(?!.*(?:(?:(?:(?<![a-z0-9])(?:hdr[-_. ]?10(?:[-_. ]?(?:\\+|p)|[-_. ]?plus))(?![a-z0-9]))|(?:(?<![a-z0-9])(?:hdr[-_. ]?10(?![-_. ]?(?:\\+|p(?:lus)?|bit|hi)))(?![a-z0-9]))|(?:(?<![a-z0-9])(?:hdr(?![-_. ]?(?:10(?![-_. ]?bit)|\\+|p(?:lus)?)))(?![a-z0-9])))))"

    private val file = "Verity.2026.REPACK.2160p.AMZN.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-BYNDR.mkv"
    private val info = PlayerMediaInfo(videoCodec = "dolby-vision", videoWidth = 3840, videoHeight = 1600, audioCodec = "E-AC-3", audioChannels = 6, hdr = listOf("DV"), hdrKnown = true, bitDepth = 10)

    @Test
    fun `a BYNDR web release reads as web tier 1 only and keeps dolby vision`() {
        val text = playerBadgeMatchText(info, emptyList(), file, file, audioFromRelease = true)
        assertTrue("web-1 finds in: $text", Pattern.compile(web1).matcher(text).find())
        assertFalse("web-3 does not in: $text", Pattern.compile(web3).matcher(text).find())
        assertTrue("a-dv finds in: $text", Pattern.compile(dolbyVision).matcher(text).find())
    }
}
