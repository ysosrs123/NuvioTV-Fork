package com.nuvio.tv.core.backup

import com.nuvio.tv.data.local.signedOutMemberAccess
import com.nuvio.tv.data.local.supporterMarkerAccess
import com.nuvio.tv.domain.model.CosmeticEntitlement
import com.nuvio.tv.domain.model.MemberAccess
import com.nuvio.tv.domain.model.MemberTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSupporterTest {

    private fun marker(tier: String?) = BackupEntry(
        BackupStoreKind.PREFS,
        BackupSupporter.STORE,
        values = listOfNotNull(tier?.let { "tier" to BackupValue.Text(it) }, "confirmed_ms" to BackupValue.Big(5L)).toMap()
    )

    private val key = BackupEntry(BackupStoreKind.PREFS, "real_debrid_tokens", values = mapOf("token" to BackupValue.Text("t")))
    private val look = BackupEntry(BackupStoreKind.PREFS, "app_icon", values = mapOf("icon" to BackupValue.Text("arc")))

    @Test
    fun `the marker is filed with the passwords in the catalogue`() {
        val spec = requireNotNull(BackupCatalog.spec(BackupStoreKind.PREFS, BackupSupporter.STORE))
        assertEquals(BackupPart.SECRETS, BackupCatalog.partFor(spec, "tier"))
        assertEquals(BackupPart.SECRETS, BackupCatalog.partFor(spec, "confirmed_ms"))
    }

    @Test
    fun `with a PIN the marker stays sealed`() {
        val entries = mapOf(BackupPart.SECRETS to listOf(key, marker("SUPPORTER")), BackupPart.SETTINGS to listOf(look))
        assertEquals(entries, BackupSupporter.placed(entries, sealed = true))
    }

    @Test
    fun `without a PIN the marker goes with the settings and the keys stay behind`() {
        val entries = mapOf(BackupPart.SECRETS to listOf(key, marker("SUPPORTER")), BackupPart.SETTINGS to listOf(look))
        val placed = BackupSupporter.placed(entries, sealed = false)

        assertEquals(listOf(key), placed[BackupPart.SECRETS])
        assertEquals(listOf(look, marker("SUPPORTER")), placed[BackupPart.SETTINGS])
    }

    @Test
    fun `a backup says whether it carries supporter status`() {
        assertTrue(BackupSupporter.isSupporter(mapOf(BackupPart.SECRETS to listOf(marker("SUPPORTER_PLUS")))))
        assertFalse(BackupSupporter.isSupporter(mapOf(BackupPart.SECRETS to listOf(marker(null), key))))
        assertFalse(BackupSupporter.isSupporter(emptyMap()))
    }

    @Test
    fun `a signed-out box shows supporter looks only from a marker`() {
        assertEquals(MemberAccess.None, signedOutMemberAccess(null))
        val access = requireNotNull(supporterMarkerAccess("SUPPORTER", setOf(CosmeticEntitlement.entries.first().name, "GONE")))
        assertEquals(MemberTier.SUPPORTER, access.tier)
        assertEquals(setOf(CosmeticEntitlement.entries.first()), access.entitlements.unlocked)
        assertEquals(access, signedOutMemberAccess(access))
        assertNull(supporterMarkerAccess(null, emptySet()))
        assertNull(supporterMarkerAccess("OLD_TIER", emptySet()))
    }
}
