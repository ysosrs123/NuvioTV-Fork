package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RealDebridAddTorrentDto(
    @Json(name = "id") val id: String? = null,
    @Json(name = "uri") val uri: String? = null
)

@JsonClass(generateAdapter = true)
data class RealDebridTorrentInfoDto(
    @Json(name = "id") val id: String? = null,
    @Json(name = "filename") val filename: String? = null,
    @Json(name = "original_filename") val originalFilename: String? = null,
    @Json(name = "hash") val hash: String? = null,
    @Json(name = "bytes") val bytes: Long? = null,
    @Json(name = "original_bytes") val originalBytes: Long? = null,
    @Json(name = "host") val host: String? = null,
    @Json(name = "split") val split: Int? = null,
    @Json(name = "progress") val progress: Int? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "files") val files: List<RealDebridTorrentFileDto>? = null,
    @Json(name = "links") val links: List<String>? = null
)

@JsonClass(generateAdapter = true)
data class RealDebridTorrentFileDto(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "path") val path: String? = null,
    @Json(name = "bytes") val bytes: Long? = null,
    @Json(name = "selected") val selected: Int? = null
) {
    fun displayName(): String = path.orEmpty().substringAfterLast('/').ifBlank { path.orEmpty() }
}

@JsonClass(generateAdapter = true)
data class RealDebridUnrestrictLinkDto(
    @Json(name = "id") val id: String? = null,
    @Json(name = "filename") val filename: String? = null,
    @Json(name = "mimeType") val mimeType: String? = null,
    @Json(name = "filesize") val filesize: Long? = null,
    @Json(name = "link") val link: String? = null,
    @Json(name = "host") val host: String? = null,
    @Json(name = "chunks") val chunks: Int? = null,
    @Json(name = "crc") val crc: Int? = null,
    @Json(name = "download") val download: String? = null,
    @Json(name = "streamable") val streamable: Int? = null,
    @Json(name = "type") val type: String? = null
)

@JsonClass(generateAdapter = true)
data class RealDebridTorrentListItemDto(
    @Json(name = "id") val id: String? = null,
    @Json(name = "filename") val filename: String? = null,
    @Json(name = "hash") val hash: String? = null,
    @Json(name = "bytes") val bytes: Long? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "added") val added: String? = null,
    @Json(name = "links") val links: List<String>? = null
)

@JsonClass(generateAdapter = true)
data class RealDebridDeviceCodeDto(
    @Json(name = "device_code") val deviceCode: String? = null,
    @Json(name = "user_code") val userCode: String? = null,
    @Json(name = "interval") val interval: Int? = null,
    @Json(name = "expires_in") val expiresIn: Int? = null,
    @Json(name = "verification_url") val verificationUrl: String? = null,
    @Json(name = "direct_verification_url") val directVerificationUrl: String? = null
)

@JsonClass(generateAdapter = true)
data class RealDebridDeviceCredentialsDto(
    @Json(name = "client_id") val clientId: String? = null,
    @Json(name = "client_secret") val clientSecret: String? = null
)

@JsonClass(generateAdapter = true)
data class RealDebridTokenDto(
    @Json(name = "access_token") val accessToken: String? = null,
    @Json(name = "expires_in") val expiresIn: Long? = null,
    @Json(name = "token_type") val tokenType: String? = null,
    @Json(name = "refresh_token") val refreshToken: String? = null
)
