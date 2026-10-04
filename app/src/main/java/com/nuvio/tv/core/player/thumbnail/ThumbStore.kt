package com.nuvio.tv.core.player.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.StatFs
import android.os.storage.StorageManager
import android.util.Log
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Per-title thumbnail store. `<key>.dat` holds appended WebP frames, `<key>.idx` the slot table, retention metadata
 * and the cached keyframe index, rewritten atomically (temp + rename). Keyed by content identity + file byte length,
 * so URL rotation does not matter and releases of the same title never mix.
 */
internal class ThumbStore private constructor(
    private val dir: File,
    val key: String,
    private val rgb565: Boolean,
) {
    companion object {
        private const val TAG = "ThumbStore"
        const val DIR = "seek_thumbs_v3"
        private const val LEGACY_DIR = "seek_thumbs"
        private const val MAGIC = 0x4E545333            // "NTS3"
        private const val FORMAT = 1
        private const val WEBP_QUALITY = 70
        /** Memory LRU size in frames. */
        const val MEM_FRAMES = 48
        private const val MAX_BUDGET_BYTES = 64L * 1024 * 1024
        private const val MEMORY_ONLY_BELOW_FREE_BYTES = 1024L * 1024 * 1024
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val FLUSH_EVERY_FRAMES = 10
        private const val FLUSH_EVERY_MS = 5_000L

        fun keyFor(identity: String, fileLength: Long): String =
            MessageDigest.getInstance("SHA-1").digest("$identity|$fileLength|$FORMAT".toByteArray())
                .joinToString("") { "%02x".format(it) }.take(24)

        fun open(context: Context, identity: String, fileLength: Long, rgb565: Boolean): ThumbStore {
            val dir = File(context.applicationContext.cacheDir, DIR).apply { mkdirs() }
            return ThumbStore(dir, keyFor(identity, fileLength), rgb565).also { it.load() }
        }

        /** Deletes every stored thumbnail, including the legacy JPEG cache. */
        fun clearAll(context: Context): Boolean {
            val cache = context.applicationContext.cacheDir
            val a = File(cache, DIR).let { !it.exists() || it.deleteRecursively() }
            val b = File(cache, LEGACY_DIR).let { !it.exists() || it.deleteRecursively() }
            return a && b
        }

        /** Stores being written outside a session, housekeeping skips them. Reference-counted per key. */
        private val busy = java.util.concurrent.ConcurrentHashMap<String, Int>()

        fun markBusy(key: String) {
            busy.merge(key, 1, Int::plus)
        }

        fun unmarkBusy(key: String) {
            busy.computeIfPresent(key) { _, n -> if (n <= 1) null else n - 1 }
        }

        private fun isBusy(key: String): Boolean = busy.containsKey(key)

        /** Drops the legacy cache, expired titles and the least-recently-used titles above the budget. */
        fun housekeeping(context: Context, activeKey: String? = null) {
            val cache = context.applicationContext.cacheDir
            File(cache, LEGACY_DIR).takeIf { it.exists() }?.deleteRecursively()
            val dir = File(cache, DIR)
            val titles = dir.listFiles { f -> f.name.endsWith(".idx") }?.mapNotNull { idx ->
                val key = idx.name.removeSuffix(".idx")
                val dat = File(dir, "$key.dat")
                val meta = runCatching { readMeta(idx) }.getOrNull()
                Triple(key, meta, idx.length() + dat.length())
            } ?: return
            val now = System.currentTimeMillis()
            var total = titles.sumOf { it.third }
            val budget = budgetBytes(context)
            for ((key, meta, size) in titles) {
                if (key == activeKey || isBusy(key)) continue
                val expired = meta == null || now - meta.lastAccessMs > retentionMs(meta.watchedFraction)
                if (expired) {
                    delete(dir, key)
                    total -= size
                }
            }
            if (total > budget) {
                for ((key, meta, size) in titles.sortedBy { it.second?.lastAccessMs ?: 0L }) {
                    if (total <= budget) break
                    if (key == activeKey || isBusy(key) || !File(dir, "$key.idx").exists()) continue
                    delete(dir, key)
                    total -= size
                }
            }
            // orphaned data and temp files
            dir.listFiles()?.forEach { f ->
                val k = f.name.substringBefore('.')
                if (isBusy(k) || k == activeKey) return@forEach
                if (f.name.endsWith(".tmp") ||
                    ((f.name.endsWith(".dat") || f.name.endsWith(".done")) && !File(dir, "$k.idx").exists())) f.delete()
            }
        }

        fun totalBytes(context: Context): Long =
            File(context.applicationContext.cacheDir, DIR).listFiles()?.sumOf { it.length() } ?: 0L

        /** Watched 7 days, in progress 30 days, abandoned 3 days. */
        internal fun retentionMs(watchedFraction: Float): Long = when {
            watchedFraction >= 0.9f -> 7 * DAY_MS
            watchedFraction < 0.05f -> 3 * DAY_MS
            else -> 30 * DAY_MS
        }

        /** min(64 MB, 5 % of free space, 1/2 cache quota). */
        fun budgetBytes(context: Context): Long {
            val cache = context.applicationContext.cacheDir
            val free = runCatching { StatFs(cache.absolutePath).availableBytes }.getOrDefault(0L)
            var b = minOf(MAX_BUDGET_BYTES, free / 20)
            if (Build.VERSION.SDK_INT >= 26) {
                runCatching {
                    val sm = context.getSystemService(StorageManager::class.java)
                    val quota = sm.getCacheQuotaBytes(sm.getUuidForPath(cache))
                    if (quota > 0) b = minOf(b, quota / 2)
                }
            }
            return b.coerceAtLeast(0L)
        }

        private fun delete(dir: File, key: String) {
            File(dir, "$key.idx").delete()
            File(dir, "$key.dat").delete()
            File(dir, "$key.done").delete()
        }

        /** Every coverage thumbnail of this release is stored. Checked without opening the store or the network. */
        fun isMarkedComplete(context: Context, identity: String, fileLength: Long): Boolean {
            val dir = File(context.applicationContext.cacheDir, DIR)
            val key = keyFor(identity, fileLength)
            return File(dir, "$key.done").exists() && File(dir, "$key.idx").exists()
        }

        fun exists(context: Context, identity: String, fileLength: Long): Boolean =
            File(File(context.applicationContext.cacheDir, DIR), "${keyFor(identity, fileLength)}.idx").exists()

        private class Meta(val lastAccessMs: Long, val watchedFraction: Float)

        private fun readMeta(idx: File): Meta = DataInputStream(idx.inputStream().buffered()).use { s ->
            if (s.readInt() != MAGIC || s.readInt() != FORMAT) throw IllegalStateException("bad idx")
            Meta(lastAccessMs = s.readLong(), watchedFraction = s.readFloat())
        }
    }

    /** Frame location in .dat. */
    private class Entry(val offset: Long, val length: Int, val keyframePtsUs: Long)

    private val entries = HashMap<Long, Entry>()
    private val mem = LruCache<Long, Bitmap>(MEM_FRAMES)
    private var lastAccessMs = System.currentTimeMillis()
    @Volatile var watchedFraction = 0f
    private var cachedIndex: ByteArray? = null
    private var dirty = 0
    private var lastFlushMs = 0L
    private var memoryOnly = false
    /** Storage is nearly full, frames stay in memory only. */
    val isMemoryOnly: Boolean get() = synchronized(this) { memoryOnly }
    private val idxFile get() = File(dir, "$key.idx")
    private val datFile get() = File(dir, "$key.dat")

    @Synchronized
    private fun load() {
        memoryOnly = runCatching { StatFs(dir.absolutePath).availableBytes < MEMORY_ONLY_BELOW_FREE_BYTES }.getOrDefault(true)
        if (!idxFile.exists()) return
        runCatching {
            val datLen = datFile.length()
            DataInputStream(idxFile.inputStream().buffered()).use { s ->
                if (s.readInt() != MAGIC || s.readInt() != FORMAT) return@use
                s.readLong()                       // last access
                watchedFraction = s.readFloat()
                val n = s.readInt()
                repeat(n) {
                    val slot = s.readLong()
                    val e = Entry(s.readLong(), s.readInt(), s.readLong())
                    if (e.offset + e.length <= datLen) entries[slot] = e   // drop entries beyond a torn append
                }
                val idxLen = s.readInt()
                if (idxLen in 1..(8 * 1024 * 1024)) cachedIndex = ByteArray(idxLen).also { s.readFully(it) }
            }
        }.onFailure {
            Log.w(TAG, "discarding unreadable store $key: ${it.javaClass.simpleName}")
            entries.clear()
            idxFile.delete()
            datFile.delete()
        }
    }

    @Synchronized fun has(slotUs: Long): Boolean = entries.containsKey(slotUs) || mem.get(slotUs) != null
    @Synchronized fun memGet(slotUs: Long): Bitmap? = mem.get(slotUs)
    @Synchronized fun slots(): Set<Long> = entries.keys.toSet()
    @Synchronized fun isEmpty(): Boolean = entries.isEmpty() && mem.size() == 0
    @Synchronized fun keyframePtsFor(slotUs: Long): Long? = entries[slotUs]?.keyframePtsUs

    /** Serialised with [KeyframeIndexCodec]. */
    @Synchronized fun cachedKeyframeIndex(): ByteArray? = cachedIndex
    @Synchronized fun setKeyframeIndex(bytes: ByteArray) { cachedIndex = bytes; dirty++ }

    /** Disk to memory. IO thread. */
    fun load(slotUs: Long): Bitmap? {
        val e = synchronized(this) { mem.get(slotUs)?.let { return it }; entries[slotUs] } ?: return null
        val bytes = runCatching {
            RandomAccessFile(datFile, "r").use { f ->
                ByteArray(e.length).also { f.seek(e.offset); f.readFully(it) }
            }
        }.getOrNull() ?: return null
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = if (rgb565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        synchronized(this) {
            mem.put(slotUs, bmp)
            lastAccessMs = System.currentTimeMillis()
        }
        return bmp
    }

    /** Stores one thumbnail for every slot it serves: one WebP append shared by all [slotsUs]. IO thread. */
    fun put(slotsUs: LongArray, keyframePtsUs: Long, bmp: Bitmap) {
        synchronized(this) { for (s in slotsUs) mem.put(s, bmp) }
        if (memoryOnly) return
        val webp = ByteArrayOutputStream(8 * 1024).also {
            @Suppress("DEPRECATION")
            val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            bmp.compress(fmt, WEBP_QUALITY, it)
        }.toByteArray()
        synchronized(this) {
            val offset = datFile.length()
            runCatching { datFile.appendBytes(webp) }.onFailure { return }
            val entry = Entry(offset, webp.size, keyframePtsUs)
            for (s in slotsUs) entries[s] = entry
            lastAccessMs = System.currentTimeMillis()
            dirty++
            val now = System.currentTimeMillis()
            if (dirty >= FLUSH_EVERY_FRAMES || now - lastFlushMs >= FLUSH_EVERY_MS) flushLocked()
        }
    }

    /** See [isMarkedComplete]. */
    fun markComplete() {
        if (memoryOnly) return
        runCatching { File(dir, "$key.done").createNewFile() }
    }

    @Synchronized fun flush() {
        if (dirty > 0) flushLocked()
    }

    @Synchronized fun trimMemory(keep: Int) = mem.trimToSize(keep.coerceAtLeast(0))

    private fun flushLocked() {
        if (memoryOnly) return
        val tmp = File(dir, "$key.idx.tmp")
        runCatching {
            DataOutputStream(tmp.outputStream().buffered()).use { s ->
                s.writeInt(MAGIC)
                s.writeInt(FORMAT)
                s.writeLong(lastAccessMs)
                s.writeFloat(watchedFraction)
                s.writeInt(entries.size)
                for ((slot, e) in entries) {
                    s.writeLong(slot)
                    s.writeLong(e.offset)
                    s.writeInt(e.length)
                    s.writeLong(e.keyframePtsUs)
                }
                val idx = cachedIndex
                s.writeInt(idx?.size ?: 0)
                if (idx != null) s.write(idx)
            }
            if (!tmp.renameTo(idxFile)) {
                idxFile.delete()
                tmp.renameTo(idxFile)
            }
            dirty = 0
            lastFlushMs = System.currentTimeMillis()
        }.onFailure { Log.w(TAG, "index flush failed: ${it.javaClass.simpleName}") }
    }
}

/** Compact serialisation of a [MediaIndex] so a re-opened title needs no index reads. */
internal object KeyframeIndexCodec {
    /** A cached index with another version is discarded and read again. */
    private const val VERSION = 2

    fun encode(m: MediaIndex): ByteArray {
        val bo = ByteArrayOutputStream()
        DataOutputStream(bo).use { s ->
            s.writeInt(VERSION)
            s.writeInt(m.kind.ordinal)
            s.writeLong(m.fileLength)
            s.writeLong(m.durationUs)
            s.writeLong(m.segmentDataStart)
            s.writeLong(m.timecodeScaleNs)
            val v = m.video
            s.writeInt(v.codec.ordinal); s.writeInt(v.width); s.writeInt(v.height); s.writeDouble(v.displayAspect)
            s.writeInt(v.nalLengthSize); s.writeInt(v.parameterSetsAnnexB.size); s.write(v.parameterSetsAnnexB)
            s.writeInt(v.bitDepth); s.writeLong(v.trackNumber)
            val c = v.colour
            s.writeInt(c.transfer); s.writeInt(c.primaries); s.writeInt(c.matrix); s.writeInt(c.range)
            s.writeInt(c.maxCll); s.writeInt(c.masteringMaxNits)
            val dv = v.dolbyVision
            s.writeBoolean(dv != null)
            if (dv != null) { s.writeInt(dv.profile); s.writeInt(dv.blCompatId); s.writeBoolean(dv.elPresent) }
            s.writeInt(v.extradata.size); s.write(v.extradata)
            val k = m.keyframes
            s.writeInt(k.count)
            for (i in 0 until k.count) {
                s.writeLong(k.ptsUs[i]); s.writeLong(k.offset[i]); s.writeLong(k.relPos[i]); s.writeInt(k.size[i])
            }
        }
        return bo.toByteArray()
    }

    fun decode(b: ByteArray): MediaIndex? = runCatching {
        DataInputStream(b.inputStream()).use { s ->
            if (s.readInt() != VERSION) return null
            val kind = ContainerKind.values()[s.readInt()]
            val fileLength = s.readLong()
            val duration = s.readLong()
            val segStart = s.readLong()
            val tcScale = s.readLong()
            val codec = VideoCodec.values()[s.readInt()]
            val w = s.readInt(); val h = s.readInt(); val aspect = s.readDouble()
            val nal = s.readInt(); val ps = ByteArray(s.readInt()).also { s.readFully(it) }
            val bitDepth = s.readInt(); val track = s.readLong()
            val colour = ContainerColour(s.readInt(), s.readInt(), s.readInt(), s.readInt(), s.readInt(), s.readInt())
            val dv = if (s.readBoolean()) DolbyVisionConfig(s.readInt(), s.readInt(), s.readBoolean()) else null
            val extradata = ByteArray(s.readInt()).also { s.readFully(it) }
            val n = s.readInt()
            val pts = LongArray(n); val off = LongArray(n); val rel = LongArray(n); val size = IntArray(n)
            for (i in 0 until n) { pts[i] = s.readLong(); off[i] = s.readLong(); rel[i] = s.readLong(); size[i] = s.readInt() }
            MediaIndex(
                kind, fileLength, duration,
                VideoTrack(codec, w, h, aspect, nal, ps, bitDepth, colour, dv, track, extradata),
                KeyframeIndex(pts, off, rel, size), segStart, tcScale,
            )
        }
    }.getOrNull()
}
