package com.nuvio.tv.updater

import com.nuvio.tv.data.remote.dto.GitHubReleaseDto
import org.junit.Assert.*
import org.junit.Test

class ReleaseSelectorTest {
    @Test fun `single stream includes prereleases and final releases but excludes drafts and invalid tags`() {
        val releases = listOf(
            GitHubReleaseDto(tagName = "0.9.4-beta-nt10", prerelease = true),
            GitHubReleaseDto(tagName = "0.9.4-beta-nt9"),
            GitHubReleaseDto(tagName = "0.9.4"),
            GitHubReleaseDto(tagName = "0.9.5", draft = true),
            GitHubReleaseDto(tagName = "nightly", name = "1.0.0")
        )
        assertEquals(listOf("0.9.4", "0.9.4-beta-nt10", "0.9.4-beta-nt9"),
            ReleaseSelector.eligibleReleases(releases).map { it.tagName })
    }

    @Test fun `release code takes precedence over tag and release order`() {
        val newer = GitHubReleaseDto(tagName = "0.9.4-beta-nt1", body = "<!-- nuvio-fork-version-code: 1400 -->")
        val older = GitHubReleaseDto(tagName = "0.9.5-beta-nt1", body = "<!-- nuvio-fork-version-code: 1399 -->")
        assertEquals(listOf(newer, older), ReleaseSelector.eligibleReleases(listOf(older, newer)))
        assertEquals(1400L, ReleaseSelector.versionCode(newer))
        assertNull(ReleaseSelector.versionCode(newer.copy(body = "Build 1400")))
    }

    @Test fun `all legacy channels and version names resolve to beta`() {
        for (value in listOf(null, "stable", "beta", "future"))
            assertEquals(UpdateChannel.BETA, UpdateChannel.fromStoredValue(value))
        assertEquals(UpdateChannel.BETA, UpdateChannel.defaultForVersion("1.0.0"))
    }
}
