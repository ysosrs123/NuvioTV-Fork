package com.nuvio.tv.core.player.thumbnail

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Stores of one title are found together, keep their duration and verdicts, and take pictures without a re-decode. */
class ThumbStoreReuseTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun slotUs(slot: Int) = slot * 10_000_000L

    @Test fun releasesOfOneTitleShareTheKeyPrefix() {
        val a = ThumbStore.keyFor("tt0111161", 4_000_000_000L)
        val b = ThumbStore.keyFor("tt0111161", 4_100_000_000L)
        val c = ThumbStore.keyFor("tt0068646", 4_000_000_000L)
        val prefix = ThumbStore.identityPrefix("tt0111161")
        assertTrue(a.startsWith(prefix) && b.startsWith(prefix))
        assertFalse(c.startsWith(prefix))
        assertTrue(a != b)
        assertEquals(24, a.length)
    }

    @Test fun siblingsAreFoundWithTheirDurationAndPictureCount() {
        val dir = tmp.newFolder("store")
        val id = "tt0111161"
        val own = ThumbStore.keyFor(id, 1_000L)
        val other = ThumbStore.keyFor(id, 2_000L)
        val unrelated = ThumbStore.keyFor("tt0068646", 2_000L)
        ThumbStore.openIn(dir, own, memoryOnly = false).apply {
            noteDuration(8_520_000L)
            putCompressed(listOf(ThumbStore.Compressed(longArrayOf(slotUs(1)), 10_000_000L, byteArrayOf(1))))
        }
        ThumbStore.openIn(dir, other, memoryOnly = false).apply {
            noteDuration(8_521_500L)
            putCompressed((0 until 5).map { ThumbStore.Compressed(longArrayOf(slotUs(it)), it * 10_000_000L, byteArrayOf(it.toByte())) })
        }
        ThumbStore.openIn(dir, unrelated, memoryOnly = false).apply {
            noteDuration(8_520_000L)
            putCompressed(listOf(ThumbStore.Compressed(longArrayOf(slotUs(1)), 10_000_000L, byteArrayOf(1))))
        }
        val found = ThumbStore.siblingsIn(dir, id, exceptKey = own)
        assertEquals(1, found.size)
        assertEquals(other, found[0].key)
        assertEquals(8_521_500L, found[0].durationMs)
        assertEquals(5, found[0].pictures)
    }

    @Test fun compressedPicturesAreCopiedAsTheyAre() {
        val dir = tmp.newFolder("store")
        val webpA = ByteArray(700) { (it * 7).toByte() }
        val webpB = ByteArray(300) { (it * 3).toByte() }
        val store = ThumbStore.openIn(dir, "copy", memoryOnly = false)
        assertTrue(store.putCompressed(listOf(
            ThumbStore.Compressed(longArrayOf(slotUs(4), slotUs(5)), 47_000_000L, webpA),
            ThumbStore.Compressed(longArrayOf(slotUs(9)), 92_000_000L, webpB),
        )))
        val reopened = ThumbStore.openIn(dir, "copy", memoryOnly = false)
        assertEquals(setOf(slotUs(4), slotUs(5), slotUs(9)), reopened.slots())
        assertArrayEquals(webpA, reopened.compressed(slotUs(5)))
        assertArrayEquals(webpB, reopened.compressed(slotUs(9)))
        assertEquals(47_000_000L, reopened.keyframePtsFor(slotUs(4)))
        assertEquals(1_000L, java.io.File(dir, "copy.dat").length())
    }

    @Test fun chunkedCopiesWriteTheIndexOnceAtTheEnd() {
        val dir = tmp.newFolder("store")
        val store = ThumbStore.openIn(dir, "chunks", memoryOnly = false)
        for (c in 0 until 3) {
            assertTrue(store.putCompressed(listOf(ThumbStore.Compressed(longArrayOf(slotUs(c)), c * 10_000_000L, byteArrayOf(c.toByte()))),
                indexNow = false))
        }
        assertFalse(java.io.File(dir, "chunks.idx").exists())
        store.flush()
        assertEquals(3, ThumbStore.openIn(dir, "chunks", memoryOnly = false).slots().size)
    }

    @Test fun durationAndVerdictsSurviveAReopen() {
        val dir = tmp.newFolder("store")
        val store = ThumbStore.openIn(dir, "meta", memoryOnly = false)
        store.noteDuration(5_400_000L)
        store.noteVerdict("aaaa", true)
        store.noteVerdict("bbbb", false)
        store.setKeyframeIndex(ByteArray(64) { it.toByte() })
        store.flush()
        val reopened = ThumbStore.openIn(dir, "meta", memoryOnly = false)
        assertEquals(5_400_000L, reopened.durationMs())
        assertEquals(true, reopened.verdictOn("aaaa"))
        assertEquals(false, reopened.verdictOn("bbbb"))
        assertNull(reopened.verdictOn("cccc"))
        assertEquals(64, reopened.cachedKeyframeIndex()?.size)
    }

    /** Last-used time and watched share as the index file has them. */
    private fun savedUse(dir: java.io.File, key: String): Pair<Long, Float> =
        java.io.DataInputStream(java.io.File(dir, "$key.idx").inputStream()).use { s ->
            s.readInt()
            s.readInt()
            s.readLong() to s.readFloat()
        }

    private fun onePicture() = listOf(ThumbStore.Compressed(longArrayOf(slotUs(1)), 10_000_000L, byteArrayOf(1)))

    @Test fun aSessionThatOnlyReadsSavesItsDateAndWatchedShare() {
        val dir = tmp.newFolder("store")
        ThumbStore.openIn(dir, "used", memoryOnly = false).putCompressed(onePicture())
        assertEquals(0f, savedUse(dir, "used").second, 0f)
        val before = System.currentTimeMillis()
        ThumbStore.openIn(dir, "used", memoryOnly = false).apply {
            noteUsed(0.5f)
            flush()
        }
        val (usedAt, watched) = savedUse(dir, "used")
        assertEquals(0.5f, watched, 0f)
        assertTrue(usedAt >= before)
    }

    @Test fun theLargerWatchedShareIsKept() {
        val dir = tmp.newFolder("store")
        ThumbStore.openIn(dir, "share", memoryOnly = false).apply {
            putCompressed(onePicture())
            noteUsed(0.6f)
            flush()
        }
        ThumbStore.openIn(dir, "share", memoryOnly = false).apply {
            noteUsed(0f)
            flush()
        }
        assertEquals(0.6f, savedUse(dir, "share").second, 0f)
        ThumbStore.openIn(dir, "share", memoryOnly = false).apply {
            noteUsed(0.95f)
            flush()
        }
        assertEquals(0.95f, savedUse(dir, "share").second, 0f)
    }

    @Test fun aStoreWithoutPicturesIsNotWrittenForItsDate() {
        val dir = tmp.newFolder("store")
        ThumbStore.openIn(dir, "empty", memoryOnly = false).apply {
            noteUsed(0.4f)
            flush()
        }
        assertFalse(java.io.File(dir, "empty.idx").exists())
    }

    @Test fun aMemoryOnlyStoreTakesNoCopies() {
        val dir = tmp.newFolder("store")
        val store = ThumbStore.openIn(dir, "mem", memoryOnly = true)
        assertFalse(store.putCompressed(listOf(ThumbStore.Compressed(longArrayOf(slotUs(1)), 0L, byteArrayOf(1)))))
        assertFalse(store.has(slotUs(1)))
        assertEquals(0, dir.listFiles()!!.size)
    }
}
