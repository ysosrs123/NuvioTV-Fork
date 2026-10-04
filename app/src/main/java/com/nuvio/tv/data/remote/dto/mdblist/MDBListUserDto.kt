package com.nuvio.tv.data.remote.dto.mdblist

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Shape of `GET /user`.
 *
 * Only the fields the settings screen renders are modelled. The live response
 * also returns `store_account_token`, which is a credential and is deliberately
 * not mapped - an unused field cannot be logged or leaked by accident.
 *
 * Note the endpoint responds with `Cache-Control: public, max-age=900`, so the
 * request must bypass the shared OkHttp disk cache or a readout goes stale for
 * a quarter of an hour. See [com.nuvio.tv.data.remote.api.MDBListApi.getUser].
 *
 * `rate_limit_remaining` and `api_requests_count` are complementary - they sum
 * to `rate_limit` - and no `X-RateLimit-*` response headers accompany them on
 * this endpoint, despite what the published docs suggest.
 */
@JsonClass(generateAdapter = true)
data class MDBListUserDto(
    @Json(name = "username") val username: String? = null,
    @Json(name = "plan") val plan: String? = null,
    /** Daily request ceiling for the account's tier. */
    @Json(name = "rate_limit") val rateLimit: Int? = null,
    /** Requests already spent today, across ratings and sync alike. */
    @Json(name = "api_requests_count") val apiRequestsCount: Int? = null,
    @Json(name = "rate_limit_remaining") val rateLimitRemaining: Int? = null
)
