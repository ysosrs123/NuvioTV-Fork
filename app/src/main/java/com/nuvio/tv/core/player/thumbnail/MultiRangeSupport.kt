package com.nuvio.tv.core.player.thumbnail

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Which servers answer one request for many byte ranges (multipart/byteranges). Learned per server from the first
 * try and kept across sessions; a "no" is tried again after [RETRY_NO_MS], a "yes" stays until a request fails.
 */
internal class MultiRangeSupport(private val memory: Memory) {
    interface Memory {
        fun load(): Map<String, String>
        fun save(key: String, value: String?)
    }

    companion object {
        const val RETRY_NO_MS = 7L * 24 * 60 * 60 * 1000

        /**
         * CDN nodes of one service share the answer: the last two labels of a name, the whole address otherwise. TorBox's
         * store servers refuse batches where its other servers on the same domain take them, so they keep their own.
         */
        fun key(url: String): String? {
            val u = url.trim().toHttpUrlOrNull() ?: return null
            val host = u.host
            val isAddress = host.all { it.isDigit() || it == '.' } || host.contains(':')
            val base = if (isAddress) host else host.split('.').takeLast(2).joinToString(".")
            return (if (RequestBudget.isStoreHost(host)) "store." else "") + "$base:${u.port}"
        }

        fun decode(value: String?, now: Long): Boolean? {
            val parts = value?.split('|') ?: return null
            val yes = parts.getOrNull(0) == "y"
            val at = parts.getOrNull(1)?.toLongOrNull() ?: return null
            return if (!yes && now - at > RETRY_NO_MS) null else yes
        }

        fun encode(yes: Boolean, now: Long): String = (if (yes) "y" else "n") + "|" + now

        @Volatile private var shared: MultiRangeSupport? = null

        fun app(context: Context): MultiRangeSupport = shared ?: synchronized(this) {
            shared ?: MultiRangeSupport(PrefsMemory(context.applicationContext)).also { shared = it }
        }
    }

    private class PrefsMemory(private val context: Context) : Memory {
        private val prefs get() = context.getSharedPreferences("seek_thumbs_multirange2", Context.MODE_PRIVATE)
        override fun load(): Map<String, String> =
            runCatching { prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap() }.getOrDefault(emptyMap())
        override fun save(key: String, value: String?) {
            runCatching { prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply() }
        }
    }

    private val known: MutableMap<String, String> by lazy { java.util.concurrent.ConcurrentHashMap(memory.load()) }

    /** True or unknown: worth a multi-range request. */
    fun allowed(url: String, now: Long = System.currentTimeMillis()): Boolean {
        val k = key(url) ?: return false
        return decode(known[k], now) != false
    }

    fun known(url: String, now: Long = System.currentTimeMillis()): Boolean? = key(url)?.let { decode(known[it], now) }

    fun note(url: String, yes: Boolean, now: Long = System.currentTimeMillis()) {
        val k = key(url) ?: return
        val v = encode(yes, now)
        if (known[k]?.startsWith(if (yes) "y" else "n") == true && yes) return
        known[k] = v
        memory.save(k, v)
    }
}
