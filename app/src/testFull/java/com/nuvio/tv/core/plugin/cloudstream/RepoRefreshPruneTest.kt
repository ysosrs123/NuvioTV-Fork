package com.nuvio.tv.core.plugin.cloudstream

import com.nuvio.tv.core.plugin.scrapersDroppedFromRepo
import com.nuvio.tv.domain.model.ScraperInfo
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepoRefreshPruneTest {

    private val parser = ExternalRepoParser(Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build())

    private fun scraper(id: String, repo: String) = ScraperInfo(
        id = id,
        name = id,
        description = "",
        version = "1",
        filename = "",
        supportedTypes = listOf("movie"),
        enabled = true,
        manifestEnabled = true,
        logo = null,
        contentLanguage = emptyList(),
        repositoryId = repo,
        formats = null
    )

    private fun MockWebServer.serve(routes: Map<String, Pair<Int, String>>) {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val (code, body) = routes[request.path] ?: (404 to "")
                return MockResponse().setResponseCode(code).setBody(body)
            }
        }
    }

    @Test
    fun `only providers of this repo that it no longer lists are dropped`() {
        val all = listOf(scraper("a:kept", "a"), scraper("a:gone", "a"), scraper("b:other", "b"))
        val dropped = scrapersDroppedFromRepo(all, "a", setOf("a:kept"))
        assertEquals(listOf("a:gone"), dropped.map { it.id })
    }

    @Test
    fun `a repo whose lists were all read is complete`() = runTest {
        val entry = """[{"name":"One","internalName":"One","url":"https://example.invalid/one.cs3"}]"""
        MockWebServer().use { server ->
            server.serve(
                mapOf(
                    "/repo.json" to (200 to """{"name":"Repo","pluginLists":["list.json"]}"""),
                    "/list.json" to (200 to entry)
                )
            )
            val result = parser.tryParse(server.url("/repo.json").toString())!!
            assertTrue(result.complete)
            assertEquals(listOf("One"), result.plugins.map { it.internalName })
        }
    }

    @Test
    fun `a repo with one unreadable list is not complete, so nothing gets pruned`() = runTest {
        val entry = """[{"name":"One","internalName":"One","url":"https://example.invalid/one.cs3"}]"""
        MockWebServer().use { server ->
            server.serve(
                mapOf(
                    "/repo.json" to (200 to """{"name":"Repo","pluginLists":["good.json","bad.json"]}"""),
                    "/good.json" to (200 to entry),
                    "/bad.json" to (500 to "")
                )
            )
            val result = parser.tryParse(server.url("/repo.json").toString())!!
            assertFalse(result.complete)
            assertEquals(1, result.plugins.size)
        }
    }
}
