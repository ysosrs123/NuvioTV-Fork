package com.nuvio.tv.core.iptv

interface GenerationWriter<T> {
    fun write(rows: List<T>)
    fun publish(): Boolean
    fun discard()
}

fun <T> saveGeneration(rows: List<T>, writer: GenerationWriter<T>, chunkRows: Int = 2_500, checkCancellation: () -> Unit = {}): Boolean {
    require(chunkRows > 0)
    var published = false
    var failure: Throwable? = null
    try {
        for (chunk in rows.chunked(chunkRows)) {
            checkCancellation()
            writer.write(chunk)
        }
        checkCancellation()
        published = writer.publish()
        return published
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        if (!published) try { writer.discard() } catch (cleanup: Throwable) {
            if (failure == null) throw cleanup else failure.addSuppressed(cleanup)
        }
    }
}
