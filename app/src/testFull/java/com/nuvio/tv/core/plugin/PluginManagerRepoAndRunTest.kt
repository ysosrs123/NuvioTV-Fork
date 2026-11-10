package com.nuvio.tv.core.plugin

import android.util.Log
import com.nuvio.tv.core.plugin.cloudstream.ExternalExtensionLoader
import com.nuvio.tv.core.plugin.cloudstream.ExternalExtensionRunner
import com.nuvio.tv.core.plugin.cloudstream.ExternalRepoParseResult
import com.nuvio.tv.core.plugin.cloudstream.ExternalRepoParser
import com.nuvio.tv.data.local.PluginDataStore
import com.nuvio.tv.domain.model.ExternalPluginEntry
import com.nuvio.tv.domain.model.NewRepoProviders
import com.nuvio.tv.domain.model.PluginRepository
import com.nuvio.tv.domain.model.RemotePluginInfo
import com.nuvio.tv.domain.model.RepositoryType
import com.nuvio.tv.domain.model.ScraperInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PluginManagerRepoAndRunTest {

    private val saved = MutableStateFlow(emptyList<ScraperInfo>())
    private val repos = MutableStateFlow(emptyList<PluginRepository>())
    private val runtime = mockk<PluginRuntime>(relaxed = true)
    private val parser = mockk<ExternalRepoParser>(relaxed = true)
    private val loader = mockk<ExternalExtensionLoader>(relaxed = true) {
        coEvery { downloadExtension(any(), any()) } returns File("unused")
    }
    private val dataStore = mockk<PluginDataStore>(relaxed = true) {
        every { pluginsEnabled } returns flowOf(true)
        every { repositories } returns repos
        every { scrapers } returns saved
        coEvery { saveScrapers(any()) } answers { saved.value = firstArg() }
        coEvery { getScraperCode(any()) } returns "code"
        coEvery { getScraperSettings(any()) } returns emptyMap()
        coEvery { pluginProviderPlayStats() } returns emptyMap()
    }

    private fun manager() = PluginManager(
        dataStore,
        runtime,
        mockk(relaxed = true),
        mockk(relaxed = true) { every { isAuthenticated } returns false },
        parser,
        loader,
        mockk<ExternalExtensionRunner>(relaxed = true)
    )

    private fun entry(name: String) = ExternalPluginEntry(
        name = name,
        internalName = name,
        url = "https://example.invalid/$name.cs3",
        version = 1,
        status = 1,
        tvTypes = listOf("Movie")
    )

    private fun repoOf(vararg names: String) {
        coEvery { parser.tryParse("https://example.org/repo.json") } returns ExternalRepoParseResult(
            name = "Repo",
            description = null,
            plugins = names.map(::entry)
        )
    }

    private fun jsScraper(id: String) = ScraperInfo(
        id = id, name = id, description = "", version = "1", filename = "$id.js",
        supportedTypes = listOf("movie"), enabled = true, manifestEnabled = true, logo = null,
        contentLanguage = emptyList(), repositoryId = "r", formats = null, type = RepositoryType.NUVIO_JS
    )

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        mockkStatic(android.os.Process::class)
        every { android.os.Process.setThreadPriority(any()) } just Runs
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() = unmockkStatic(Log::class, android.os.Process::class)

    @Test
    fun `a repo added to choose from is saved with every provider off`() = runBlocking {
        repoOf("one", "two", "three")
        manager().addRepository("https://example.org/repo.json", NewRepoProviders.OFF_IF_SEVERAL)
        assertEquals(3, saved.value.size)
        assertTrue(saved.value.none { it.enabled })
    }

    @Test
    fun `a single provider repo keeps its normal default`() = runBlocking {
        repoOf("only")
        manager().addRepository("https://example.org/repo.json", NewRepoProviders.OFF_IF_SEVERAL)
        assertTrue(saved.value.single().enabled)
    }

    @Test
    fun `a repo added from a phone arrives off even with one provider`() = runBlocking {
        repoOf("only")
        manager().addRepository("https://example.org/repo.json", NewRepoProviders.ALL_OFF)
        assertTrue(saved.value.none { it.enabled })
    }

    @Test
    fun `a newly synced repo arrives off and is listed for review`() = runBlocking {
        repoOf("only")
        manager().reconcileWithRemoteRepoUrls(
            listOf(RemotePluginInfo(url = "https://example.org/repo.json")),
            removeMissingLocal = false
        )
        assertTrue(saved.value.none { it.enabled })
        coVerify(exactly = 1) { dataStore.markSyncedRepoToReview(any()) }
    }

    @Test
    fun `a synced repo already on the box stays as it is`() = runBlocking {
        repos.value = listOf(PluginRepository(id = "r", name = "Repo", url = "https://example.org/repo.json"))
        saved.value = listOf(jsScraper("kept"))
        manager().reconcileWithRemoteRepoUrls(
            listOf(RemotePluginInfo(url = "https://example.org/repo.json")),
            removeMissingLocal = false
        )
        assertTrue(saved.value.single().enabled)
        coVerify(exactly = 0) { dataStore.markSyncedRepoToReview(any()) }
    }

    @Test
    fun `a javascript plugin hitting its own limit reads as timed out`() = runBlocking {
        saved.value = listOf(jsScraper("slow"))
        coEvery { runtime.executePlugin(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            withTimeout(1) { delay(1_000) }
            emptyList()
        }
        val pm = manager()
        pm.executeScrapersStreaming("1", "movie").collect()
        val outcome = pm.lastOutcomes.value.getValue("slow")
        assertEquals(PluginRunState.DOWN, outcome.state)
        assertEquals(PluginRunReason.TIMEOUT, outcome.reason)
    }

    @Test
    fun `a search cancelled early leaves nothing running in the summary`() = runBlocking {
        saved.value = listOf(jsScraper("a"), jsScraper("b"), jsScraper("c"))
        val started = CompletableDeferred<Unit>()
        coEvery { runtime.executePlugin(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            started.complete(Unit)
            awaitCancellation()
        }
        val pm = manager()
        pm.aliasSearch("content", "1", "movie", null, null)
        val job = launch(Dispatchers.Default) { pm.executeScrapersStreaming("1", "movie").collect() }
        started.await()
        job.cancelAndJoin()
        val summary = pm.searchSummary("content").first()!!
        assertEquals(3, summary.total)
        assertEquals(3, summary.outcomes.size)
        assertEquals(3, summary.count(PluginRunState.STOPPED))
    }
}
