package com.nuvio.tv.core.image

import coil3.memory.MemoryCache

/** Keep full-size heroes from evicting hundreds of small posters during a long browse.
 * Both LRUs share the existing total budget; no extra bitmap references are retained here.
 */
class ArtworkMemoryCache(bytes: Long) : MemoryCache {
    private val lock = Any()
    private val small = MemoryCache.Builder().maxSizeBytes(bytes - largeBudget(bytes)).build()
    private val large = MemoryCache.Builder().maxSizeBytes(largeBudget(bytes)).build()
    override val initialMaxSize = bytes
    override var maxSize: Long
        get() = synchronized(lock) { small.maxSize + large.maxSize }
        set(value) = synchronized(lock) {
            require(value >= 0)
            small.maxSize = value - largeBudget(value)
            large.maxSize = largeBudget(value)
        }
    val posterMaxSize: Long get() = synchronized(lock) { small.maxSize }
    override val size: Long get() = synchronized(lock) { small.size + large.size }
    override val keys: Set<MemoryCache.Key> get() = synchronized(lock) { small.keys + large.keys }
    override fun get(key: MemoryCache.Key): MemoryCache.Value? = synchronized(lock) {
        small[key] ?: large[key]
    }
    override fun set(key: MemoryCache.Key, value: MemoryCache.Value) = synchronized(lock) {
        // Remove the other variant if Coil upgrades the image behind a common URL key.
        if (value.image.size <= 1024 * 1024) {
            large.remove(key)
            small[key] = value
        } else {
            small.remove(key)
            large[key] = value
        }
    }
    override fun remove(key: MemoryCache.Key): Boolean = synchronized(lock) {
        val removedSmall = small.remove(key)
        large.remove(key) || removedSmall
    }
    override fun trimToSize(size: Long) = synchronized(lock) {
        require(size >= 0)
        small.trimToSize(size - largeBudget(size))
        large.trimToSize(largeBudget(size))
    }
    override fun clear() = synchronized(lock) { small.clear(); large.clear() }

    private companion object {
        // Retain one 1080p hero on smaller heaps without taking more than half
        // their budget. Larger heaps reserve three quarters for small artwork.
        fun largeBudget(total: Long) = maxOf(total / 4, minOf(8L * 1024 * 1024, total / 2))
    }
}
