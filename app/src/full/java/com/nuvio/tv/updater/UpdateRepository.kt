package com.nuvio.tv.updater

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.GitHubReleaseApi
import com.nuvio.tv.updater.model.AppUpdate
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

internal class NoEligibleUpdateException : IllegalStateException("No compatible fork update found")

@Singleton
class UpdateRepository @Inject constructor(private val gitHubReleaseApi: GitHubReleaseApi) {
    suspend fun getLatestUpdate(
        supportedAbis: List<String> =
            AbiSelector.installedFirst(android.os.Build.SUPPORTED_ABIS?.toList().orEmpty())
    ): Result<AppUpdate> = try {
        // List releases includes prereleases; /latest does not. Inspect all pages.
        val releases = mutableListOf<com.nuvio.tv.data.remote.dto.GitHubReleaseDto>()
        var page = 1
        do {
            val response = gitHubReleaseApi.getReleases(
                owner = BuildConfig.GITHUB_OWNER, repo = BuildConfig.GITHUB_REPO, page = page++
            )
            check(response.isSuccessful) { "GitHub API error: ${response.code()}" }
            val batch = response.body() ?: error("Empty GitHub release response")
            releases.addAll(batch)
        } while (response.headers()["Link"].orEmpty().contains("rel=\"next\""))
        val selected = ReleaseSelector.eligibleReleases(releases)
            .filter { VersionUtils.isRemoteNewer(it.tagName, BuildConfig.VERSION_NAME,
                ReleaseSelector.versionCode(it), BuildConfig.VERSION_CODE.toLong()) }
            .firstNotNullOfOrNull { release ->
                AbiSelector.chooseBestApkAsset(release.assets, supportedAbis)?.takeIf { asset ->
                    asset.browserDownloadUrl.startsWith(
                        "https://github.com/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases/download/"
                    ) && (asset.size == null || asset.size > 0)
                }?.let { release to it }
            } ?: throw NoEligibleUpdateException()
        val (dto, asset) = selected
        Result.success(AppUpdate(
            tag = dto.tagName!!, title = dto.name?.takeIf { it.isNotBlank() } ?: dto.tagName,
            notes = dto.body.orEmpty().replace(Regex("""<!-- nuvio-fork-version-code: [0-9]+ -->"""), "").trim(),
            releaseUrl = dto.htmlUrl, assetName = asset.name, assetUrl = asset.browserDownloadUrl,
            assetSizeBytes = asset.size, versionCode = ReleaseSelector.versionCode(dto)
        ))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
}
