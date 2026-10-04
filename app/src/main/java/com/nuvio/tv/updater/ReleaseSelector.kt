package com.nuvio.tv.updater

import com.nuvio.tv.data.remote.dto.GitHubReleaseDto

internal object ReleaseSelector {
    private val codePattern = Regex("""<!-- nuvio-fork-version-code: ([1-9][0-9]*) -->""")

    fun versionCode(release: GitHubReleaseDto): Long? =
        codePattern.find(release.body.orEmpty())?.groupValues?.get(1)?.toLongOrNull()

    // GitHub prerelease flags never exclude releases from this fork's single stream.
    fun eligibleReleases(releases: List<GitHubReleaseDto>): List<GitHubReleaseDto> = releases
        .filter { !it.draft && VersionUtils.parse(it.tagName) != null }
        .sortedWith(compareByDescending<GitHubReleaseDto> { versionCode(it) ?: Long.MIN_VALUE }
            .thenByDescending { VersionUtils.parse(it.tagName) })
}
