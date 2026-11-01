package com.nuvio.tv.data.remote.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path

interface ParentalGuideApi {

    @GET("titles/{imdbId}/parentsGuide")
    suspend fun getParentsGuide(
        @Path("imdbId") imdbId: String
    ): Response<ImdbApiParentsGuideResponse>
}

@JsonClass(generateAdapter = true)
data class ImdbApiParentsGuideResponse(
    @Json(name = "parentsGuide") val parentsGuide: List<ImdbApiParentsGuideCategory>? = null
)

@JsonClass(generateAdapter = true)
data class ImdbApiParentsGuideCategory(
    @Json(name = "category") val category: String,
    @Json(name = "severityBreakdowns") val severityBreakdowns: List<ImdbApiSeverityBreakdown>? = null,
    @Json(name = "reviews") val reviews: List<ImdbApiParentsGuideReview>? = null
)

@JsonClass(generateAdapter = true)
data class ImdbApiSeverityBreakdown(
    @Json(name = "severityLevel") val severityLevel: String? = null,
    // IMDb leaves the count out for some levels; a missing count is no votes.
    @Json(name = "voteCount") val voteCount: Int? = null
)

@JsonClass(generateAdapter = true)
data class ImdbApiParentsGuideReview(
    @Json(name = "text") val text: String? = null,
    @Json(name = "isSpoiler") val isSpoiler: Boolean? = null
)
