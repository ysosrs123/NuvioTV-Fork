package com.nuvio.tv.core.sync

import com.nuvio.tv.data.remote.supabase.SupabaseProviderCredential
import com.nuvio.tv.domain.model.DebridSettings
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCredentialModelsTest {
    @Test
    fun completeRemoteSnapshotDoesNotRequireSeeding() {
        val snapshot = ProviderCredentialSnapshot(
            profileId = 1,
            values = listOf(
                ProviderCredentialValue("debrid:torbox", "api_key", "local-torbox"),
                ProviderCredentialValue("animeskip", "client_id", "local-anime")
            )
        )
        val rows = listOf(
            SupabaseProviderCredential(
                provider = "DEBRID:TORBOX",
                credentialJson = buildJsonObject { put("api_key", "remote") }
            ),
            SupabaseProviderCredential(
                provider = "animeskip",
                credentialJson = buildJsonObject { put("client_id", "remote") }
            )
        )

        assertFalse(shouldSeedProviderCredentials(snapshot, rows))
    }

    @Test
    fun missingRemoteProviderRequiresSeeding() {
        val snapshot = ProviderCredentialSnapshot(
            profileId = 1,
            values = listOf(
                ProviderCredentialValue("debrid:torbox", "api_key", "local-torbox"),
                ProviderCredentialValue("animeskip", "client_id", "local-anime")
            )
        )
        val rows = listOf(
            SupabaseProviderCredential(
                provider = "debrid:torbox",
                credentialJson = buildJsonObject { put("api_key", "remote") }
            )
        )

        assertTrue(shouldSeedProviderCredentials(snapshot, rows))
    }

    @Test
    fun `remote values replace only supported local providers`() {
        val local = ProviderCredentialSnapshot(
            profileId = 2,
            values = listOf(
                ProviderCredentialValue("debrid:torbox", "api_key", "local-torbox"),
                ProviderCredentialValue("animeskip", "client_id", "local-anime")
            )
        )
        val remote = listOf(
            SupabaseProviderCredential(
                provider = "debrid:torbox",
                credentialJson = buildJsonObject { put("api_key", "remote-torbox") }
            ),
            SupabaseProviderCredential(
                provider = "tmdb",
                credentialJson = buildJsonObject { put("api_key", "remote-tmdb") }
            )
        )

        val merged = local.mergeRemote(remote)

        assertEquals("remote-torbox", merged.values[0].value)
        assertEquals("local-anime", merged.values[1].value)
    }

    @Test
    fun `blank remote value is retained as a clear tombstone`() {
        val local = ProviderCredentialSnapshot(
            profileId = 1,
            values = listOf(ProviderCredentialValue("mdblist", "api_key", "local"))
        )
        val remote = listOf(
            SupabaseProviderCredential(
                provider = "mdblist",
                credentialJson = buildJsonObject { put("api_key", "") }
            )
        )

        assertEquals("", local.mergeRemote(remote).values.single().value)
    }

    @Test
    fun `real-debrid and alldebrid keys never go to the account`() {
        val snapshot = localProviderCredentials(profileId = 1, debrid = allKeys(), mdbListApiKey = "md", animeSkipClientId = "as")

        assertEquals(
            listOf("debrid:torbox", "debrid:premiumize", "mdblist", "animeskip"),
            snapshot.values.map { it.provider }
        )
        assertFalse(snapshot.values.any { it.value == "rd-key" || it.value == "ad-key" })
        assertFalse(snapshot.values.any { it.credentialJson().toString().contains("rd-key") })
        assertFalse(snapshot.values.any { it.credentialJson().toString().contains("ad-key") })
    }

    @Test
    fun `account rows for real-debrid or alldebrid are not applied`() {
        val snapshot = localProviderCredentials(profileId = 1, debrid = allKeys(), mdbListApiKey = "md", animeSkipClientId = "as")
        val remote = listOf(
            SupabaseProviderCredential(
                provider = "debrid:alldebrid",
                credentialJson = buildJsonObject { put("api_key", "remote-ad") }
            ),
            SupabaseProviderCredential(
                provider = "debrid:realdebrid",
                credentialJson = buildJsonObject { put("api_key", "remote-rd") }
            )
        )

        assertEquals(snapshot, snapshot.mergeRemote(remote))
    }

    @Test
    fun `services kept on the device do not ask for a seed`() {
        val snapshot = localProviderCredentials(profileId = 1, debrid = allKeys(), mdbListApiKey = "md", animeSkipClientId = "as")
        val rows = listOf("debrid:torbox", "debrid:premiumize", "mdblist", "animeskip").map { provider ->
            SupabaseProviderCredential(provider = provider, credentialJson = buildJsonObject { put("api_key", "x") })
        }

        assertFalse(shouldSeedProviderCredentials(snapshot, rows))
    }

    private fun allKeys() = DebridSettings(
        torboxApiKey = "tb-key",
        premiumizeApiKey = "pm-key",
        realDebridApiKey = "rd-key",
        allDebridApiKey = "ad-key"
    )
}
