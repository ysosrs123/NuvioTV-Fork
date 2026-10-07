package com.nuvio.tv.data.iptv

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.tv.core.iptv.HostKey

interface IptvHostStore {
    fun read(key: String): String?
    fun write(key: String, value: String?)
}

class PreferencesHostStore(private val preferences: SharedPreferences) : IptvHostStore {
    override fun read(key: String): String? = preferences.getString(key, null)
    override fun write(key: String, value: String?) = preferences.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()

    companion object {
        fun of(context: Context) = PreferencesHostStore(context.applicationContext.getSharedPreferences("iptv-live", Context.MODE_PRIVATE))
    }
}

class IptvHostMemory(private val prefix: String, private val store: IptvHostStore?, private val capacity: Int = 128) {
    private val memory = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > capacity
    }

    @Synchronized fun get(url: String): String? {
        val key = HostKey.of(url) ?: return null
        memory[key]?.let { return it }
        return runCatching { store?.read(prefix + key) }.getOrNull()?.takeIf { it.length <= 32 }?.also { memory[key] = it }
    }

    @Synchronized fun put(url: String, value: String) {
        val key = HostKey.of(url) ?: return
        if (memory[key] == value) return
        memory[key] = value
        runCatching { store?.write(prefix + key, value) }
    }

    @Synchronized fun forget(url: String) {
        val key = HostKey.of(url) ?: return
        memory.remove(key)
        runCatching { store?.write(prefix + key, null) }
    }
}
