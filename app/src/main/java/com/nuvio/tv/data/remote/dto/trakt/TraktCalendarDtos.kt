package com.nuvio.tv.data.remote.dto.trakt

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TraktCalendarShowDto(
    @Json(name = "first_aired") val firstAired: String? = null,
    @Json(name = "episode") val episode: TraktCalendarEpisodeDto? = null,
    @Json(name = "show") val show: TraktShowDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktCalendarEpisodeDto(
    @Json(name = "season") val season: Int? = null,
    @Json(name = "number") val number: Int? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null,
    @Json(name = "first_aired") val firstAired: String? = null,
    @Json(name = "episode_type") val episodeType: String? = null,
    @Json(name = "runtime") val runtime: Int? = null
)
