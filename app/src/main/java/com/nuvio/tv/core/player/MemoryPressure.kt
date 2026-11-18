package com.nuvio.tv.core.player

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import java.util.concurrent.CopyOnWriteArrayList

/** The app's one ComponentCallbacks2: thumbnails and playback buffers both listen here. */
object MemoryPressure {
    private class Entry(val minLevel: Int, val listener: (Int) -> Unit)

    private val entries = CopyOnWriteArrayList<Entry>()
    @Volatile private var registered = false
    /** The app's screen is resumed in front. */
    @Volatile var inFront: Boolean = false

    fun register(context: Context) {
        if (registered) return
        synchronized(this) {
            if (registered) return
            context.applicationContext.registerComponentCallbacks(object : ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) = dispatch(level)

                override fun onConfigurationChanged(newConfig: Configuration) = Unit

                @Deprecated("Deprecated in Java")
                override fun onLowMemory() = dispatch(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
            })
            registered = true
        }
    }

    /** [listener] hears every level at or above [minLevel]. */
    fun addListener(minLevel: Int, listener: (Int) -> Unit) {
        entries.add(Entry(minLevel, listener))
    }

    fun removeListener(listener: (Int) -> Unit) {
        entries.removeAll { it.listener === listener }
    }

    internal fun dispatch(level: Int) {
        entries.forEach { if (level >= it.minLevel) runCatching { it.listener(level) } }
    }
}
