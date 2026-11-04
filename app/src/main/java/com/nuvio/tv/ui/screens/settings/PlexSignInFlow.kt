package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.ServerException
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerRepository
import com.nuvio.tv.data.mediaserver.ServerSignInIssue
import com.nuvio.tv.data.mediaserver.plex.PlexAccount
import com.nuvio.tv.data.mediaserver.plex.PlexHomeUser
import com.nuvio.tv.data.mediaserver.plex.PlexPin
import com.nuvio.tv.data.mediaserver.plex.PlexPinStatus
import com.nuvio.tv.data.mediaserver.plex.PlexProvider
import com.nuvio.tv.data.mediaserver.plex.PlexReach
import com.nuvio.tv.data.mediaserver.plex.PlexServer
import com.nuvio.tv.data.mediaserver.serverFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PlexSignInStep {
    CODE,
    SERVERS,
    USERS,
    PIN,
    SIGNING_IN,
    FAILED
}

enum class PlexCodePhase {
    STARTING,
    WAITING,
    EXPIRED,
    FAILED
}

data class PlexSignInState(
    val step: PlexSignInStep = PlexSignInStep.CODE,
    val pin: PlexPin? = null,
    val codePhase: PlexCodePhase = PlexCodePhase.STARTING,
    val account: String? = null,
    val servers: List<PlexServer> = emptyList(),
    val serversLoading: Boolean = false,
    val server: PlexServer? = null,
    val users: List<PlexHomeUser> = emptyList(),
    val pinFor: PlexHomeUser? = null,
    val pinWrong: Boolean = false,
    val busy: Boolean = false,
    val error: ServerFailure? = null,
    val issue: ServerSignInIssue? = null
)

