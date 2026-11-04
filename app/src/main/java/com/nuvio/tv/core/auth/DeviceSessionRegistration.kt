package com.nuvio.tv.core.auth

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.logging.forLog
import com.nuvio.tv.core.sync.SyncClientIdentity
import com.nuvio.tv.domain.model.AuthState
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DeviceSessionRegistration"
private const val CLIENT_NAME = "Nuvio TV"
private const val REGISTRATION_INTERVAL_MS = 15 * 60 * 1000L
private const val MAX_DEVICE_NAME_LENGTH = 160

internal data class DeviceClientMetadata(
    val deviceName: String,
    val platform: String
)

@Singleton
class DeviceSessionRegistration @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val authManager: AuthManager,
    private val postgrest: Postgrest,
    private val syncClientIdentity: SyncClientIdentity
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val registrationMutex = Mutex()
    private var lastRegistrationAtMs = 0L

    init {
        scope.launch {
            authManager.authState.collect { state ->
                when (state) {
                    is AuthState.FullAccount -> registerIfAuthenticated(force = true)
                    is AuthState.SignedOut -> registrationMutex.withLock {
                        lastRegistrationAtMs = 0L
                    }
                    is AuthState.Loading -> Unit
                }
            }
        }
    }

    fun requestForegroundRegistration() {
        scope.launch {
            registerIfAuthenticated()
        }
    }

    internal suspend fun registerIfAuthenticated(force: Boolean = false): Boolean {
        if (authManager.authState.value !is AuthState.FullAccount) return false

        return registrationMutex.withLock {
            if (authManager.authState.value !is AuthState.FullAccount) {
                return@withLock false
            }

            val now = SystemClock.elapsedRealtime()
            if (!force && lastRegistrationAtMs > 0L && now - lastRegistrationAtMs < REGISTRATION_INTERVAL_MS) {
                return@withLock true
            }

            val params = buildDeviceRegistrationParams(
                installationId = syncClientIdentity.currentClientId(),
                clientVersion = BuildConfig.VERSION_NAME,
                metadata = currentDeviceClientMetadata(context)
            )

            try {
                register(params)
                lastRegistrationAtMs = SystemClock.elapsedRealtime()
                true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "Device session registration failed", error.forLog())
                false
            }
        }
    }

    private suspend fun register(params: JsonObject) {
        try {
            postgrest.rpc("register_current_device", params)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (!authManager.refreshSessionIfJwtExpired(error)) throw error
            postgrest.rpc("register_current_device", params)
        }
    }
}

internal fun buildDeviceRegistrationParams(
    installationId: String,
    clientVersion: String,
    metadata: DeviceClientMetadata
): JsonObject = buildJsonObject {
    put("p_installation_id", installationId)
    put("p_client_name", CLIENT_NAME)
    put("p_client_version", clientVersion)
    put("p_platform", metadata.platform)
    put("p_device_name", metadata.deviceName)
}

internal fun currentDeviceClientMetadata(context: Context): DeviceClientMetadata {
    val configuredName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
        runCatching {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()
    } else {
        null
    }
    val deviceName = selectDeviceName(
        configuredName = configuredName,
        manufacturer = Build.MANUFACTURER.orEmpty(),
        model = Build.MODEL.orEmpty(),
        fallback = "Android TV"
    ).take(MAX_DEVICE_NAME_LENGTH)
    val osVersion = Build.VERSION.RELEASE.orEmpty()
        .trim()
        .ifBlank { Build.VERSION.SDK_INT.toString() }

    return DeviceClientMetadata(
        deviceName = deviceName,
        platform = "Android TV $osVersion"
    )
}

internal fun selectDeviceName(
    configuredName: String?,
    manufacturer: String,
    model: String,
    fallback: String
): String {
    return configuredName
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: formatDeviceName(manufacturer, model, fallback)
}

internal fun formatDeviceName(
    manufacturer: String,
    model: String,
    fallback: String
): String {
    val normalizedManufacturer = manufacturer.trim()
    val normalizedModel = model.trim()

    return when {
        normalizedModel.isBlank() -> fallback
        normalizedManufacturer.isBlank() -> normalizedModel
        normalizedModel.startsWith(normalizedManufacturer, ignoreCase = true) -> normalizedModel
        else -> "$normalizedManufacturer $normalizedModel"
    }
}
