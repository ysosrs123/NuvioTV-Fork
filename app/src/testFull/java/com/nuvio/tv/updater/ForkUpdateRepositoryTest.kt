package com.nuvio.tv.updater

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.GitHubReleaseApi
import com.nuvio.tv.data.remote.dto.GitHubAssetDto
import com.nuvio.tv.data.remote.dto.GitHubReleaseDto
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import okhttp3.Headers
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class ForkUpdateRepositoryTest {
    private val api = mockk<GitHubReleaseApi>()
    private val repo = UpdateRepository(api)
    private val nextCode = BuildConfig.VERSION_CODE + 1L
    private fun asset(abi: String = "arm64-v8a") = GitHubAssetDto(
        "app-full-$abi-release.apk",
        "https://github.com/ysosrs123/NuvioTV-Fork/releases/download/test/app-full-$abi-release.apk", 42
    )
    private fun release(code: Long = nextCode, assets: List<GitHubAssetDto> = listOf(asset())) = GitHubReleaseDto(
        tagName = BuildConfig.VERSION_NAME, prerelease = true,
        body = "Notes\n<!-- nuvio-fork-version-code: $code -->", assets = assets
    )
    @Test fun `fetches fork prereleases across pages and offers higher build with same tag`() = runTest {
        coEvery { api.getReleases("ysosrs123", "NuvioTV-Fork", 100, 1) } returns Response.success(
            listOf(release(BuildConfig.VERSION_CODE.toLong())),
            Headers.headersOf("Link", "<https://api.github.com/next>; rel=\"next\"")
        )
        coEvery { api.getReleases("ysosrs123", "NuvioTV-Fork", 100, 2) } returns Response.success(listOf(release()))
        val update = repo.getLatestUpdate(listOf("arm64-v8a")).getOrThrow()
        assertEquals(nextCode, update.versionCode)
        assertEquals("Notes", update.notes)
        coVerify(exactly = 0) { api.getLatestRelease(any(), any()) }
    }
    @Test fun `skips newer release without compatible ABI and never accepts external asset URL`() = runTest {
        coEvery { api.getReleases(any(), any(), any(), any()) } returns Response.success(listOf(
            release(nextCode + 2, listOf(asset("x86_64"))),
            release(nextCode + 1, listOf(asset().copy(browserDownloadUrl = "https://github.com/NuvioMedia/NuvioTV/official.apk"))),
            release()
        ))
        assertEquals(nextCode, repo.getLatestUpdate(listOf("arm64-v8a")).getOrThrow().versionCode)
    }
    @Test fun `empty feed no matching assets and installed builds are not offered`() = runTest {
        for (releases in listOf(emptyList(), listOf(release(1)), listOf(release(assets = listOf(asset("x86")))))) {
            coEvery { api.getReleases(any(), any(), any(), any()) } returns Response.success(releases)
            assertTrue(repo.getLatestUpdate(listOf("arm64-v8a")).exceptionOrNull() is NoEligibleUpdateException)
        }
    }
    @Test fun `offline and HTTP failures are errors rather than update available`() = runTest {
        coEvery { api.getReleases(any(), any(), any(), any()) } returns Response.error(403, "limited".toResponseBody())
        assertTrue(repo.getLatestUpdate(listOf("arm64-v8a")).isFailure)
        coEvery { api.getReleases(any(), any(), any(), any()) } throws java.io.IOException("offline")
        assertTrue(repo.getLatestUpdate(listOf("arm64-v8a")).exceptionOrNull() is java.io.IOException)
    }
    @Test fun `cancellation propagates instead of becoming no update feedback`() = runTest {
        coEvery { api.getReleases(any(), any(), any(), any()) } throws CancellationException()
        try {
            repo.getLatestUpdate(listOf("arm64-v8a"))
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
    @Test fun `ABI choice rejects singleton wrong ABI debug store flavor and ambiguous APKs`() {
        assertNull(AbiSelector.chooseBestApkAsset(listOf(asset("x86")), listOf("arm64-v8a")))
        for (name in listOf("app.apk", "app-full-arm64-v8a-debug.apk", "app-playstore-arm64-v8a-release.apk"))
            assertNull(AbiSelector.chooseBestApkAsset(listOf(asset().copy(name = name)), listOf("arm64-v8a")))
        val all = listOf(asset("armeabi-v7a"), asset(), asset("universal"))
        assertEquals(asset(), AbiSelector.chooseBestApkAsset(all, listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals(asset("universal"), AbiSelector.chooseBestApkAsset(all, listOf("x86")))
    }
    @Test fun `update keeps the installed APK type on a box that supports both`() {
        val device = listOf("arm64-v8a", "armeabi-v7a", "armeabi")
        val all = listOf(asset("armeabi-v7a"), asset())
        assertEquals(listOf("arm64-v8a", "armeabi-v7a", "armeabi"), AbiSelector.installedFirst(device, is64Bit = true))
        assertEquals(listOf("armeabi-v7a", "armeabi", "arm64-v8a"), AbiSelector.installedFirst(device, is64Bit = false))
        assertEquals(asset(), AbiSelector.chooseBestApkAsset(all, AbiSelector.installedFirst(device, is64Bit = true)))
        assertEquals(asset("armeabi-v7a"),
            AbiSelector.chooseBestApkAsset(all, AbiSelector.installedFirst(device, is64Bit = false)))
        // A 32-bit-only device is unchanged.
        assertEquals(listOf("armeabi-v7a", "armeabi"), AbiSelector.installedFirst(listOf("armeabi-v7a", "armeabi"), false))
    }
}
