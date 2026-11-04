package com.nuvio.tv.data.mediaserver.plex

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A code shown on the TV and typed at plex.tv/link. */
class PlexPin(
    val id: Long,
    val code: String,
    val expiresAtMs: Long
) {
    override fun toString(): String = "PlexPin(id=$id)"
}

sealed interface PlexPinStatus {
    data object Waiting : PlexPinStatus
    data object Expired : PlexPinStatus
    class Linked(val accountToken: String) : PlexPinStatus {
        override fun toString(): String = "Linked"
    }
}

/** The signed-in Plex account, before a server and a Plex Home user are chosen. */
class PlexAccount(
    val token: String,
    val id: String,
    val uuid: String,
    val name: String
) {
    override fun toString(): String = "PlexAccount(id=$id)"
}

enum class PlexReach { LOCAL, REMOTE, RELAY, OFFLINE }

/** One address a server can be reached at, as plex.tv lists it. */
data class PlexAddress(
    val uri: String,
    val local: Boolean,
    val relay: Boolean
)

class PlexServer(
    val machineId: String,
    val name: String,
    val owned: Boolean,
    val accessToken: String,
    val addresses: List<PlexAddress>,
    val reach: PlexReach = PlexReach.OFFLINE,
    /** The address that answered first, in the order local, remote, relay. */
    val address: String? = null,
    /** Another working address to fall back to, preferably a remote one. */
    val secondAddress: String? = null
) {
    fun reached(reach: PlexReach, address: String?, secondAddress: String?) =
        PlexServer(machineId, name, owned, accessToken, addresses, reach, address, secondAddress)

    override fun toString(): String = "PlexServer(machineId=$machineId, reach=$reach)"
}

class PlexHomeUser(
    val id: String,
    val uuid: String,
    val name: String,
    val hasPin: Boolean,
    val admin: Boolean,
    val restricted: Boolean
) {
    override fun toString(): String = "PlexHomeUser(id=$id)"
}

/** What a saved Plex sign-in holds; kept on this device only. */
@Serializable
internal data class PlexCredential(
    @SerialName("account") val accountToken: String,
    @SerialName("user") val userToken: String,
    @SerialName("server") val serverToken: String,
    @SerialName("user_id") val userId: String,
    @SerialName("user_uuid") val userUuid: String = "",
    @SerialName("user_name") val userName: String,
    @SerialName("home_user") val homeUser: Boolean = false
) {
    override fun toString(): String = "PlexCredential(userId=$userId)"
}

@Serializable
internal data class PinWire(
    val id: Long = 0,
    val code: String = "",
    @SerialName("expiresIn") val expiresIn: Long = 0,
    @SerialName("authToken") val authToken: String? = null
)

@Serializable
internal data class AccountWire(
    val id: Long = 0,
    val uuid: String = "",
    val username: String = "",
    val title: String = "",
    @SerialName("authToken") val authToken: String? = null
)

@Serializable
internal data class ResourceWire(
    val name: String = "",
    val provides: String = "",
    @SerialName("clientIdentifier") val clientIdentifier: String = "",
    val owned: Boolean = false,
    @SerialName("accessToken") val accessToken: String? = null,
    val presence: Boolean = false,
    val connections: List<ConnectionWire> = emptyList()
)

@Serializable
internal data class ConnectionWire(
    val protocol: String = "",
    val address: String = "",
    val port: Int = 0,
    val uri: String = "",
    val local: Boolean = false,
    val relay: Boolean = false,
    @SerialName("IPv6") val ipv6: Boolean = false
)

@Serializable
internal data class HomeUsersWire(val users: List<HomeUserWire> = emptyList())

@Serializable
internal data class HomeUserWire(
    val id: Long = 0,
    val uuid: String = "",
    val title: String = "",
    val username: String? = null,
    val admin: Boolean = false,
    val restricted: Boolean = false,
    val protected: Boolean = false,
    @SerialName("hasPassword") val hasPassword: Boolean = false
)
