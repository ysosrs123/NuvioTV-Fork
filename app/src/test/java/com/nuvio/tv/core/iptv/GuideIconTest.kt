package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class GuideIconTest {
    private fun parse(body: String): GuideProgramme {
        val programmes = mutableListOf<GuideProgramme>()
        XmlTvGuideParser().parse("""<tv><programme channel="one" start="20261005070000 +0000" stop="20261005080000 +0000"><title>Show</title>$body</programme></tv>""".byteInputStream(), {}, programmes::add)
        return programmes.single()
    }

    @Test fun firstWebIconIsKept() {
        assertEquals("https://img.example/a.jpg", parse("""<icon src=" https://img.example/a.jpg " width="100"/><icon src="https://img.example/b.jpg"/>""").icon)
        assertEquals("http://img.example/b.png", parse("""<icon src="file:///data/x.png"/><icon src="http://img.example/b.png"/>""").icon)
    }

    @Test fun missingOrUnsafeIconsAreDropped() {
        assertNull(parse("").icon)
        assertNull(parse("""<icon/>""").icon)
        assertNull(parse("""<icon src="javascript:alert(1)"/>""").icon)
        assertNull(parse("""<icon src="https://img.example/a b.jpg"/>""").icon)
        assertNull(parse("""<icon src="https://img.example/${"a".repeat(2048)}"/>""").icon)
    }

    @Test fun channelIconsDoNotLeakIntoProgrammes() {
        val programmes = mutableListOf<GuideProgramme>()
        XmlTvGuideParser().parse("""<tv><channel id="one"><display-name>One</display-name><icon src="https://img.example/logo.png"/></channel><programme channel="one" start="20261005070000 +0000"><title>Show</title></programme></tv>""".byteInputStream(), {}, programmes::add)
        assertNull(programmes.single().icon)
    }

    @Test fun iconUrlLimits() {
        assertEquals("https://a.example/" + "x".repeat(2048 - 18), guideIconUrl("https://a.example/" + "x".repeat(2048 - 18)))
        assertNull(guideIconUrl("https://a.example/" + "x".repeat(2048 - 17)))
        assertNull(guideIconUrl("ftp://a.example/x"))
        assertNull(guideIconUrl(null))
    }
}
