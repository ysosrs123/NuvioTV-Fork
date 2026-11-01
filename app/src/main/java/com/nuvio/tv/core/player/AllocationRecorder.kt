package com.nuvio.tv.core.player

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import java.io.File
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Test builds only: records every Java allocation of the process between two 10 s BUFFER lines and logs where the
 * bytes came from, so a heap churn can be named without a debugger. Off unless
 * `adb shell setprop debug.nuvio.alloctrack 1` before the film starts; the property is cleared by a reboot.
 * Uses the runtime's own allocation tracker (the one the IDE's legacy tracker reads), which slows allocation while on.
 */
internal object AllocationRecorder {
    const val PROPERTY = "debug.nuvio.alloctrack"
    private const val TAG = "AllocTrack"
    private const val MB = 1024L * 1024L
    private const val TOP_SITES = 8
    private const val TOP_CLASSES = 5

    @Volatile private var checked = false
    @Volatile private var tracking = false
    private var enable: Method? = null
    private var dump: Method? = null
    private var dir: File? = null
    private var dumps = 0

    /** Called with every BUFFER line, off the main thread. The first call switches the tracker on when the property says so. */
    @Synchronized
    fun sample(context: Context, positionS: Long) {
        if (!checked) {
            checked = true
            if (property() != "1") return
            start(context)
            return
        }
        if (!tracking) return
        val bytes = runCatching { dump?.invoke(null) as? ByteArray }.getOrNull()
        if (bytes == null) {
            Log.w(TAG, "allocations unreadable, tracker off")
            stop()
            return
        }
        // Clear the table for the next window.
        runCatching { enable?.invoke(null, false); enable?.invoke(null, true) }
        dumps++
        val file = dir?.let { File(it, "alloc-${dumps.toString().padStart(3, '0')}-${positionS}s.bin") }
        runCatching { file?.writeBytes(bytes) }
        val report = runCatching { summarise(bytes) }.getOrElse { "unparsed (${it.javaClass.simpleName}: ${it.message})" }
        Log.i(TAG, "pos=${positionS}s ${bytes.size / 1024} KB table${file?.let { " -> ${it.name}" } ?: ""}: $report")
    }

    @Synchronized
    fun stop() {
        if (!tracking) return
        tracking = false
        runCatching { enable?.invoke(null, false) }
        Log.i(TAG, "off after $dumps windows")
    }

    private fun start(context: Context) {
        val why = runCatching { bind() }.exceptionOrNull()
        if (why != null || enable == null || dump == null) {
            Log.w(TAG, "tracker unavailable on this build: ${why?.javaClass?.simpleName} ${why?.message}")
            return
        }
        dir = File(context.filesDir, "alloctrack").also { it.mkdirs(); it.listFiles()?.forEach { f -> f.delete() } }
        val on = runCatching { enable?.invoke(null, true); true }.getOrDefault(false)
        tracking = on
        Log.i(TAG, if (on) "on: every BUFFER line now reports the window's Java allocations (files in ${dir?.path})"
            else "could not switch the tracker on")
    }

