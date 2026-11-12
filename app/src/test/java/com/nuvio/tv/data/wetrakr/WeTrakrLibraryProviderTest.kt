package com.nuvio.tv.data.wetrakr

import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.ListMembershipChanges
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WeTrakrLibraryProviderTest {
    private val severance = WeTrakrTitle(WeTrakrType.SHOW, WeTrakrIds(wetrakr = 900, imdb = "tt11280740"), "Severance")
    private val item = LibraryEntryInput("tt11280740", "series", "Severance", imdbId = "tt11280740")
    private val removeNothing = ListMembershipChanges(weTrakrTabDefinitions.associate { it.key to false })

    @Test
    fun `a show waiting for new episodes is removed, its watched record stays`() = runTest {
        val (provider, harness, repository) = provider(WeTrakrList.WAITING, WeTrakrList.WATCHED)
        harness.engine.on(WeTrakrHttpMethod.POST, "/sync/tracking/remove", ok("""{"deleted":{"shows":1},"not_found":{"shows":[]}}"""))
        provider.applyMembershipChanges(item, removeNothing, destructiveRemovalConfirmed = false)
        val call = harness.engine.calls("/sync/tracking/remove").single()
        val show = Json.parseToJsonElement(call.body!!).jsonObject["shows"]!!.jsonArray.single().jsonObject
        assertEquals("waiting", show["status"]!!.jsonPrimitive.content)
        assertEquals(setOf(WeTrakrList.WATCHED), repository.currentSnapshot()!!.listsOf(item.itemId, item.itemType))
    }

    @Test
    fun `choosing Watching again for a waiting show writes nothing`() = runTest {
        val (provider, harness, repository) = provider(WeTrakrList.WAITING)
        provider.applyMembershipChanges(item, choose("wetrakr:status:watching"), destructiveRemovalConfirmed = false)
        assertTrue(harness.engine.requests.isEmpty())
        assertEquals(setOf(WeTrakrList.WAITING), repository.currentSnapshot()!!.listsOf(item.itemId, item.itemType))
    }

    @Test
    fun `a waiting show moved to Plan to watch leaves Watching`() = runTest {
        val (provider, harness, repository) = provider(WeTrakrList.WAITING)
        harness.engine.on(WeTrakrHttpMethod.POST, "/sync/tracking", ok("""{"added":{"shows":1},"not_found":{"shows":[]}}"""))
        provider.applyMembershipChanges(item, choose("wetrakr:status:planning"), destructiveRemovalConfirmed = false)
        assertEquals(1, harness.engine.calls("/sync/tracking").size)
        assertEquals(setOf(WeTrakrList.PLANNING), repository.currentSnapshot()!!.listsOf(item.itemId, item.itemType))
    }

    @Test
    fun `a removal WeTrakr does not carry out is reported as a failure`() = runTest {
        val (provider, harness, repository) = provider(WeTrakrList.WAITING)
        harness.engine.on(
            WeTrakrHttpMethod.POST, "/sync/tracking/remove",
            ok("""{"deleted":{"shows":0},"not_found":{"shows":[{"ids":{"imdb":"tt11280740"}}]}}""")
        )
        try {
            provider.applyMembershipChanges(item, removeNothing, destructiveRemovalConfirmed = false)
            fail("expected the removal to fail")
        } catch (expected: IllegalStateException) {
        }
        assertEquals(setOf(WeTrakrList.WAITING), repository.currentSnapshot()!!.listsOf(item.itemId, item.itemType))
    }

    private fun choose(key: String) = ListMembershipChanges(weTrakrTabDefinitions.associate { it.key to (it.key == key) })

    private suspend fun TestScope.provider(
        vararg lists: WeTrakrList
    ): Triple<WeTrakrTrackingLibraryProvider, WeTrakrHarness, WeTrakrSyncRepository> {
        val harness = WeTrakrHarness()
        harness.signIn()
        val repository = harness.repository(TestScope(testScheduler))
        repository.ensureLoaded()
        repository.mutate(harness.store.scope()) { snapshot ->
            snapshot.copy(lists = snapshot.lists + lists.associateWith { listOf(WeTrakrListEntry(severance, "2026-11-03T00:00:00.000Z")) }) to Unit
        }
        val tracking = mockk<WeTrakrTrackingProvider> { every { isAuthenticated } returns MutableStateFlow(true) }
        return Triple(WeTrakrTrackingLibraryProvider(repository, harness.api, tracking), harness, repository)
    }
}
