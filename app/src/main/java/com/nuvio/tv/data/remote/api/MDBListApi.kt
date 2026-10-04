package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.mdblist.MDBListMediaResponseDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListMediaRequestDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListUserDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface MDBListApi {
    @GET("{provider}/{mediaType}/{mediaId}")
    suspend fun getMedia(
        @Path("provider") provider: String,
        @Path("mediaType") mediaType: String,
        @Path("mediaId") mediaId: String,
        @Query("apikey") apiKey: String,
        @Query("append_to_response") appendToResponse: String = "keyword"
    ): Response<MDBListMediaResponseDto>

    /**
     * no-cache is load-bearing: this endpoint returns
     * `Cache-Control: public, max-age=900`, so the shared client's disk
     * cache would serve a request count up to fifteen minutes old - and
     * would also validate an API key revoked within that window.
     */
    @Headers("Cache-Control: no-cache")
    @GET("user")
    suspend fun getUser(
        @Query("apikey") apiKey: String
    ): Response<MDBListUserDto>

    @POST("{provider}/{mediaType}/")
    suspend fun getMediaBatch(
        @Path("provider") provider: String,
        @Path("mediaType") mediaType: String,
        @Query("apikey") apiKey: String,
        @Body body: MDBListMediaRequestDto
    ): Response<List<MDBListMediaResponseDto>>
}
