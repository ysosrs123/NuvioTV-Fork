package com.nuvio.tv.core.scout.ask

import android.content.Context
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.core.profile.ProtectedProfilePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject
import javax.inject.Singleton

data class AskKeys(val active: AskProvider? = null, val configs: Map<AskProvider, AskConfig> = emptyMap()) {
    val activeConfig: AskConfig? get() = active?.let(configs::get)
}

internal object AskKeysCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(keys: AskKeys): String = buildJsonObject {
        keys.active?.let { put("active", it.id) }
        putJsonObject("configs") {
            keys.configs.forEach { (provider, config) ->
                putJsonObject(provider.id) {
                    put("key", config.key)
                    put("model", config.model)
                    put("baseUrl", config.baseUrl)
                }
            }
        }
    }.toString()

    fun decode(raw: String?): AskKeys {
        if (raw.isNullOrBlank()) return AskKeys()
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return AskKeys()
        val configs = (root["configs"] as? JsonObject).orEmpty().mapNotNull { (id, value) ->
            val provider = AskProvider.fromId(id) ?: return@mapNotNull null
            val obj = value as? JsonObject ?: return@mapNotNull null
            val key = obj.text("key") ?: return@mapNotNull null
            provider to AskConfig(provider, key, obj.text("model").orEmpty(), obj.text("baseUrl").orEmpty())
        }.toMap()
        return AskKeys(AskProvider.fromId(root.text("active"))?.takeIf(configs::containsKey), configs)
    }

    private fun JsonObject.text(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull
}

/** AI keys stay encrypted on this box. No sync service reads this store. */
@Singleton
class AskKeyVault @Inject constructor(
    @ApplicationContext context: Context,
    private val profileManager: dagger.Lazy<ProfileManager>
) : ProfileScopedCredentialStore {
    private val protected = ProtectedProfilePreferences(context, "scout_ask_keys", "scout_ask_keys_v1")
    private val changes = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val keys: Flow<AskKeys> get() = profileManager.get().activeProfileId.flatMapLatest { id ->
        changes.map { read(id) }
    }

    fun current(): AskKeys = read(profileManager.get().activeProfileId.value)

    fun save(config: AskConfig) {
        val id = profileManager.get().activeProfileId.value
        protected.write(id, AskKeysCodec.encode(AskKeys(config.provider, read(id).configs + (config.provider to config))))
        changes.value++
    }

    fun select(provider: AskProvider) {
        val id = profileManager.get().activeProfileId.value
        val keys = read(id)
        if (provider !in keys.configs) return
        protected.write(id, AskKeysCodec.encode(keys.copy(active = provider)))
        changes.value++
    }

    fun remove(provider: AskProvider) {
        val id = profileManager.get().activeProfileId.value
        val keys = read(id)
        val left = keys.configs - provider
        val active = keys.active?.takeIf { it != provider } ?: left.keys.firstOrNull()
        protected.write(id, if (left.isEmpty()) null else AskKeysCodec.encode(AskKeys(active, left)))
        changes.value++
    }

    private fun read(profileId: Int): AskKeys = AskKeysCodec.decode(protected.read(profileId))

    override fun removeProfile(profileId: Int) {
        protected.write(profileId, null)
        changes.value++
    }

    override fun clearAllProfiles() {
        protected.clear()
        changes.value++
    }
}
