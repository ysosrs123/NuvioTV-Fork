package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.remote.dto.PremiumizeDirectDownloadFileDto
import com.nuvio.tv.data.remote.dto.RealDebridTorrentFileDto
import com.nuvio.tv.data.remote.dto.TorboxTorrentFileDto
import com.nuvio.tv.domain.model.StreamClientResolve
import org.junit.Assert.assertEquals
import org.junit.Test

class DebridFileSelectorReleaseTest {
    @Test
    fun `recorded releases select the requested file across providers and file orders`() {
        for (release in releases) {
            val resolve = resolve(release)
            val expected = release.files.single { it.id == release.expectedId }
            for (files in listOf(release.files, release.files.reversed())) {
                assertEquals(
                    release.title,
                    expected.id,
                    TorboxFileSelector().selectFile(files, resolve, release.season, release.episode)?.id,
                )
                assertEquals(
                    release.title,
                    expected.id,
                    RealDebridFileSelector().selectFile(
                        files.map { RealDebridTorrentFileDto(id = it.id, path = it.name, bytes = it.size) },
                        resolve,
                        release.season,
                        release.episode,
                    )?.id,
                )
                assertEquals(
                    release.title,
                    expected.name,
                    PremiumizeDirectDownloadFileSelector().selectFile(
                        files.map { PremiumizeDirectDownloadFileDto(path = it.name, size = it.size, link = "https://example.com/${it.id}") },
                        resolve,
                        release.season,
                        release.episode,
                    )?.path,
                )
            }
        }
    }

    private fun resolve(release: Release) = StreamClientResolve(
        type = "torrent",
        infoHash = null,
        fileIdx = release.fileIdx,
        magnetUri = null,
        sources = null,
        torrentName = release.description,
        filename = release.filename,
        mediaType = "series",
        mediaId = null,
        mediaOnlyId = null,
        title = release.title,
        season = release.season,
        episode = release.episode,
        service = null,
        serviceIndex = null,
        serviceExtension = null,
        isCached = true,
    )

    private data class Release(
        val title: String,
        val season: Int,
        val episode: Int,
        val fileIdx: Int,
        val filename: String,
        val description: String,
        val expectedId: Int,
        val files: List<TorboxTorrentFileDto>,
    )

