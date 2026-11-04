package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AllDebridEnvelopeDto<T>(
    @Json(name = "status") val status: String? = null,
    @Json(name = "data") val data: T? = null,
    @Json(name = "error") val error: AllDebridErrorDto? = null
) {
    val isSuccess: Boolean
        get() = status.equals("success", ignoreCase = true) && error == null
}

@JsonClass(generateAdapter = true)
data class AllDebridErrorDto(
    @Json(name = "code") val code: String? = null,
    @Json(name = "message") val message: String? = null
)

@JsonClass(generateAdapter = true)
data class AllDebridPinDto(
    @Json(name = "pin") val pin: String? = null,
    @Json(name = "check") val check: String? = null,
    @Json(name = "expires_in") val expiresIn: Int? = null,
    @Json(name = "user_url") val userUrl: String? = null,
    @Json(name = "base_url") val baseUrl: String? = null
)

@JsonClass(generateAdapter = true)
data class AllDebridPinCheckDto(
    @Json(name = "activated") val activated: Boolean? = null,
    @Json(name = "expires_in") val expiresIn: Int? = null,
    @Json(name = "apikey") val apiKey: String? = null
)

@JsonClass(generateAdapter = true)
data class AllDebridUserDataDto(
    @Json(name = "user") val user: AllDebridUserDto? = null
)

@JsonClass(generateAdapter = true)
data class AllDebridUserDto(
    @Json(name = "username") val username: String? = null,
    @Json(name = "isPremium") val isPremium: Boolean? = null,
    @Json(name = "premiumUntil") val premiumUntil: Long? = null
)

@JsonClass(generateAdapter = true)
data class AllDebridUploadDataDto(
    @Json(name = "magnets") val magnets: List<AllDebridUploadedMagnetDto>? = null
)

@JsonClass(generateAdapter = true)
data class AllDebridUploadedMagnetDto(
    @Json(name = "magnet") val magnet: String? = null,
    @Json(name = "hash") val hash: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "size") val size: Long? = null,
    @Json(name = "ready") val ready: Boolean? = null,
    @Json(name = "id") val id: Long? = null,
    @Json(name = "error") val error: AllDebridErrorDto? = null
)

@JsonClass(generateAdapter = true)
data class AllDebridMagnetDto(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "filename") val filename: String? = null,
    @Json(name = "hash") val hash: String? = null,
    @Json(name = "size") val size: Long? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "statusCode") val statusCode: Int? = null,
    @Json(name = "uploadDate") val uploadDate: Long? = null,
    @Json(name = "completionDate") val completionDate: Long? = null
) {
    val isReady: Boolean
        get() = statusCode == 4 || status.equals("Ready", ignoreCase = true)
}

@JsonClass(generateAdapter = true)
data class AllDebridFilesDataDto(
    @Json(name = "magnets") val magnets: List<AllDebridMagnetFilesDto>? = null
)

@JsonClass(generateAdapter = true)
data class AllDebridMagnetFilesDto(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "files") val files: List<AllDebridFileNodeDto>? = null,
    @Json(name = "error") val error: AllDebridErrorDto? = null
)

@JsonClass(generateAdapter = true)
data class AllDebridFileNodeDto(
    @Json(name = "n") val name: String? = null,
    @Json(name = "s") val size: Long? = null,
    @Json(name = "l") val link: String? = null,
    @Json(name = "e") val entries: List<AllDebridFileNodeDto>? = null
)

@JsonClass(generateAdapter = true)
data class AllDebridUnlockDto(
    @Json(name = "link") val link: String? = null,
    @Json(name = "filename") val filename: String? = null,
    @Json(name = "filesize") val filesize: Long? = null,
    @Json(name = "id") val id: String? = null,
    @Json(name = "delayed") val delayed: Long? = null
)

@JsonClass(generateAdapter = true)
data class AllDebridDelayedDto(
    @Json(name = "status") val status: Int? = null,
    @Json(name = "time_left") val timeLeft: Int? = null,
    @Json(name = "link") val link: String? = null
)
