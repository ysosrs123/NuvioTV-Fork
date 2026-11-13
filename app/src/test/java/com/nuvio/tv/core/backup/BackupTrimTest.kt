package com.nuvio.tv.core.backup

import com.nuvio.tv.data.local.isKeptDataOfAnotherAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTrimTest {

    private fun backup(name: String, modifiedMs: Long) = SavedBackup(name, name, modifiedMs, 1L)

    @Test
    fun `each kind keeps only its newest files`() {
        val manual = (1..4).map { backup("Arc backup 2026-11-0$it 1200.json", it.toLong()) }
        val safety = (1..3).map { backup("Arc safety copy 2026-11-0$it 1300.json", 100L + it) }

        val trimmed = backupsToTrim(manual + safety).map { it.displayName }.toSet()

        assertEquals(
            setOf(
                "Arc backup 2026-11-01 1200.json",
                "Arc backup 2026-11-02 1200.json",
                "Arc safety copy 2026-11-01 1300.json"
            ),
            trimmed
        )
    }

    @Test
    fun `a newer safety copy never pushes out a backup made by hand`() {
        val manual = (1..2).map { backup("Arc backup 2026-11-0$it 1200.json", it.toLong()) }
        val safety = backup("Arc safety copy 2026-11-09 1300.json", 900L)

        assertTrue(backupsToTrim(manual + safety).isEmpty())
    }

    @Test
    fun `file names tell the kinds apart`() {
        assertEquals(BackupKind.MANUAL, BackupKind.of("Arc backup 2026-11-09 2321.json"))
        assertEquals(BackupKind.MANUAL, BackupKind.of("Arc backup 2026-11-09 2321 (1).json"))
        assertEquals(BackupKind.SAFETY, BackupKind.of("Arc safety copy 2026-11-09 2321.json"))
        assertEquals(null, BackupKind.of("notes.json"))
    }

    @Test
    fun `kept data is cleared only for a different account`() {
        assertFalse(isKeptDataOfAnotherAccount(null, "a"))
        assertFalse(isKeptDataOfAnotherAccount("a", "a"))
        assertTrue(isKeptDataOfAnotherAccount("a", "b"))
    }
}
