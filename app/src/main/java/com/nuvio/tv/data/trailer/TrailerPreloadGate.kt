package com.nuvio.tv.data.trailer

internal class TrailerPreloadGate {
    private data class Warm(val owner: Any, val cancel: () -> Unit)
    private val warming = mutableMapOf<String, Warm>()
    private val playing = mutableMapOf<String, Int>()

    @Synchronized fun beginWarm(url: String, owner: Any, cancel: () -> Unit): Boolean {
        if (url in playing || url in warming) return false
        warming[url] = Warm(owner, cancel)
        return true
    }

    @Synchronized fun endWarm(url: String, owner: Any) {
        if (warming[url]?.owner === owner) warming.remove(url)
    }

    @Synchronized fun beginPlayback(url: String) {
        playing[url] = (playing[url] ?: 0) + 1
        warming.remove(url)?.cancel?.invoke()
    }

    @Synchronized fun endPlayback(url: String) {
        val remaining = (playing[url] ?: return) - 1
        if (remaining == 0) playing.remove(url) else playing[url] = remaining
    }
}
