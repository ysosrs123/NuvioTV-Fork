package com.nuvio.tv.core.debrid

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.domain.model.DebridSettings

data class DebridProvider(
    val id: String,
    val displayName: String,
    val shortName: String,
    val visibleInUi: Boolean = true,
    val authMethod: DebridProviderAuthMethod = DebridProviderAuthMethod.ApiKey,
    val capabilities: Set<DebridProviderCapability> = emptySet(),
    val syncsWithAccount: Boolean = true
)

data class DebridServiceCredential(
    val provider: DebridProvider,
    val apiKey: String
)

enum class DebridProviderCapability {
    ClientResolve,
    LocalTorrentCacheCheck,
    LocalTorrentResolve,
    CloudLibrary
}

enum class DebridProviderAuthMethod {
    ApiKey,
    DeviceCode
}

object DebridProviders {
    const val TORBOX_ID = "torbox"
    const val PREMIUMIZE_ID = "premiumize"
    const val REAL_DEBRID_ID = "realdebrid"
    const val ALL_DEBRID_ID = "alldebrid"

    val Torbox = DebridProvider(
        id = TORBOX_ID,
        displayName = "Torbox",
        shortName = "TB",
        authMethod = DebridProviderAuthMethod.DeviceCode,
        capabilities = setOf(
            DebridProviderCapability.ClientResolve,
            DebridProviderCapability.LocalTorrentCacheCheck,
            DebridProviderCapability.LocalTorrentResolve,
            DebridProviderCapability.CloudLibrary
        )
    )

    val Premiumize = DebridProvider(
        id = PREMIUMIZE_ID,
        displayName = "Premiumize",
        shortName = "PM",
        authMethod = premiumizeAuthMethod(BuildConfig.PREMIUMIZE_CLIENT_ID),
        capabilities = setOf(
            DebridProviderCapability.ClientResolve,
            DebridProviderCapability.LocalTorrentCacheCheck,
            DebridProviderCapability.LocalTorrentResolve,
            DebridProviderCapability.CloudLibrary
        )
    )

    val RealDebrid = DebridProvider(
        id = REAL_DEBRID_ID,
        displayName = "Real-Debrid",
        shortName = "RD",
        authMethod = DebridProviderAuthMethod.DeviceCode,
        syncsWithAccount = false,
        capabilities = setOf(
            DebridProviderCapability.ClientResolve,
            DebridProviderCapability.LocalTorrentResolve,
            DebridProviderCapability.CloudLibrary
        )
    )

    val AllDebrid = DebridProvider(
        id = ALL_DEBRID_ID,
        displayName = "AllDebrid",
        shortName = "AD",
        authMethod = DebridProviderAuthMethod.DeviceCode,
        syncsWithAccount = false,
        capabilities = setOf(
            DebridProviderCapability.ClientResolve,
            DebridProviderCapability.LocalTorrentResolve,
            DebridProviderCapability.CloudLibrary
        )
    )

    private val registered = listOf(Torbox, Premiumize, RealDebrid, AllDebrid)

    fun premiumizeAuthMethod(clientId: String): DebridProviderAuthMethod =
        if (clientId.isBlank()) DebridProviderAuthMethod.ApiKey else DebridProviderAuthMethod.DeviceCode

    fun all(): List<DebridProvider> = registered

    fun visible(): List<DebridProvider> = registered.filter { it.visibleInUi }

    /** The services whose keys Nuvio account sync stores; the others stay on the device. */
    fun syncedWithAccount(): List<DebridProvider> = registered.filter { it.syncsWithAccount }

    fun byId(id: String?): DebridProvider? {
        val normalized = id?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return registered.firstOrNull { it.id.equals(normalized, ignoreCase = true) }
    }

    fun isSupported(id: String?): Boolean = byId(id) != null

    fun isVisible(id: String?): Boolean = byId(id)?.visibleInUi == true

    fun instantName(id: String?): String = "${displayName(id)} Instant"

    fun addonId(id: String?): String =
        "debrid:${byId(id)?.id ?: id?.trim().orEmpty().ifBlank { "unknown" }}"

    fun displayName(id: String?): String {
        return byId(id)?.displayName ?: id.toFallbackDisplayName()
    }

    fun shortName(id: String?): String {
        return byId(id)?.shortName ?: id?.trim()?.takeIf { it.isNotBlank() }?.uppercase().orEmpty()
    }

    fun configuredServices(settings: DebridSettings): List<DebridServiceCredential> =
        registered.mapNotNull { provider ->
            settings.apiKeyFor(provider.id)
                .trim()
                .takeIf { provider.visibleInUi && it.isNotBlank() }
                ?.let { apiKey -> DebridServiceCredential(provider, apiKey) }
        }

    fun configuredResolverServices(settings: DebridSettings): List<DebridServiceCredential> =
        configuredServices(settings).filter { credential ->
            credential.provider.supports(DebridProviderCapability.ClientResolve) ||
                credential.provider.supports(DebridProviderCapability.LocalTorrentResolve)
        }

    fun preferredResolverService(settings: DebridSettings): DebridServiceCredential? {
        val services = configuredResolverServices(settings)
        if (services.isEmpty()) return null
        val preferredId = byId(settings.preferredResolverProviderId)?.id
        return services.firstOrNull { it.provider.id == preferredId } ?: services.firstOrNull()
    }

    /** Connected resolver accounts, the preferred one first. */
    fun orderedResolverServices(settings: DebridSettings): List<DebridServiceCredential> {
        val services = configuredResolverServices(settings)
        val preferred = preferredResolverService(settings) ?: return services
        return listOf(preferred) + services.filter { it.provider.id != preferred.provider.id }
    }

    /**
     * The accounts that handle torrents an add-on gives without a service: those that can say what they have
     * cached, in the preferred order. Real-Debrid and AllDebrid only when nothing else is connected.
     */
    fun plainTorrentServices(settings: DebridSettings): List<DebridServiceCredential> {
        val ordered = orderedResolverServices(settings)
            .filter { it.provider.supports(DebridProviderCapability.LocalTorrentResolve) }
        val checkable = ordered.filter { it.provider.supports(DebridProviderCapability.LocalTorrentCacheCheck) }
        return checkable.ifEmpty { ordered.take(1) }
    }

    fun configuredSourceNames(settings: DebridSettings): List<String> {
        return configuredServices(settings).map { instantName(it.provider.id) }
    }

    private fun String?.toFallbackDisplayName(): String {
        val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return "Debrid"
        return value
            .replace('-', ' ')
            .replace('_', ' ')
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.lowercase().replaceFirstChar { it.titlecase() }
            }
            .ifBlank { "Debrid" }
    }
}

fun DebridProvider.supports(capability: DebridProviderCapability): Boolean =
    capability in capabilities
