package com.nuvio.tv.data.remote.api

import com.nuvio.tv.core.scout.ScoutKeyword
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ScoutTitleFacts(
    @Json(name = "id") val id: Int,
    @Json(name = "title") val title: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "overview") val overview: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "vote_average") val voteAverage: Double? = null,
    @Json(name = "genres") val genres: List<TmdbGenre>? = null,
    @Json(name = "created_by") val createdBy: List<TmdbCreatedBy>? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "first_air_date") val firstAirDate: String? = null,
    @Json(name = "runtime") val runtime: Int? = null,
    @Json(name = "episode_run_time") val episodeRunTime: List<Int>? = null,
    @Json(name = "vote_count") val voteCount: Int? = null,
    @Json(name = "original_language") val originalLanguage: String? = null,
    @Json(name = "production_countries") val productionCountries: List<TmdbCountry>? = null,
    @Json(name = "origin_country") val originCountry: List<String>? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    @Json(name = "belongs_to_collection") val collection: ScoutCollectionRef? = null,
    @Json(name = "keywords") val keywords: ScoutKeywordList? = null,
    @Json(name = "credits") val credits: TmdbCreditsResponse? = null,
    @Json(name = "release_dates") val releaseDates: TmdbMovieReleaseDatesResponse? = null,
    @Json(name = "content_ratings") val contentRatings: TmdbTvContentRatingsResponse? = null
)

@JsonClass(generateAdapter = true)
data class ScoutCollectionRef(@Json(name = "id") val id: Int)

@JsonClass(generateAdapter = true)
data class ScoutKeywordList(
    @Json(name = "keywords") val keywords: List<ScoutKeyword>? = null,
    @Json(name = "results") val results: List<ScoutKeyword>? = null
)
