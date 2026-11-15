package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.PlaybackIssueReportResponseDto
import com.nuvio.tv.data.remote.dto.ReportStatusRequestDto
import com.nuvio.tv.data.remote.dto.ReportStatusResponseDto
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url

interface ReportRelayApi {
    @POST
    suspend fun upload(@Url path: String, @Body body: RequestBody): Response<PlaybackIssueReportResponseDto>

    @POST("api/report-status")
    suspend fun status(@Body body: ReportStatusRequestDto): Response<ReportStatusResponseDto>
}
