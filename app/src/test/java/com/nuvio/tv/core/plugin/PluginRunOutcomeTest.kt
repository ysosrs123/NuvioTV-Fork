package com.nuvio.tv.core.plugin

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Test

class PluginRunOutcomeTest {

    @Test
    fun `results win over any problem`() {
        val problems = PluginRunProblems()
        problems.noteFailure(SocketTimeoutException("read timed out"))
        assertEquals(PluginRunOutcome("p", PluginRunState.FOUND, 4), problems.outcome("p", 4))
    }

    @Test
    fun `an extension stops collecting links when the search limit is due`() {
        val problems = PluginRunProblems(linksDueAtMs = 10_000)
        assertEquals(4_000L, problems.linkWaitMs(capMs = 60_000, nowMs = 6_000))
        assertEquals(0L, problems.linkWaitMs(capMs = 60_000, nowMs = 12_000))
        assertEquals(60_000L, PluginRunProblems(linksDueAtMs = 100_000).linkWaitMs(capMs = 60_000, nowMs = 0))
        assertEquals(60_000L, PluginRunProblems().linkWaitMs(capMs = 60_000, nowMs = 0))
    }

    @Test
    fun `providers that never reported are stopped and finished ones are left alone`() {
        val found = PluginRunOutcome("a", PluginRunState.FOUND, 2)
        val summary = PluginSearchSummary(total = 3, outcomes = listOf(found))
        val closed = summary.withUnfinishedStopped(listOf("a", "b", "c"))
        assertEquals(found, closed.outcomes.first())
        assertEquals(2, closed.count(PluginRunState.STOPPED))
        val done = PluginSearchSummary(total = 1, outcomes = listOf(found))
        assertEquals(done, done.withUnfinishedStopped(listOf("a", "b")))
    }

    @Test
    fun `no results and no problem reads as no results`() {
        assertEquals(PluginRunState.NO_RESULTS, PluginRunProblems().outcome("p", 0).state)
    }

    @Test
    fun `timeouts and unknown hosts read as the site being down`() {
        val timeout = PluginRunProblems().apply { noteFailure(SocketTimeoutException()) }.outcome("p", 0)
        assertEquals(PluginRunState.DOWN, timeout.state)
        assertEquals(PluginRunReason.TIMEOUT, timeout.reason)
        val host = PluginRunProblems().apply { noteFailure(UnknownHostException("x.example")) }.outcome("p", 0)
        assertEquals(PluginRunReason.UNREACHABLE, host.reason)
    }

    @Test
    fun `an http code in the message is kept`() {
        val outcome = PluginRunProblems().apply { noteFailure(IOException("Request failed with HTTP 403")) }.outcome("p", 0)
        assertEquals(PluginRunReason.HTTP, outcome.reason)
        assertEquals(403, outcome.httpCode)
    }

    @Test
    fun `a missing class means the extension needs a newer library`() {
        val outcome = PluginRunProblems().apply {
            noteFailure(NoClassDefFoundError("Lcom/lagradost/cloudstream3/syncproviders/SyncRepo;"))
        }.outcome("p", 0)
        assertEquals(PluginRunState.CANT_RUN, outcome.state)
        assertEquals(PluginRunReason.NEWER_LIBRARY, outcome.reason)
    }

    @Test
    fun `a specific reason replaces a generic error but not the other way round`() {
        val problems = PluginRunProblems()
        problems.noteFailure(IllegalStateException("parse"))
        problems.noteFailure(SocketTimeoutException())
        problems.noteFailure(IllegalStateException("again"))
        assertEquals(PluginRunReason.TIMEOUT, problems.outcome("p", 0).reason)
    }

    @Test
    fun `search keys match for the same title`() {
        assertEquals(pluginSearchKey("Series", "tt1", 1, 2), pluginSearchKey("series", "tt1", 1, 2))
        assertEquals("movie:tt9::", pluginSearchKey("movie", "tt9", null, null))
    }
}
