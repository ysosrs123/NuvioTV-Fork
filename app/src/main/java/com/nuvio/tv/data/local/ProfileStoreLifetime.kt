package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One file generation. Retained callers cannot write into a recreated profile. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ProfileStoreLifetime(
    private val delegate: DataStore<Preferences>,
    val generations: StateFlow<Long> = MutableStateFlow(0L)
) : DataStore<Preferences> {
    private val gate = Mutex()
    private val retiredState = MutableStateFlow(false)
    val retired: StateFlow<Boolean> = retiredState

    override val data: Flow<Preferences> = retired.flatMapLatest { ended ->
        if (ended) flowOf(Result.success(emptyPreferences())) else delegate.data
            .map<Preferences, Result<Preferences>> { Result.success(it) }
            .catch { error ->
                // A child CancellationException is normal to flatMapLatest. Carry an
                // unexpected active-upstream failure as a value across that boundary
                // before restoring the original exception for the caller.
                if (error is CancellationException && !currentCoroutineContext().isActive) throw error
                emit(Result.failure(error))
            }
    }.map { it.getOrThrow() }

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = gate.withLock {
        checkActive()
        delegate.updateData(transform)
    }

    fun checkActive() {
        if (retired.value) throw CancellationException("Profile store generation retired")
    }

    suspend fun retire() = gate.withLock {
        // Serialize with an already admitted edit; prevent every subsequent edit.
        retiredState.value = true
        delegate.updateData { emptyPreferences() }
        Unit
    }
}
