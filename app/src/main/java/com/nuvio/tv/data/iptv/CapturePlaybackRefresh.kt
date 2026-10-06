package com.nuvio.tv.data.iptv

internal class CapturePlaybackRefresh(private val post: (Runnable)->Boolean,
    private val remove: (Runnable)->Unit, publish: ()->Unit, private val failure: (Exception)->Unit) {
    private var publish: (() -> Unit)? = publish
    private var closed=false
    private var dirty=false
    private var queued=false
    private var active=0
    private val callback=Runnable { drain() }
    fun request(): Boolean {
        synchronized(this) {
            if(closed) return false
            dirty=true
            if(queued || active!=0) return true
            queued=true
        }
        try { if(!post(callback)) throw IllegalStateException("Playback callback rejected") }
        catch(e:Exception) { synchronized(this) { closed=true; queued=false; publish=null }; failure(e); return false }
        return true
    }
    private fun drain() {
        val action=synchronized(this) {
            if(closed || !queued || !dirty) return
            queued=false; dirty=false; active++; publish
        }
        try { action?.invoke() }
        catch(e:Exception) { close(); failure(e) }
        finally {
            val again=synchronized(this) { active--; !closed && dirty }
            if(again) request()
        }
    }
    fun close(): Boolean {
        synchronized(this) { closed=true; queued=false; dirty=false; publish=null }
        try { remove(callback) } catch (_:Exception) { return false }
        return synchronized(this) { active==0 }
    }
}
