package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ReportStatusAskDto(
    @Json(name = "code") val code: String,
    @Json(name = "token") val token: String
)

@JsonClass(generateAdapter = true)
data class ReportStatusRequestDto(
    @Json(name = "reports") val reports: List<ReportStatusAskDto>
)

@JsonClass(generateAdapter = true)
data class ReportStatusItemDto(
    @Json(name = "code") val code: String? = null,
    @Json(name = "state") val state: String? = null,
    @Json(name = "version") val version: String? = null
)

@JsonClass(generateAdapter = true)
data class ReportStatusResponseDto(
    @Json(name = "reports") val reports: List<ReportStatusItemDto>? = null
)
