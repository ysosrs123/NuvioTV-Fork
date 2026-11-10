package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AppReportRequestDto(
    @Json(name = "schemaVersion") val schemaVersion: Int,
    @Json(name = "createdAtMs") val createdAtMs: Long,
    @Json(name = "app") val app: PlaybackIssueAppDto,
    @Json(name = "device") val device: PlaybackIssueDeviceDto,
    @Json(name = "network") val network: PlaybackIssueNetworkDto? = null,
    @Json(name = "box") val box: PlaybackIssueBoxDto? = null,
    @Json(name = "kind") val kind: String,
    @Json(name = "where") val where: String? = null,
    @Json(name = "note") val note: String? = null,
    @Json(name = "logLines") val logLines: List<String>? = null,
    @Json(name = "crash") val crash: AppReportCrashDto? = null,
    @Json(name = "extra") val extra: Map<String, String>? = null
)

@JsonClass(generateAdapter = true)
data class AppReportCrashDto(
    @Json(name = "reason") val reason: String,
    @Json(name = "reasonCode") val reasonCode: Int,
    @Json(name = "importance") val importance: Int,
    @Json(name = "status") val status: Int,
    @Json(name = "description") val description: String? = null,
    @Json(name = "exitedAtMs") val exitedAtMs: Long,
    @Json(name = "traceLines") val traceLines: List<String>? = null
)
