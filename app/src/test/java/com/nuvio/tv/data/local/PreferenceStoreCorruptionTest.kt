package com.nuvio.tv.data.local

import android.content.Context
import com.nuvio.tv.core.formatcheck.FormatCheckStore
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceStoreCorruptionTest {

    @Test
    fun aZeroedFile_givesDefaultsInsteadOfACrash() = runBlocking {
        val filesDir = Files.createTempDirectory("store-corruption").toFile()
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.filesDir } returns filesDir
        val folder = File(filesDir, "datastore").apply { mkdirs() }
        val files = listOf(
            "device_ui_preferences_v2",
            "ui_scale_prefs",
            "appearance_v2",
            "format_check_device"
        ).map { name -> File(folder, "$name.preferences_pb").apply { writeBytes(ByteArray(435)) } }

        UiScalePreference.set(context, 100)
        DeviceUiPreferenceStore.update(context) { it }
        V2AppearancePreferenceStore.update(context) { it }
        FormatCheckStore.saveRules(context, emptyMap(), emptyList())

        for (file in files) {
            assertTrue(file.name, file.readBytes().let { it.isEmpty() || it.any { byte -> byte != 0.toByte() } })
        }
        assertEquals(100, UiScalePreference.flow(context).first())
        DeviceUiPreferenceStore.flow(context).first()
        V2AppearancePreferenceStore.flow(context).first()
        assertNull(FormatCheckStore.results(context).first())
    }

    @Test
    fun everyPreferenceStoreSurvivesADamagedFile() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val unprotected = root.walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { file ->
                val text = file.readText()
                Regex("""preferencesDataStore\(""").findAll(text)
                    .filterNot { it.range.first > 0 && text.substring(0, it.range.first).endsWith("import androidx.datastore.preferences.") }
                    .filterNot { "corruptionHandler" in declaration(text, it.range.last) }
                    .map { file.name }
            }
            .toList()
        assertEquals(emptyList<String>(), unprotected)
    }

    private fun declaration(text: String, open: Int): String {
        var depth = 1
        var i = open + 1
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        return text.substring(open, i)
    }
}
