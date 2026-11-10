package com.nuvio.tv.core.scout

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ScoutProfileFilesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val engine = mockk<ScoutEngine>(relaxed = true)

    private fun files(): ScoutProfileFiles {
        val context = mockk<Context> { every { filesDir } returns folder.root }
        return ScoutProfileFiles(context, dagger.Lazy { engine })
    }

    private fun seed(): File {
        val scout = File(folder.root, "scout").apply { mkdirs() }
        listOf(2, 3).forEach { id -> listOf("pool", "ai", "taste", "shown", "mood_ai").forEach { File(scout, "${it}_$id.json").writeText("{}") } }
        File(scout, "facts.json").writeText("[]")
        return scout
    }

    private fun left(scout: File) = scout.list().orEmpty().sorted()

    @Test
    fun `deleting a profile removes only its Scout files`() {
        val scout = seed()
        files().removeProfile(2)
        assertEquals(listOf("ai_3.json", "facts.json", "mood_ai_3.json", "pool_3.json", "shown_3.json", "taste_3.json"), left(scout))
        verify { engine.forget(2) }
    }

    @Test
    fun `signing out removes every Scout file`() {
        val scout = seed()
        File(scout, "opened.txt").writeText("movie:1\n")
        files().clearAllProfiles()
        assertFalse(scout.exists())
        verify { engine.forget(null) }
    }

    @Test
    fun `a profile with no Scout files is fine`() {
        files().removeProfile(4)
        verify { engine.forget(4) }
    }
}
