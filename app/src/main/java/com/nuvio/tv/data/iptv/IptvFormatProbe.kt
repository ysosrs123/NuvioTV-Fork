package com.nuvio.tv.data.iptv

import android.content.Context
import com.nuvio.tv.core.iptv.SniffedFormat
import com.nuvio.tv.core.iptv.StreamFormatSniff
import okhttp3.OkHttpClient
import okhttp3.Request

class IptvFormatProbe(private val memory: IptvHostMemory) {
    fun known(url: String): IptvStreamFormat? = StreamFormatSniff.fromUrl(url)?.let(::format)
        ?: memory.get(url)?.let { value -> IptvStreamFormat.entries.firstOrNull { it.name == value && it != IptvStreamFormat.AUTO } }

    fun probe(client: OkHttpClient, url: String, headers: Map<String, String>): IptvStreamFormat? {
        known(url)?.let { return it }
        val request = try {
            Request.Builder().url(url).get().apply { headers.forEach { (name, value) -> header(name, value) } }.build()
        } catch (_: IllegalArgumentException) { return null }
        val found = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                val bytes = ByteArray(PROBE_BYTES)
                var length = 0
                body.byteStream().use { input ->
                    while (length < PROBE_BYTES) {
                        val read = input.read(bytes, length, PROBE_BYTES - length)
                        if (read < 0) break
                        length += read
                    }
                }
                StreamFormatSniff.decide(bytes, length, response.header("Content-Type"), response.request.url.toString())
            }
        } catch (_: Exception) { null } ?: return null
        return format(found).also { memory.put(url, it.name) }
    }

    fun forget(url: String) = memory.forget(url)

    private fun format(value: SniffedFormat) = when (value) {
        SniffedFormat.HLS -> IptvStreamFormat.HLS
        SniffedFormat.MPEG_TS -> IptvStreamFormat.MPEG_TS
    }

    companion object {
        const val PROBE_BYTES = 1024
        private var shared: IptvFormatProbe? = null

        @Synchronized fun shared(context: Context): IptvFormatProbe =
            shared ?: IptvFormatProbe(IptvHostMemory("probe-", PreferencesHostStore.of(context))).also { shared = it }
    }
}
