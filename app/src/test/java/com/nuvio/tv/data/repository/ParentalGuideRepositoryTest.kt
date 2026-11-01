package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.api.ImdbApiParentsGuideResponse
import com.nuvio.tv.data.remote.api.ParentalGuideApi
import com.squareup.moshi.Moshi
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response

class ParentalGuideRepositoryTest {

    private val adapter = Moshi.Builder().build().adapter(ImdbApiParentsGuideResponse::class.java)

    @Test
    fun `a severity level without a vote count does not drop the guide`() = runBlocking {
        val body = adapter.fromJson(
            """
            {"parentsGuide":[
              {"category":"VIOLENCE","severityBreakdowns":[
                {"severityLevel":"none","voteCount":3},
                {"severityLevel":"mild","voteCount":10},
                {"severityLevel":"moderate","voteCount":42},
                {"severityLevel":"severe"}
              ]},
              {"category":"PROFANITY","severityBreakdowns":[
                {"severityLevel":"none","voteCount":20},
                {"severityLevel":"mild"}
              ]}
            ]}
            """.trimIndent()
        )!!
        val api = mockk<ParentalGuideApi>()
        coEvery { api.getParentsGuide("tt1985966") } returns Response.success(body)

        val result = ParentalGuideRepository(api).getParentalGuide("tt1985966")

        assertEquals("moderate", result?.violence)
        assertNull(result?.profanity)
    }
}
