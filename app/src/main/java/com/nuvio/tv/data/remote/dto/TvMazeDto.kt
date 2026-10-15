package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TvMazeShowDto(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "network") val network: TvMazeNetworkDto? = null,
    @Json(name = "webChannel") val webChannel: TvMazeNetworkDto? = null,
    @Json(name = "externals") val externals: TvMazeExternalsDto? = null,
    @Json(name = "image") val image: TvMazeImageDto? = null,
    @Json(name = "_embedded") val embedded: TvMazeShowEmbeddedDto? = null
)

@JsonClass(generateAdapter = true)
data class TvMazeImageDto(
    @Json(name = "medium") val medium: String? = null,
    @Json(name = "original") val original: String? = null
)

@JsonClass(generateAdapter = true)
data class TvMazeNetworkDto(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "name") val name: String? = null
)

@JsonClass(generateAdapter = true)
data class TvMazeExternalsDto(
    @Json(name = "imdb") val imdb: String? = null,
    @Json(name = "thetvdb") val thetvdb: Long? = null,
    @Json(name = "tvrage") val tvrage: Long? = null
)

@JsonClass(generateAdapter = true)
data class TvMazeShowEmbeddedDto(
    @Json(name = "nextepisode") val nextEpisode: TvMazeEpisodeDto? = null,
    @Json(name = "previousepisode") val previousEpisode: TvMazeEpisodeDto? = null,
    @Json(name = "seasons") val seasons: List<TvMazeSeasonDto>? = null
)

@JsonClass(generateAdapter = true)
data class TvMazeSeasonDto(
    @Json(name = "id") val id: Int,
    @Json(name = "number") val number: Int? = null,
    @Json(name = "premiereDate") val premiereDate: String? = null,
    @Json(name = "endDate") val endDate: String? = null
)

@JsonClass(generateAdapter = true)
data class TvMazeEpisodeDto(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "season") val season: Int? = null,
    @Json(name = "number") val number: Int? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "airdate") val airdate: String? = null,
    @Json(name = "airtime") val airtime: String? = null,
    @Json(name = "airstamp") val airstamp: String? = null,
    @Json(name = "runtime") val runtime: Int? = null
)