    private val releases = listOf(
        Release(
            title = "The Example",
            season = 1,
            episode = 2,
            fileIdx = 5,
            filename = "The.Example.S01E02.720p.BluRay.x264.DUAL-WWW.SITENAME.TV.mkv",
            description = "Série de Exemplo (The Example) 2005 - 1ª Temporada Completa Acesse o ORIGINAL WWW.SITENAME.TV 720P\nThe.Example.S01E02.720p.BluRay.x264.DUAL-WWW.SITENAME.TV.mkv\n👤 1 💾 541.09 MB ⚙️ ExampleIndexer\nDual Audio / 🇬🇧 / 🇵🇹",
            expectedId = 3,
            files = listOf(
                TorboxTorrentFileDto(id = 11, name = "Série de Exemplo (The Example) 2005 - 1ª Temporada Completa Acesse o ORIGINAL WWW.SITENAME.TV/SITENAME.TV.mp4", size = 53346313L),
                TorboxTorrentFileDto(id = 3, name = "Série de Exemplo (The Example) 2005 - 1ª Temporada Completa Acesse o ORIGINAL WWW.SITENAME.TV/The.Example.S01E02.720p.BluRay.x264.DUAL-WWW.SITENAME.TV.mkv", size = 567375172L),
            ),
        ),
        Release(
            title = "Sample Show",
            season = 1,
            episode = 2,
            fileIdx = 246,
            filename = "Sample Show (2008) - S01E02 - Part Two... (1080p BluRay x265 Silence).mkv",
            description = "Sample Show (2008) Season 1-5 S01-S05 (1080p BluRay x265 HEVC 10bit AAC 5 1 Silence) [QxR]\nSeason 1/Sample Show (2008) - S01E02 - Part Two... (1080p BluRay x265 Silence).mkv\n👤 391 💾 2.04 GB ⚙️ ExampleIndexer",
            expectedId = 3,
            files = listOf(
                TorboxTorrentFileDto(id = 78, name = "Sample Show (2008) Season 1-5 S01-S05 (1080p BluRay x265 HEVC 10bit AAC 5.1 Silence)/Featurettes/Season 5/Featurettes 5.1/Deleted Scenes/1.mkv", size = 12515022L),
                TorboxTorrentFileDto(id = 3, name = "Sample Show (2008) Season 1-5 S01-S05 (1080p BluRay x265 HEVC 10bit AAC 5.1 Silence)/Season 1/Sample Show (2008) - S01E02 - Part Two... (1080p BluRay x265 Silence).mkv", size = 2185130378L),
            ),
        ),
        Release(
            title = "Tale of Examples",
            season = 1,
            episode = 2,
            fileIdx = 6,
            filename = "Tale.Of.Examples.S01E02.BluRay.720p.DUAL.WWW.OTHERSITE.COM.mkv",
            description = "] Tale of Examples 1ª a 7ª Temporada Completa [720p] [BluRay] [DUAL\n[OTHERSITE.COM] Tale of Examples 1ª Temporada Completa 2011 [720p] [DUAL]/Tale.Of.Examples.S01E02.BluRay.720p.DUAL.WWW.OTHERSITE.COM.mkv\n👤 46 💾 750.52 MB ⚙️ ExampleIndexer\nDual Audio / 🇬🇧 / 🇵🇹",
            expectedId = 42,
            files = listOf(
                TorboxTorrentFileDto(id = 5, name = "[ACESSE OTHERSITE.COM] Tale of Examples 1ª a 7ª Temporada Completa [720p] [BluRay] [DUAL]/[OTHERSITE.COM] Tale of Examples 7ª Temporada Completa 2017 [1080p] [DUAL]/OTHERSITE.COM.mp4", size = 7767348L),
                TorboxTorrentFileDto(id = 42, name = "[ACESSE OTHERSITE.COM] Tale of Examples 1ª a 7ª Temporada Completa [720p] [BluRay] [DUAL]/[OTHERSITE.COM] Tale of Examples 1ª Temporada Completa 2011 [720p] [DUAL]/Tale.Of.Examples.S01E02.BluRay.720p.DUAL.WWW.OTHERSITE.COM.mkv", size = 786979890L),
            ),
        ),
        Release(
            title = "Sample on Screen",
            season = 1,
            episode = 2,
            fileIdx = 3,
            filename = "[TatakaeFuniSubs] Sample on Screen - S01E02 (BD 1080p AV1) [Dual Audio] [0A1B2C3D].mkv",
            description = "[TatakaeFuniSubs] Sample on Screen S01-04 (BD 1080p) [Dual Audio] | OAD (OVA) | Example Alt Title (Full Series Batch)\nSeason 1/[TatakaeFuniSubs] Sample on Screen - S01E02 (BD 1080p AV1) [Dual Audio] [0A1B2C3D].mkv\n👤 195 💾 497.32 MB ⚙️ ExampleIndexer\nDubbed / Dual Audio",
            expectedId = 15,
            files = listOf(
                TorboxTorrentFileDto(id = 2, name = "Sample on Screen S01-04 (BD 1080p) [Dual Audio] [TatakaeFuniSubs]/Season 1/[TatakaeFuniSubs] Sample on Screen - S01E14 (BD 1080p AV1) [Dual Audio] [4E5F6A7B].mkv", size = 356036376L),
                TorboxTorrentFileDto(id = 15, name = "Sample on Screen S01-04 (BD 1080p) [Dual Audio] [TatakaeFuniSubs]/Season 1/[TatakaeFuniSubs] Sample on Screen - S01E02 (BD 1080p AV1) [Dual Audio] [0A1B2C3D].mkv", size = 521477716L),
            ),
        ),
        Release(
            title = "Demo Things",
            season = 2,
            episode = 3,
            fileIdx = 24,
            filename = "Demo.Things.S02E03.720p.WEB-DL.6CH.x264.DUAL-WWW.SITENAME.COM.mkv",
            description = "Demo Things 2017 - 2ª Temporada Completa [WEB-DL] WWW.SITENAME.COM\nDemo.Things.S02E03.720p.WEB-DL.6CH.x264.DUAL-WWW.SITENAME.COM.mkv\n👤 5 💾 879.54 MB ⚙️ ExampleIndexer\nDual Audio / 🇬🇧 / 🇵🇹",
            expectedId = 6,
            files = listOf(
                TorboxTorrentFileDto(id = 2, name = "Demo Things 2017 - 2ª Temporada Completa [WEB-DL] WWW.SITENAME.COM/SITENAME.mp4", size = 33957794L),
                TorboxTorrentFileDto(id = 6, name = "Demo Things 2017 - 2ª Temporada Completa [WEB-DL] WWW.SITENAME.COM/Demo.Things.S02E03.720p.WEB-DL.6CH.x264.DUAL-WWW.SITENAME.COM.mkv", size = 922263595L),
            ),
        ),
        Release(
            title = "Example of a Show",
            season = 1,
            episode = 2,
            fileIdx = 1,
            filename = "Example of a Show - S01E02 (BD 1080p x265 Opus) [Dual Audio] [PMR].mkv",
            description = "[PMR] Example of a Show Season 1 (S01) (BD 1080p x265 Opus) [Dual Audio] | Example Alternative Title\nExample of a Show - S01E02 (BD 1080p x265 Opus) [Dual Audio] [PMR].mkv\n👤 20 💾 4.49 GB ⚙️ ExampleIndexer\nDubbed / Dual Audio",
            expectedId = 4,
            files = listOf(
                TorboxTorrentFileDto(id = 3, name = "Example of a Show S01 (BD 1080p x265 Opus) [Dual Audio] [PMR]/Example of a Show - S01E01 (BD 1080p x265 Opus) [Dual Audio] [PMR].mkv", size = 12359794201L),
                TorboxTorrentFileDto(id = 4, name = "Example of a Show S01 (BD 1080p x265 Opus) [Dual Audio] [PMR]/Example of a Show - S01E02 (BD 1080p x265 Opus) [Dual Audio] [PMR].mkv", size = 4817912734L),
            ),
        ),
    )
}
