package com.nuvio.tv.core.stream

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.repository.StreamRepository
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SpeculativeStreamPolicyTest {
    private val profile = AtomicInteger(nextProfile.incrementAndGet())
    private val rows = listOf(mockk<AddonStreams>())
    private fun policy(enabled: Boolean) {
        StreamPrefetchCache.bindProfile { profile.get() }
        StreamPrefetchCache.updatePolicy(profile.get(), enabled)
    }
    private class Repository(val rows: List<AddonStreams>) : StreamRepository {
        val searches = AtomicInteger()
        val explicit = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        var wait = false
        override fun setLocalPluginSearchPaused(paused: Boolean) {}
        override fun getStreamsForPrefetch(type: String, videoId: String, season: Int?, episode: Int?) = flow {
            searches.incrementAndGet()
            started.complete(Unit)
            try {
                if (wait) awaitCancellation()
                emit(NetworkResult.Success(rows))
            } finally { cancelled.complete(Unit) }
        }
        override fun getStreamsFromAllAddons(type: String, videoId: String, season: Int?, episode: Int?, forceRefresh: Boolean) = flow {
            explicit.incrementAndGet()
            emit(NetworkResult.Success(rows))
        }
        override suspend fun getStreamsFromAddon(addon: Addon, type: String, videoId: String): NetworkResult<List<Stream>> = error("unexpected addon request")
    }
    private fun prefetch(repo: Repository) = StreamPrefetchCache.prefetch(repo, "movie", "title", null, null, "test")
    @After fun stop() { policy(false); StreamPrefetchCache.cancelPending() }

    @Test fun `OFF performs zero speculative searches but explicit Play still loads`() = runBlocking {
        policy(false)
        val repo = Repository(rows)
        prefetch(repo)
        assertEquals(0, repo.searches.get())
        assertEquals(rows, (StreamPrefetchCache.streamsFor(repo, "movie", "title", null, null).first() as NetworkResult.Success).data)
        assertEquals(1, repo.explicit.get())
    }
    @Test fun `OFF cancels in flight search and a Play join falls through`() = runBlocking {
        policy(true)
        val repo = Repository(rows).apply { wait = true }
        prefetch(repo)
        withTimeout(3000) { repo.started.await() }
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            StreamPrefetchCache.streamsFor(repo, "movie", "title", null, null).toList()
        }
        policy(false)
        withTimeout(3000) { repo.cancelled.await() }
        assertEquals(rows, (withTimeout(3000) { result.await() }.last() as NetworkResult.Success).data)
        assertEquals(1, repo.explicit.get())
        prefetch(repo)
        assertEquals(1, repo.searches.get())
    }
    @Test fun `navigation cancels work while keeping completed metadata reusable`() = runBlocking {
        policy(true)
        val repo = Repository(rows)
        prefetch(repo)
        withTimeout(3000) { repo.started.await() }
        StreamPrefetchCache.streamsFor(repo, "movie", "title", null, null).toList()
        StreamPrefetchCache.cancelPending()
        assertEquals(rows, (StreamPrefetchCache.streamsFor(repo, "movie", "title", null, null).toList().last() as NetworkResult.Success).data)
        assertEquals(0, repo.explicit.get())
    }
    @Test fun `profile switch rejects old permission before settings observer runs`() = runBlocking {
        policy(true)
        val repo = Repository(rows)
        prefetch(repo)
        withTimeout(3000) { repo.started.await() }
        StreamPrefetchCache.streamsFor(repo, "movie", "title", null, null).toList()
        profile.incrementAndGet()
        prefetch(repo)
        StreamPrefetchCache.streamsFor(repo, "movie", "title", null, null).toList()
        assertEquals(1, repo.searches.get())
        assertEquals(1, repo.explicit.get())
        policy(false)
        assertNull(StreamPrefetchCache.selectionFor("movie", "title", null, null))
    }
    @Test fun `a flow created before profile change cannot replay the old profile cache`() = runBlocking {
        policy(true)
        val repo = Repository(rows)
        prefetch(repo)
        withTimeout(3000) { repo.started.await() }
        StreamPrefetchCache.streamsFor(repo, "movie", "title", null, null).toList()
        val deferredFlow = StreamPrefetchCache.streamsFor(repo, "movie", "title", null, null)
        profile.incrementAndGet()
        deferredFlow.toList()
        assertEquals(1, repo.explicit.get())
    }
    private companion object { val nextProfile = AtomicInteger(100) }
}
