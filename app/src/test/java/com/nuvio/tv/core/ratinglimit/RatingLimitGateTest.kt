package com.nuvio.tv.core.ratinglimit

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RatingLimitGateTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun gate(pick: MutableStateFlow<Long>, saved: Map<String, String>): RatingLimitGate {
        val stored = saved.toMutableMap<String, Any?>()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val prefs = mockk<SharedPreferences> {
            every { all } answers { stored }
            every { edit() } returns editor
        }
        val context = mockk<Context> {
            every { getSharedPreferences("arc_rating_limit", any()) } returns prefs
            every { filesDir } returns folder.root
        }
        val profiles = mockk<ProfileManager> { every { profileSelectionRevision } returns pick }
        val tmdb = mockk<TmdbService> { coEvery { ensureTmdbId(any(), any(), any()) } returns null }
        return RatingLimitGate(context, mockk(relaxed = true), tmdb, profiles)
    }

    @Test
    fun `an unlocked title locks again at the next profile pick, even of the same profile`() = runBlocking {
        val pick = MutableStateFlow(0L)
        val gate = gate(pick, mapOf("p2" to "pg"))
        gate.unlock(2, "tt0000001", "movie")
        assertEquals(RatingCheck.Allowed, gate.check(2, "tt0000001", "movie"))
        pick.value = 1
        assertTrue(gate.check(2, "tt0000001", "movie") is RatingCheck.NeedsPin)
    }

    @Test
    fun `a removed profile's slot carries no limit, and clearing the box removes them all`() {
        val gate = gate(MutableStateFlow(0L), mapOf("p2" to "pg", "p3" to "y"))
        assertEquals(RatingLimit.PG, gate.limitFor(2))
        gate.forgetProfile(2)
        assertNull(gate.limitFor(2))
        assertEquals(RatingLimit.PRESCHOOL, gate.limitFor(3))
        gate.forgetAll()
        assertNull(gate.limitFor(3))
    }

    @Test
    fun `ratings looked up for a list are written once, not per title`() = runBlocking {
        val gate = gate(MutableStateFlow(0L), mapOf("p2" to "pg"))
        val kept = gate.keepFitting(RatingLimit.PG, listOf("tt0000001", "tt0000002", "tt0000003")) { it to "movie" }
        assertTrue(kept.isEmpty())
        val file = folder.root.resolve("rating_limit/ratings.json")
        assertTrue(file.exists())
        assertEquals(3, org.json.JSONObject(file.readText()).length())
    }

    @Test
    fun `the gate is left out only when the app is not a Hilt app, never because a look-up failed`() {
        val plain = mockk<Context> { every { applicationContext } returns mockk<android.app.Application>() }
        assertNull(ratingLimitGateOrNull(plain))
        val hiltApp = mockk<android.app.Application>(moreInterfaces = *arrayOf(dagger.hilt.internal.GeneratedComponentManager::class)) {
            every { (this@mockk as dagger.hilt.internal.GeneratedComponentManager<*>).generatedComponent() } returns Any()
        }
        val broken = mockk<Context> { every { applicationContext } returns hiltApp }
        org.junit.Assert.assertThrows(Throwable::class.java) { ratingLimitGateOrNull(broken) }
    }
}
