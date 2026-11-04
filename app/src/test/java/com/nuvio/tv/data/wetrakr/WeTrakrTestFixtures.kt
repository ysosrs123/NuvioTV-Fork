package com.nuvio.tv.data.wetrakr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

class InMemoryWeTrakrAuthPersistence : WeTrakrAuthPersistence {
    val values = mutableMapOf<Int, String>()
    override fun read(profileId: Int): String? = values[profileId]
    override fun write(profileId: Int, value: String?) {
        if (value == null) values.remove(profileId) else values[profileId] = value
    }
    override fun clear() = values.clear()
}

class InMemoryWeTrakrSyncStorage : WeTrakrSyncStorage {
    val values = mutableMapOf<Int, String>()
    override suspend fun load(profileId: Int): String? = values[profileId]
    override suspend fun save(profileId: Int, payload: String, checkScope: () -> Unit) {
        checkScope()
        values[profileId] = payload
    }
    override suspend fun remove(profileId: Int, checkScope: () -> Unit) {
        values.remove(profileId)
    }
}

class FakeWeTrakrEngine : WeTrakrHttpEngine {
    val requests = mutableListOf<WeTrakrHttpRequest>()
    private val routes = mutableListOf<Pair<(WeTrakrHttpRequest) -> Boolean, ArrayDeque<WeTrakrHttpResponse>>>()

    fun on(method: WeTrakrHttpMethod, path: String, vararg responses: WeTrakrHttpResponse) {
        routes += { request: WeTrakrHttpRequest -> request.method == method && request.path == path } to ArrayDeque(responses.toList())
    }

    override suspend fun execute(request: WeTrakrHttpRequest): WeTrakrHttpResponse {
        requests += request
        val queue = routes.lastOrNull { it.first(request) }?.second
            ?: error("No route for ${request.method} ${request.path}")
        return if (queue.size > 1) queue.removeFirst() else queue.first()
    }

    fun calls(path: String) = requests.filter { it.path == path }
}

fun ok(body: String, vararg headers: Pair<String, String>, status: Int = 200) = WeTrakrHttpResponse(status, body, headers.toMap())

class WeTrakrHarness(
    val engine: FakeWeTrakrEngine = FakeWeTrakrEngine(),
    var now: Long = 1_793_750_400_000L
) {
    val persistence = InMemoryWeTrakrAuthPersistence()
    val store = WeTrakrAuthStore(persistence)
    val http = WeTrakrHttpClient(engine, nowEpochMs = { now }, sleep = { now += it })
    val configuration = WeTrakrConfiguration(clientId = "client-key", appVersion = "test")
    val auth = WeTrakrAuthRepository(http, configuration, store, nowEpochMs = { now })
    val api = WeTrakrApiClient(http, auth, store)
    val storage = InMemoryWeTrakrSyncStorage()
    val activeProfile = MutableStateFlow(1)

    fun signIn(user: WeTrakrUser? = WeTrakrUser(42, "alex")) {
        store.authorize(WeTrakrTokens("access", "refresh", now + 7L * 24 * 60 * 60 * 1_000), store.scope())
        user?.let { store.saveUser(it, store.scope()) }
    }

    fun repository(scope: CoroutineScope = TestScope(UnconfinedTestDispatcher()), remote: WeTrakrSyncRemote? = null) =
        WeTrakrSyncRepository(
            storage, store, api, activeProfile, scope, now = { now }, dispatcher = Dispatchers.Unconfined,
            remote = remote?.let { r -> { _: WeTrakrAuthScope -> r } } ?: { WeTrakrHttpSyncRemote(api, it) }
        )
}