    /** The runtime's allocation tracker is hidden API: the process lifts the restriction for itself first (test builds). */
    @SuppressLint("BlockedPrivateApi", "PrivateApi")
    private fun bind() {
        val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)
        val getDeclaredMethod = Class::class.java.getDeclaredMethod("getDeclaredMethod", String::class.java, arrayOf<Class<*>>()::class.java)
        val vmRuntime = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
        val getRuntime = getDeclaredMethod.invoke(vmRuntime, "getRuntime", null) as Method
        val setExemptions = getDeclaredMethod.invoke(vmRuntime, "setHiddenApiExemptions", arrayOf(Array<String>::class.java)) as Method
        setExemptions.invoke(getRuntime.invoke(null), arrayOf("L"))
        val ddm = Class.forName("org.apache.harmony.dalvik.ddmc.DdmVmInternal")
        enable = runCatching { ddm.getDeclaredMethod("setRecentAllocationsTrackingEnabled", Boolean::class.javaPrimitiveType) }
            .getOrElse { ddm.getDeclaredMethod("enableRecentAllocations", Boolean::class.javaPrimitiveType) }
        dump = ddm.getDeclaredMethod("getRecentAllocations")
    }

    private fun property(): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, PROPERTY) as? String
    }.getOrNull()?.trim()

    class Site(val className: String, val method: String, val line: Int)

    class Entry(val size: Long, val threadId: Int, val className: String, val stack: List<Site>) {
        /** The first frame outside the allocating class's own constructors: where the bytes were asked for. */
        val site: String
            get() {
                val f = stack.firstOrNull { !it.method.contains("<init>") } ?: stack.firstOrNull()
                return if (f == null) "(no stack)" else "${f.className}.${f.method}:${f.line}"
            }
    }

    /**
     * The runtime's allocation table: a 15-byte header (header sizes, entry count, string table offset, table sizes),
     * one entry per allocation (size, thread, class, stack frames of class, method, file, line), then the class,
     * method and file name tables as length-prefixed UTF-16 strings.
     */
    fun parse(bytes: ByteArray): List<Entry> {
        val b = ByteBuffer.wrap(bytes)
        val headerLen = b.get().toInt() and 0xff
        val entryHeaderLen = b.get().toInt() and 0xff
        val frameLen = b.get().toInt() and 0xff
        val count = b.short.toInt() and 0xffff
        val stringsAt = b.int
        val classCount = b.short.toInt() and 0xffff
        val methodCount = b.short.toInt() and 0xffff
        val fileCount = b.short.toInt() and 0xffff
        val strings = ByteBuffer.wrap(bytes).apply { position(stringsAt) }
        fun table(n: Int): List<String> = List(n) {
            val chars = strings.int
            val arr = ByteArray(chars * 2)
            strings.get(arr)
            String(arr, StandardCharsets.UTF_16BE)
        }
        val classes = table(classCount)
        val methods = table(methodCount)
        table(fileCount)
        b.position(headerLen)
        val out = ArrayList<Entry>(count)
        repeat(count) {
            val at = b.position()
            val size = b.int.toLong() and 0xffffffffL
            val thread = b.short.toInt() and 0xffff
            val cls = classes.getOrElse(b.short.toInt() and 0xffff) { "?" }
            val depth = b.get().toInt() and 0xff
            b.position(at + entryHeaderLen)
            val stack = ArrayList<Site>(depth)
            repeat(depth) {
                val f = b.position()
                val fc = classes.getOrElse(b.short.toInt() and 0xffff) { "?" }
                val fm = methods.getOrElse(b.short.toInt() and 0xffff) { "?" }
                b.short
                val line = b.short.toInt()
                b.position(f + frameLen)
                stack += Site(fc, fm, line)
            }
            out += Entry(size, thread, cls, stack)
        }
        return out
    }

    /** Totals, then the sites and classes that allocated the most bytes in the window. */
    fun summarise(bytes: ByteArray): String {
        val entries = parse(bytes)
        val total = entries.sumOf { it.size }
        val bySite = entries.groupBy { it.site }.mapValues { (_, e) -> e.sumOf { it.size } to e.size }
            .entries.sortedByDescending { it.value.first }.take(TOP_SITES)
        val byClass = entries.groupBy { it.className }.mapValues { (_, e) -> e.sumOf { it.size } to e.size }
            .entries.sortedByDescending { it.value.first }.take(TOP_CLASSES)
        val threads = entries.groupBy { it.threadId }.mapValues { (_, e) -> e.sumOf { it.size } }
            .entries.sortedByDescending { it.value }.take(3)
        return "${entries.size} allocations, ${total / MB} MB; sites: " +
            bySite.joinToString { "${it.key} ${it.value.first / 1024} KB x${it.value.second}" } +
            "; classes: " + byClass.joinToString { "${it.key} ${it.value.first / 1024} KB x${it.value.second}" } +
            "; threads: " + threads.joinToString { "${it.key} ${it.value / 1024} KB" }
    }
}
