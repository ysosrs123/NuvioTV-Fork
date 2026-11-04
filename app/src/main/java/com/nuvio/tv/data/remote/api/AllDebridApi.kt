package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.AllDebridDelayedDto
import com.nuvio.tv.data.remote.dto.AllDebridEnvelopeDto
import com.nuvio.tv.data.remote.dto.AllDebridFilesDataDto
import com.nuvio.tv.data.remote.dto.AllDebridPinCheckDto
import com.nuvio.tv.data.remote.dto.AllDebridPinDto
import com.nuvio.tv.data.remote.dto.AllDebridUnlockDto
import com.nuvio.tv.data.remote.dto.AllDebridUploadDataDto
import com.nuvio.tv.data.remote.dto.AllDebridUserDataDto
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

interface AllDebridApi {
    @GET("v4.1/pin/get")
    suspend fun getPin(): Response<AllDebridEnvelopeDto<AllDebridPinDto>>

    @FormUrlEncoded
    @POST("v4/pin/check")
    suspend fun checkPin(
        @Field("check") check: String,
        @Field("pin") pin: String
    ): Response<AllDebridEnvelopeDto<AllDebridPinCheckDto>>

    @GET("v4/user")
    suspend fun getUser(
        @Header("Authorization") authorization: String
    ): Response<AllDebridEnvelopeDto<AllDebridUserDataDto>>

    @FormUrlEncoded
    @POST("v4/magnet/upload")
    suspend fun uploadMagnets(
        @Header("Authorization") authorization: String,
        @Field("magnets[]") magnets: List<String>
    ): Response<AllDebridEnvelopeDto<AllDebridUploadDataDto>>

    @POST("v4.1/magnet/status")
    suspend fun listMagnets(
        @Header("Authorization") authorization: String
    ): Response<ResponseBody>

    @FormUrlEncoded
    @POST("v4.1/magnet/status")
    suspend fun magnetStatus(
        @Header("Authorization") authorization: String,
        @Field("id") id: Long
    ): Response<ResponseBody>

    @FormUrlEncoded
    @POST("v4/magnet/files")
    suspend fun magnetFiles(
        @Header("Authorization") authorization: String,
        @Field("id[]") ids: List<Long>
    ): Response<AllDebridEnvelopeDto<AllDebridFilesDataDto>>

    @FormUrlEncoded
    @POST("v4/magnet/delete")
    suspend fun deleteMagnet(
        @Header("Authorization") authorization: String,
        @Field("id") id: Long
    ): Response<AllDebridEnvelopeDto<Any>>

    @FormUrlEncoded
    @POST("v4/link/unlock")
    suspend fun unlockLink(
        @Header("Authorization") authorization: String,
        @Field("link") link: String
    ): Response<AllDebridEnvelopeDto<AllDebridUnlockDto>>

    @FormUrlEncoded
    @POST("v4/link/delayed")
    suspend fun delayedLink(
        @Header("Authorization") authorization: String,
        @Field("id") id: Long
    ): Response<AllDebridEnvelopeDto<AllDebridDelayedDto>>
}