/** Signs in to Plex: a code at plex.tv/link, then a server from the account, then a Plex Home user. */
class PlexSignInFlow(
    private val repository: ServerRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val mutableState = MutableStateFlow(PlexSignInState())
    val state: StateFlow<PlexSignInState> = mutableState.asStateFlow()

    private val provider: PlexProvider?
        get() = repository.providers.filterIsInstance<PlexProvider>().firstOrNull()

    private var job: Job? = null
    private var codeJob: Job? = null
    private var owner: PlexAccount? = null
    private var onConnected: (ServerConnection) -> Unit = {}
    private var replacing: String? = null
    private var codePaused = false

    fun start(replacing: String? = null, onConnected: (ServerConnection) -> Unit) {
        close()
        this.replacing = replacing
        this.onConnected = onConnected
        newCode()
    }

    /** No polling plex.tv while the app is in the background; a fresh code comes when it is back. */
    fun pauseCode() {
        if (mutableState.value.step != PlexSignInStep.CODE || codeJob?.isActive != true) return
        codeJob?.cancel()
        codeJob = null
        codePaused = true
    }

    fun resumeCode() {
        if (!codePaused) return
        codePaused = false
        if (mutableState.value.step == PlexSignInStep.CODE) newCode()
    }

    fun newCode() {
        val provider = provider ?: return fail(ServerException(ServerFailure.UNSUPPORTED))
        codeJob?.cancel()
        mutableState.value = PlexSignInState(step = PlexSignInStep.CODE, codePhase = PlexCodePhase.STARTING)
        codeJob = scope.launch { runCode(provider) }
    }

    /** New codes keep coming while nobody links one, for up to an hour; Plex's short codes only last a few minutes. */
    private suspend fun runCode(provider: PlexProvider) {
        val startedAt = clock()
        while (true) {
            val pin = try {
                provider.account.startPin()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(codePhase = PlexCodePhase.FAILED).withError(error) }
                return
            }
            mutableState.update { it.copy(pin = pin, codePhase = PlexCodePhase.WAITING, error = null, issue = null) }
            val token = awaitLink(provider, pin)
            if (token != null) {
                linked(provider, token)
                return
            }
            if (clock() - startedAt >= CODE_RENEW_FOR_MS) {
                mutableState.update { it.copy(codePhase = PlexCodePhase.EXPIRED) }
                return
            }
        }
    }

    private suspend fun awaitLink(provider: PlexProvider, pin: PlexPin): String? {
        var waitMs = POLL_MS
        while (clock() < pin.expiresAtMs) {
            delay(waitMs)
            val status = try {
                provider.account.checkPin(pin).also { waitMs = POLL_MS }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                waitMs = (waitMs * 2).coerceAtMost(POLL_BACKOFF_MAX_MS)
                continue
            }
            when (status) {
                is PlexPinStatus.Linked -> return status.accountToken
                PlexPinStatus.Expired -> return null
                PlexPinStatus.Waiting -> Unit
            }
        }
        return null
    }

    private suspend fun linked(provider: PlexProvider, token: String) {
        mutableState.update { it.copy(step = PlexSignInStep.SERVERS, serversLoading = true, busy = false) }
        val signedIn = try {
            provider.account.account(token)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            fail(error)
            return
        }
        owner = signedIn
        mutableState.update { it.copy(account = signedIn.name) }
        val found = try {
            coroutineScope {
                val users = async { provider.account.homeUsers(signedIn) }
                val servers = provider.account.servers(token)
                mutableState.update { it.copy(servers = servers) }
                val reached = servers.map { server -> async { provider.reach(server) } }.awaitAll()
                mutableState.update { it.copy(users = users.await()) }
                reached
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            fail(error)
            return
        }
        val ordered = found.sortedWith(compareBy({ it.reach.ordinal }, { !it.owned }, { it.name.lowercase() }))
        mutableState.update { it.copy(servers = ordered, serversLoading = false) }
        ordered.singleOrNull()?.takeIf { it.reach != PlexReach.OFFLINE }?.let { pickServer(it) }
    }

    fun pickServer(server: PlexServer) {
        if (mutableState.value.busy || server.reach == PlexReach.OFFLINE) return
        val users = mutableState.value.users
        mutableState.update { it.copy(server = server, error = null, issue = null) }
        val only = users.singleOrNull()
        when {
            users.isEmpty() || only != null && !only.hasPin -> job = scope.launch { finish(server, only, null) }
            only != null -> mutableState.update { it.copy(step = PlexSignInStep.PIN, pinFor = only, pinWrong = false) }
            else -> mutableState.update { it.copy(step = PlexSignInStep.USERS) }
        }
    }

    fun backToServers() {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(step = PlexSignInStep.SERVERS, pinFor = null, pinWrong = false, error = null, issue = null) }
    }

    fun pickUser(user: PlexHomeUser) {
        val server = mutableState.value.server ?: return
        if (mutableState.value.busy) return
        if (user.hasPin) {
            mutableState.update { it.copy(step = PlexSignInStep.PIN, pinFor = user, pinWrong = false, error = null) }
        } else {
            job = scope.launch { finish(server, user, null) }
        }
    }

    fun backToUsers() {
        if (mutableState.value.busy) return
        val next = if (mutableState.value.users.size > 1) PlexSignInStep.USERS else PlexSignInStep.SERVERS
        mutableState.update { it.copy(step = next, pinFor = null, pinWrong = false, error = null) }
    }

    fun submitPin(pin: String) {
        val state = mutableState.value
        val server = state.server ?: return
        val user = state.pinFor ?: return
        if (state.busy || pin.isBlank()) return
        job = scope.launch { finish(server, user, pin.trim()) }
    }

    private suspend fun finish(server: PlexServer, user: PlexHomeUser?, pin: String?) {
        val provider = provider ?: return
        val owner = owner ?: return
        val previousStep = mutableState.value.step
        mutableState.update { it.copy(busy = true, pinWrong = false, error = null, issue = null) }
        val connection = try {
            val switched = if (user == null) owner else provider.account.switchUser(owner, user, pin)
            if (switched == null) {
                mutableState.update { it.copy(busy = false, pinWrong = true) }
                return
            }
            mutableState.update { it.copy(step = PlexSignInStep.SIGNING_IN) }
            val serverForUser = if (switched.uuid == owner.uuid) {
                server
            } else {
                provider.account.servers(switched.token).firstOrNull { it.machineId == server.machineId }
                    ?.reached(server.reach, server.address, server.secondAddress)
                    ?: throw ServerException(ServerFailure.FORBIDDEN)
            }
            repository.connectWith(provider, replacing = replacing) { provider.signIn(serverForUser, owner, switched) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableState.update { it.copy(step = previousStep, busy = false).withError(error) }
            return
        }
        server.secondAddress
            ?.takeIf { connection.alternateAddress == null }
            ?.let { repository.setListedAlternateAddress(connection.id, it) }
        currentCoroutineContext().ensureActive()
        this.owner = null
        mutableState.value = PlexSignInState()
        onConnected(connection)
    }

    fun close() {
        codePaused = false
        job?.cancel()
        codeJob?.cancel()
        job = null
        codeJob = null
        owner = null
        mutableState.value = PlexSignInState()
    }

    private fun fail(error: Exception) {
        mutableState.update { it.copy(step = PlexSignInStep.FAILED, busy = false, serversLoading = false).withError(error) }
    }

    private fun PlexSignInState.withError(error: Exception): PlexSignInState =
        copy(error = error.serverFailure(), issue = (error as? ServerException)?.issue)

    private companion object {
        const val POLL_MS = 1_000L
        const val POLL_BACKOFF_MAX_MS = 15_000L
        const val CODE_RENEW_FOR_MS = 60 * 60_000L
    }
}
