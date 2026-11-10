package com.nuvio.tv.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupFormatTest {
    private val header = BackupHeader(
        createdAtMs = 1_762_600_000_000L,
        appVersion = "1.1.0-nt.13",
        device = BackupDevice("Ugoos", "AM9 Pro", 34),
        profileNames = listOf("Sam", "Kids"),
        parts = setOf(BackupPart.PROFILES, BackupPart.SETTINGS, BackupPart.SECRETS)
    )

    @Test
    fun `every value type survives a round trip`() {
        val values = mapOf(
            "s" to BackupValue.Text("hello \"quoted\" ü"),
            "b" to BackupValue.Flag(true),
            "i" to BackupValue.Whole(42),
            "l" to BackupValue.Big(9_000_000_000L),
            "f" to BackupValue.Real(1.5f),
            "d" to BackupValue.Precise(2.25),
            "set" to BackupValue.Texts(setOf("a", "b"))
        )
        val document = BackupDocument(
            header,
            mapOf(
                BackupPart.SETTINGS to listOf(BackupEntry(BackupStoreKind.PROFILE_STORE, "layout_settings", 2, values)),
                BackupPart.PROFILES to listOf(BackupEntry(BackupStoreKind.PREFS, "profile_local_avatars", null, mapOf("p1" to BackupValue.Text("x"))))
            ),
            SealedBox("PBKDF2WithHmacSHA256", 1000, "c2FsdA==", "aXY=", "ZGF0YQ==")
        )

        val decoded = BackupFormat.decode(BackupFormat.encode(document))

        assertEquals(header, decoded.header)
        assertEquals(document.entries, decoded.entries)
        assertEquals(document.sealedSecrets, decoded.sealedSecrets)
    }

    @Test
    fun `secrets are never written in the clear`() {
        val document = BackupDocument(
            header,
            mapOf(BackupPart.SECRETS to listOf(BackupEntry(BackupStoreKind.PROFILE_STORE, "debrid_settings", 1, mapOf("torbox_api_key" to BackupValue.Text("key-123")))))
        )

        val text = BackupFormat.encode(document)

        assertFalse(text.contains("key-123"))
        assertTrue(BackupFormat.decode(text).entries[BackupPart.SECRETS] == null)
    }

    @Test
    fun `other files and newer versions are refused`() {
        assertThrows(BackupFormatException::class.java) { BackupFormat.decode("{\"collections\":[]}") }
        assertThrows(BackupFormatException::class.java) { BackupFormat.decode("not json") }
        assertThrows(BackupFormatException::class.java) {
            BackupFormat.decode("{\"format\":\"arc-backup\",\"version\":99,\"header\":{}}")
        }
    }

    @Test
    fun `unknown parts, kinds and value types are skipped`() {
        val text = """{"format":"arc-backup","version":1,"header":{"createdAt":5},
            "parts":{"settings":[{"kind":"teleporter","store":"x"},
            {"kind":"prefs","store":"network_dns","values":{"a":{"t":"s","v":"1"},"b":{"t":"blob","v":"2"}}}],
            "future":[{"kind":"prefs","store":"y"}]}}"""

        val decoded = BackupFormat.decode(text)

        assertEquals(1, decoded.entries[BackupPart.SETTINGS]?.size)
        assertEquals(mapOf("a" to BackupValue.Text("1")), decoded.entries[BackupPart.SETTINGS]?.single()?.values)
        assertNull(decoded.sealedSecrets)
    }
}
