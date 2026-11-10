package com.nuvio.tv.data.floppy

import java.io.IOException

class FakeFloppyPersistence : FloppyAuthPersistence {
    val values = mutableMapOf<Int, String>()
    override fun read(profileId: Int): String? = values[profileId]
    override fun write(profileId: Int, value: String?) {
        if (value == null) values.remove(profileId) else values[profileId] = value
    }
    override fun clear() = values.clear()
}

class FakeFloppyEngine : FloppyHttpEngine {
    val requests = mutableListOf<FloppyHttpRequest>()
    private val handlers = mutableListOf<(FloppyHttpRequest) -> FloppyHttpResponse?>()

    fun on(method: FloppyHttpMethod, path: String, status: Int = 200, body: String = "{}") {
        handlers += { request ->
            if (request.method == method && request.path == path) FloppyHttpResponse(status, body) else null
        }
    }

    fun onceFirst(method: FloppyHttpMethod, path: String, status: Int, body: String = "{}") {
        var used = false
        handlers.add(0) { request ->
            if (!used && request.method == method && request.path == path) {
                used = true
                FloppyHttpResponse(status, body)
            } else {
                null
            }
        }
    }

    override suspend fun execute(request: FloppyHttpRequest): FloppyHttpResponse {
        requests += request
        return handlers.firstNotNullOfOrNull { it(request) } ?: throw IOException("no route")
    }

    fun paths(): List<String> = requests.map { it.path }
}

class FloppyTestHarness {
    var now = 1_000_000L
    val engine = FakeFloppyEngine()
    val persistence = FakeFloppyPersistence()
    val store = FloppyAuthStore(persistence)
    val auth = FloppyAuthRepository(engine, store, "Nuvio Arc on Test", nowEpochMs = { now })
    val api = FloppyApiClient(engine, auth, store)
    val ledger = FloppyPlayLedger(FakeFloppyPersistence())

    fun floppyServer() {
        engine.on(FloppyHttpMethod.POST, "/oauth/register", 201, """{"client_id":"flp_oauth_1"}""")
        engine.on(FloppyHttpMethod.POST, "/oauth/device/authorization", 200, DEVICE_RESPONSE)
        engine.on(FloppyHttpMethod.POST, "/oauth/token", 200, TOKEN_RESPONSE)
        engine.on(FloppyHttpMethod.GET, FloppyAuthRepository.CONNECTION_CHECK_PATH, 200, "[]")
        engine.on(FloppyHttpMethod.POST, "/api/v1/scrobble/", 201, "{}")
        engine.on(FloppyHttpMethod.POST, "/oauth/revoke", 200, "{}")
    }

    companion object {
        const val SERVER = "http://192.168.1.20:8000"
        const val DEVICE_RESPONSE =
            """{"device_code":"flp_device_1","user_code":"K7QM-4XTD","verification_uri":"http://192.168.1.20:8000/oauth/device","verification_uri_complete":"http://192.168.1.20:8000/oauth/device?user_code=K7QM-4XTD","expires_in":600,"interval":5}"""
        const val TOKEN_RESPONSE =
            """{"access_token":"flp_access_1","token_type":"Bearer","expires_in":3600,"refresh_token":"flp_refresh_1","scope":"scrobble:write"}"""
    }
}
