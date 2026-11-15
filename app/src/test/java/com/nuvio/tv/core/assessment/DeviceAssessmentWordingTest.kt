package com.nuvio.tv.core.assessment

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAssessmentWordingTest {
    private val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    private fun strings(dir: File): Map<String, String> {
        val file = File(dir, "strings.xml")
        if (!file.isFile) return emptyMap()
        return Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private val english = strings(File(res, "values"))

    @Test
    fun `suggestions read as suggestions in English`() {
        assertEquals("Suggested: %1\$s", english["assessment_suggested"])
        assertEquals("On \\u00b7 Auto, up to \\u2248%1\$s", english["assessment_value_on_auto"])
        assertEquals("Automatic (follows Automatic speed-up)", english["assessment_value_connections_auto"])
        assertFalse(english.getValue("assessment_grounds_vod_auto").contains("right now"))
    }

    @Test
    fun `every language with the assessment has the new wording`() {
        val keys = listOf(
            "assessment_suggested",
            "assessment_value_connections_auto",
            "assessment_grounds_connections_auto",
            "assessment_grounds_connections_auto_one",
            "assessment_grounds_connections_auto_untested",
            "assessment_grounds_strip_sdr"
        )
        val languages = res.listFiles().orEmpty().filter { it.name.startsWith("values-") }
        var checked = 0
        for (dir in languages) {
            val local = strings(dir)
            if ("assessment_currently" !in local) continue
            checked++
            for (key in keys) assertTrue("${dir.name} $key", key in local)
            assertEquals(dir.name, 1, local.getValue("assessment_suggested").split("%1\$s").size - 1)
            val grounds = local.getValue("assessment_grounds_connections_auto")
            for (ph in listOf("%1\$s", "%2\$s", "%3\$d", "%4\$d")) assertTrue("${dir.name} $ph", grounds.contains(ph))
        }
        assertTrue(checked >= 30)
    }
}
