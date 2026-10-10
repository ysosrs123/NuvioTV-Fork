package com.nuvio.tv.core.iptv

import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class RecordingTargetsTest {
    private val now = 1_791_331_200_000L
    private val zone = ZoneId.of("Australia/Sydney")
    private val id = "0f3c9a2e-1111-2222-3333-444455556666"

    private val mounts = """
        /dev/block/dm-5 /data f2fs rw,lazytime,seclabel,nosuid,nodev 0 0
        /dev/fuse /storage/emulated fuse rw,lazytime,nosuid,nodev 0 0
        /dev/block/vold/public:8,1 /mnt/media_rw/1234-ABCD vfat rw,dirsync,nosuid,nodev,noexec,uid=1023,gid=1023 0 0
        /dev/fuse /storage/1234-ABCD fuse rw,lazytime,nosuid,nodev,noexec 0 0
        /dev/block/vold/public:8,17 /mnt/media_rw/5678-EF01 exfat rw,nosuid,nodev 0 0
        /dev/block/vold/public:8,33 /mnt/media_rw/0A1B2C3D4E5F6071 ntfs3 ro,nosuid,nodev 0 0
        /dev/block/vold/public:8,49 /mnt/media_rw/AAAA-BBBB sdfat rw 0 0
        /dev/fuse /storage/CCCC-DDDD fuse rw 0 0
        /dev/sdb1 /mnt/media_rw/EEEE-FFFF fuseblk rw,allow_other 0 0
        /dev/sdc1 /mnt/media_rw/My\040Drive tntfs rw 0 0
    """.trimIndent()

    @Test fun mountsMapToFileSystemsOfTheVolume() {
        val parsed = RecordingMounts.parse(mounts)
        assertEquals(10, parsed.size)
        assertEquals(RecordingFileSystem.FAT32, RecordingMounts.fileSystem(parsed, "/storage/1234-ABCD"))
        assertEquals(RecordingFileSystem.EXFAT, RecordingMounts.fileSystem(parsed, "/storage/5678-EF01"))
        assertEquals(RecordingFileSystem.NTFS, RecordingMounts.fileSystem(parsed, "/storage/0A1B2C3D4E5F6071"))
        assertTrue(RecordingMounts.find(parsed, "/storage/0A1B2C3D4E5F6071")!!.readOnly)
        assertFalse(RecordingMounts.find(parsed, "/storage/1234-ABCD")!!.readOnly)
        assertEquals(RecordingFileSystem.UNKNOWN, RecordingMounts.fileSystem(parsed, "/storage/AAAA-BBBB"))
        assertEquals(RecordingFileSystem.UNKNOWN, RecordingMounts.fileSystem(parsed, "/storage/CCCC-DDDD"))
        assertEquals("fuse", RecordingMounts.find(parsed, "/storage/CCCC-DDDD")!!.type)
        assertEquals(RecordingFileSystem.LARGE, RecordingMounts.fileSystem(parsed, "/storage/EEEE-FFFF"))
        assertEquals(RecordingFileSystem.NTFS, RecordingMounts.fileSystem(parsed, "/storage/My Drive"))
        assertEquals(RecordingFileSystem.UNKNOWN, RecordingMounts.fileSystem(parsed, "/storage/9999-9999"))
        assertEquals(RecordingFileSystem.EXFAT, RecordingMounts.type("texfat"))
        assertEquals(RecordingFileSystem.NTFS, RecordingMounts.type("NTFS"))
        assertEquals(RecordingFileSystem.LARGE, RecordingMounts.type("ufsd"))
        assertFalse(RecordingFileSystem.FAT32.largeFiles)
        assertFalse(RecordingFileSystem.UNKNOWN.largeFiles)
        assertTrue(RecordingMounts.parse("garbage\n\n").isEmpty())
    }

    @Test fun volumeRootComesFromTheAppFolder() {
        assertEquals("/storage/1234-ABCD", RecordingMounts.volumeRoot("/storage/1234-ABCD/Android/data/com.nuvio.tv/files"))
        assertEquals("/mnt/media_rw/1234-ABCD", RecordingMounts.volumeRoot("/mnt/media_rw/1234-ABCD/Android/data/x/files/recordings/"))
        assertNull(RecordingMounts.volumeRoot("/data/user/0/com.nuvio.tv/files"))
        assertNull(RecordingMounts.volumeRoot("/Android/data/x"))
    }

    @Test fun partsAreNamedFromTheMainFile() {
        val main = "News - Late - 07-Oct-26 2200.ts"
        assertEquals(main, RecordingParts.name(main, 1))
        assertEquals("News - Late - 07-Oct-26 2200 (part 2).ts", RecordingParts.name(main, 2))
        assertEquals("News - Late - 07-Oct-26 2200 (part 12).ts", RecordingParts.name(main, 12))
        assertEquals("raw (part 3)", RecordingParts.name("raw", 3))
        try { RecordingParts.name(main, 0); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(RecordingParts.FAT_PART_BYTES, RecordingParts.partBytes(RecordingFileSystem.FAT32))
        assertEquals(RecordingParts.FAT_PART_BYTES, RecordingParts.partBytes(RecordingFileSystem.UNKNOWN))
        assertEquals(Long.MAX_VALUE, RecordingParts.partBytes(RecordingFileSystem.EXFAT))
        assertTrue(RecordingParts.FAT_PART_BYTES < 4L * 1024 * 1024 * 1024 - RecordingParts.SEGMENT_HEADROOM_BYTES)
    }

    @Test fun splitsLandOnPacketBoundariesAndOnlyBetweenSegments() {
        val limit = 188L * 10 + 50
        assertEquals(100, RecordingParts.fit(0, 100, limit))
        assertEquals(188 * 10 - 1000, RecordingParts.fit(1000, 1000, limit))
        assertEquals(0, RecordingParts.fit(188L * 10, 500, limit))
        assertEquals(500, RecordingParts.fit(Long.MAX_VALUE / 2, 500, Long.MAX_VALUE))
        assertEquals(940 - 900, RecordingParts.fit(900, 2000, 1000))
        assertFalse(RecordingParts.rollBeforeSegment(0, 1000, 0))
        assertTrue(RecordingParts.rollBeforeSegment(1000, 1000, 0))
        assertFalse(RecordingParts.rollBeforeSegment(999, 1000, 0))
        assertTrue(RecordingParts.rollBeforeSegment(RecordingParts.FAT_PART_BYTES - RecordingParts.SEGMENT_HEADROOM_BYTES, RecordingParts.FAT_PART_BYTES,
            RecordingParts.SEGMENT_HEADROOM_BYTES))
        assertFalse(RecordingParts.rollBeforeSegment(Long.MAX_VALUE / 2, Long.MAX_VALUE, RecordingParts.SEGMENT_HEADROOM_BYTES))
        assertTrue(RecordingParts.tooLarge("write failed: EFBIG (File too large)"))
        assertTrue(RecordingParts.tooLarge("File too large"))
        assertFalse(RecordingParts.tooLarge("No space left on device"))
        assertFalse(RecordingParts.tooLarge(null))
    }

    @Test fun uploadResumesFromRemoteSize() {
        val pieces = listOf(RecordingPiece(0, 100), RecordingPiece(100, 50))
        assertEquals(RecordingUploadStep.Write(0, 0, 64), RecordingUpload.next(pieces, 0, 150, false, 64))
        assertEquals(RecordingUploadStep.Write(0, 64, 36), RecordingUpload.next(pieces, 64, 150, false, 64))
        assertEquals(RecordingUploadStep.Write(1, 20, 10), RecordingUpload.next(pieces, 120, 130, false, 64))
        assertEquals(RecordingUploadStep.Wait, RecordingUpload.next(pieces, 130, 130, false, 64))
        assertEquals(RecordingUploadStep.Done, RecordingUpload.next(pieces, 150, 150, true, 64))
        assertEquals(RecordingUploadStep.Done, RecordingUpload.next(pieces, 150, Long.MAX_VALUE, true, 64))
        assertEquals(RecordingUploadStep.Write(0, 0, 50), RecordingUpload.next(pieces.reversed(), 100, Long.MAX_VALUE, true, 64))
        assertEquals(RecordingUploadStep.Lost, RecordingUpload.next(listOf(RecordingPiece(100, 50)), 40, 150, true, 64))
        assertEquals(RecordingUploadStep.Lost, RecordingUpload.next(listOf(RecordingPiece(0, 10), RecordingPiece(20, 10)), 15, 30, true, 64))
        assertEquals(RecordingUploadStep.Truncate(150), RecordingUpload.next(pieces, 160, 150, true, 64))
        assertEquals(RecordingUploadStep.Done, RecordingUpload.next(emptyList(), 150, Long.MAX_VALUE, true, 64))
        assertEquals(RecordingUploadStep.Wait, RecordingUpload.next(emptyList(), 0, 0, false, 64))
    }

    @Test fun onlyConfirmedClosedPiecesAreRemoved() {
        val pieces = listOf(RecordingPiece(0, 100), RecordingPiece(100, 50))
        assertEquals(emptyList<Int>(), RecordingUpload.removable(pieces, 99, false))
        assertEquals(listOf(0), RecordingUpload.removable(pieces, 100, false))
        assertEquals(listOf(0), RecordingUpload.removable(pieces, 150, false))
        assertEquals(listOf(0, 1), RecordingUpload.removable(pieces, 150, true))
        assertEquals("0000268435456.ts", RecordingUpload.spoolName(RecordingParts.SPOOL_PART_BYTES))
        assertEquals(RecordingParts.SPOOL_PART_BYTES, RecordingUpload.spoolStart("0000268435456.ts"))
        assertNull(RecordingUpload.spoolStart("0000268435456.ts.part"))
        assertNull(RecordingUpload.spoolStart("abc.ts"))
        assertEquals(2_000L, RecordingUpload.retryMillis(0))
        assertEquals(60_000L, RecordingUpload.retryMillis(99))
    }

    @Test fun shareAddressesAreParsedAndValidated() {
        assertEquals(RecordingShareTarget("nas.local", null, "recordings", ""), RecordingShareAddress.parse(" nas.local ", "recordings", ""))
        assertEquals(RecordingShareTarget("192.168.1.20", 4450, "tv", "Live/News"), RecordingShareAddress.parse("192.168.1.20:4450", "tv", "\\Live\\News\\"))
        assertEquals(RecordingShareTarget("nas", null, "media", "TV/Live"), RecordingShareAddress.parse("smb://nas/media/TV", "", "Live"))
        assertEquals(RecordingShareTarget("NAS", null, "media", "TV"), RecordingShareAddress.parse("\\\\NAS\\media\\TV", "", ""))
        assertEquals(RecordingShareTarget("fe80::1", 445, "s", ""), RecordingShareAddress.parse("[fe80::1]:445", "s", ""))
        assertEquals(RecordingShareTarget("fd00::20", null, "s", ""), RecordingShareAddress.parse("fd00::20", "s", ""))
        assertEquals(RecordingShareTarget("nas", null, "s", "a/b"), RecordingShareAddress.parse("nas", "s", "./a//b/"))
        assertEquals("a/b/x.ts", RecordingShareTarget("nas", null, "s", "a/b").path("x.ts"))
        assertEquals("x.ts", RecordingShareTarget("nas", null, "s", "").path("x.ts"))
        assertNull(RecordingShareAddress.parse("", "s", ""))
        assertNull(RecordingShareAddress.parse("nas", "", ""))
        assertNull(RecordingShareAddress.parse("nas:0", "s", ""))
        assertNull(RecordingShareAddress.parse("nas:70000", "s", ""))
        assertNull(RecordingShareAddress.parse("nas:x", "s", ""))
        assertNull(RecordingShareAddress.parse("na s", "s", ""))
        assertNull(RecordingShareAddress.parse("nas", "s*", ""))
        assertNull(RecordingShareAddress.parse("nas", "s", "a/../b"))
        assertNull(RecordingShareAddress.parse("nas", "s", "a:b"))
        assertNull(RecordingShareAddress.parse("nas", "s", "trailing."))
        assertNull(RecordingShareAddress.parse("[fe80::1", "s", ""))
        assertFalse(RecordingShareTarget("secret-host", null, "s", "").toString().contains("secret"))
    }

    @Test fun locationValuesRoundTrip() {
        assertEquals("volume:1234-ABCD", RecordingLocations.volume("1234-ABCD"))
        assertEquals("1234-ABCD", RecordingLocations.volumeId("volume:1234-ABCD"))
        assertNull(RecordingLocations.volumeId("volume:../x"))
        assertNull(RecordingLocations.volumeId(RecordingLocations.INTERNAL))
        assertEquals("ab12cd34", RecordingLocations.shareId(RecordingLocations.share("ab12cd34")))
        assertNull(RecordingLocations.shareId(RecordingLocations.SHARE))
        assertTrue(RecordingLocations.valid(null))
        assertTrue(RecordingLocations.valid("share:ab12cd34"))
        assertFalse(RecordingLocations.valid("internal"))
        assertFalse(RecordingLocations.valid("volume:"))
        try { RecordingLocations.volume("a/b"); fail() } catch (_: IllegalArgumentException) { }
    }

    private fun name(channel: String, title: String?) = RecordingFiles.name(channel, title, now, zone)

    private fun assertPortable(name: String) {
        assertTrue(name, name.length <= RecordingFiles.MAX_NAME_CHARS)
        assertTrue(name, name.toByteArray(Charsets.UTF_8).size <= RecordingFiles.MAX_NAME_BYTES)
        assertTrue(name, name.none { it < ' ' || it in "<>:\"/\\|?*" })
        assertFalse(name, name.endsWith(".") || name.endsWith(" ") || name.startsWith(".") || name.startsWith(" "))
        assertTrue(name, name.endsWith("07-Oct-26 1100.ts"))
        val stem = name.substringBefore('.').trimEnd().uppercase()
        assertFalse(name, stem in setOf("CON", "PRN", "AUX", "NUL") || stem.matches(Regex("(COM|LPT)[1-9]")))
        for (index in listOf(2, 99)) {
            val part = RecordingFiles.partial(RecordingParts.name(name, index))
            assertTrue(part, part.toByteArray(Charsets.UTF_8).size <= 240)
        }
    }

    @Test fun fileNamesArePortableAcrossFat32ExfatNtfsAndSmb() {
        assertEquals("UK BBC One HD - News at Six Special live - 07-Oct-26 1100.ts",
            name("UK: BBC One/HD", "News at Six: \"Special\" <live>").also(::assertPortable))
        assertEquals("Sport 1 - Final Win - 07-Oct-26 1100.ts", name("Sport 1 🏆", "Final 🔥 Win").also(::assertPortable))
        assertEquals("_AUX.1 - 07-Oct-26 1100.ts", name("AUX.1", null).also(::assertPortable))
        assertEquals("AUX - 07-Oct-26 1100.ts", name("AUX", null).also(::assertPortable))
        assertEquals("_com1.x - 07-Oct-26 1100.ts", name("com1.x", "").also(::assertPortable))
        assertEquals("Movies - The End - 07-Oct-26 1100.ts", name("Movies...", "The End... ").also(::assertPortable))
        assertEquals("a b c - 07-Oct-26 1100.ts", name("a/b\\c", null).also(::assertPortable))
        assertEquals("Tab Line - 07-Oct-26 1100.ts", name("Tab\tLine\u0000", null).also(::assertPortable))
        assertEquals("الجزيرة - نشرة الأخبار - 07-Oct-26 1100.ts", name("الجزيرة", "نشرة الأخبار").also(::assertPortable))
        assertEquals("中央电视台 - 新闻联播 - 07-Oct-26 1100.ts", name("中央电视台", "新闻联播").also(::assertPortable))
        assertEquals("हिन्दी समाचार - 07-Oct-26 1100.ts", name("हिन्दी समाचार", null).also(::assertPortable))
        assertEquals("Café".let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFC) } + " - 07-Oct-26 1100.ts",
            name("Café", null).also(::assertPortable))
        assertPortable(name("x".repeat(500), "y".repeat(500)))
        assertPortable(name("频道".repeat(100), "节目名称".repeat(100)))
        assertPortable(name("قناة".repeat(80), "برنامج".repeat(80)))
        assertPortable(name("😀".repeat(100), "...".repeat(50)))
        assertTrue(name("Channel", "t".repeat(500)).startsWith("Channel - ttt"))
        val wide = name("頻道".repeat(30), "節目".repeat(60))
        assertTrue(wide.startsWith("頻道頻道") && wide.contains(" - 節目"))
        assertEquals("07-Oct-26 1100.ts", name("///", null))
    }
}
