package com.nuvio.tv.data.local

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.gson.Gson
import com.nuvio.tv.domain.model.WatchedItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
internal class WatchedItemsOwner(
    val store: DataStore<Preferences>,
    private val onRetired: (WatchedItemsOwner) -> Unit
) {
    companion object {
        private const val TAG = "WatchedItemsCache"
        private val key = stringSetPreferencesKey("watched_items")
    }
    private sealed interface Event {
        data object Decoded : Event
        data object Retired : Event
        data class Failed(val error: Throwable) : Event
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val epoch = AtomicLong()
    private val gson = Gson()
    val decoder = WatchedItemsDecoder { raw ->
        runCatching { gson.fromJson(raw, WatchedItem::class.java) }.getOrNull()
    }
    private val retired = (store as? ProfileStoreLifetime)?.retired ?: MutableStateFlow(false)

    // No synthetic initial value. Stop disk observation and expire replay immediately
    // when unused. Keep only this generation's current decoded revision for fresh reads.
    private val changes = store.data.map { it[key] ?: emptySet() }
        .distinctUntilChanged()
        .map<Set<String>, Event> { readItems(); Event.Decoded }
        .catch { error ->
            if (error is CancellationException && (!currentCoroutineContext().isActive || retired.value)) throw error
            emit(Event.Failed(error))
        }
        .shareIn(scope, SharingStarted.WhileSubscribed(0, 0), replay = 1)

    init {
        scope.launch {
            retired.first { it }
            mutex.withLock { decoder.clear() }
            onRetired(this@WatchedItemsOwner)
            scope.cancel()
        }
    }

    fun observe(): Flow<List<WatchedItem>> = retired.flatMapLatest { ended ->
        if (ended) flowOf(Event.Retired) else changes
    }.map { event ->
        // Restore failures after flatMapLatest so an unexpected CancellationException
        // reaches the reader instead of being consumed as child cancellation.
        when (event) {
            Event.Retired -> emptyList()
            is Event.Failed -> throw event.error
            Event.Decoded -> readItems()
        }
    }.distinctUntilChanged()

    fun captureEpoch(): Long = epoch.get()
    private fun checkEpoch(expected: Long?) {
        if (expected != null && expected != epoch.get()) throw CancellationException("Watched history generation retired")
    }

    suspend fun readItems(expectedEpoch: Long? = null): List<WatchedItem> = mutex.withLock {
        checkEpoch(expectedEpoch)
        if (retired.value) return@withLock emptyList()
        val prefs = store.data.first()
        if (retired.value) return@withLock emptyList()
        decode(prefs)
    }

    suspend fun readPreferences(expectedEpoch: Long? = null): Preferences = mutex.withLock {
        checkEpoch(expectedEpoch)
        (store as? ProfileStoreLifetime)?.checkActive()
        store.data.first().also { (store as? ProfileStoreLifetime)?.checkActive() }
    }

    suspend fun edit(expectedEpoch: Long? = null, reset: Boolean = false, transform: (MutablePreferences, WatchedItemsDecoder) -> Unit) = mutex.withLock {
        checkEpoch(expectedEpoch)
        (store as? ProfileStoreLifetime)?.checkActive()
        var previous: Set<String> = emptySet()
        val result = store.edit { prefs ->
            previous = prefs[key] ?: emptySet()
            transform(prefs, decoder)
        }
        if (reset) epoch.incrementAndGet()
        // Metadata-only/no-op writes must not load an otherwise unused history.
        if (!retired.value && previous != (result[key] ?: emptySet<String>())) decode(result)
        Unit
    }

    internal suspend fun decodeStats(): Pair<Long, Long> = mutex.withLock {
        decoder.decodeRuns to decoder.parsedEntries
    }

    private fun decode(prefs: Preferences): List<WatchedItem> {
        val before = decoder.parsedEntries
        val runs = decoder.decodeRuns
        val result = decoder.decode(prefs[key] ?: emptySet())
        if (runs != decoder.decodeRuns && Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "decode runs=${decoder.decodeRuns} added=${decoder.parsedEntries - before} items=${result.size}")
        }
        return result
    }
}
