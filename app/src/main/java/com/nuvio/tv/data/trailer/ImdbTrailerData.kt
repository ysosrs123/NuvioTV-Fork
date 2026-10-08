package com.nuvio.tv.data.trailer

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal object ImdbTrailerData {
    data class Encoding(val url: String, val height: Int, val mp4: Boolean, val hls: Boolean = false)
    data class Video(val id: String, val encodings: List<Encoding>)
    sealed interface Result {
        data class Found(val video: Video) : Result
        data object Missing : Result
        data object Pending : Result
    }

    fun parse(json: String?, titlePage: Boolean): Result = runCatching {
        val root = JsonParser.parseString(json ?: return Result.Pending).asJsonObject
        // Browser projection avoids moving megabytes of unrelated page data over the JS bridge.
        when (root.string("status")) {
            "missing" -> Result.Missing
            "found" -> video(root.getAsJsonObject("video"))
            else -> if (titlePage) {
                val primary = findPrimary(root) ?: return Result.Pending
                val edges = primary.getAsJsonArray("edges") ?: return Result.Pending
                if (edges.size() == 0) Result.Missing
                else video(edges[0].asJsonObject.getAsJsonObject("node"))
            } else {
                val page = root.getAsJsonObject("props")?.getAsJsonObject("pageProps")
                video(page?.getAsJsonObject("videoPlaybackData")?.getAsJsonObject("video"))
            }
        }
    }.getOrDefault(Result.Pending)

    private fun video(node: JsonObject?): Result {
        node ?: return Result.Pending
        val id = node.string("id") ?: return Result.Pending
        if (!Regex("vi\\d{6,}").matches(id)) return Result.Pending
        val encodings = node.getAsJsonArray("playbackURLs")?.mapNotNull { element ->
            val e = element.asJsonObject
            val url = e.string("url")?.replace("&amp;", "&") ?: return@mapNotNull null
            if (!url.startsWith("https://")) return@mapNotNull null
            val definition = e.string("videoDefinition")
                ?: e.getAsJsonObject("displayName")?.string("value") ?: e.string("definition").orEmpty()
            val mime = e.string("videoMimeType") ?: e.string("mimeType").orEmpty()
            val hls = mime.contains("m3u8", true) || url.contains(".m3u8", true)
            Encoding(url, height(definition), !hls && (mime.contains("mp4", true) || url.contains(".mp4", true)), hls)
        }.orEmpty()
        return Result.Found(Video(id, encodings))
    }

    fun bestMp4(video: Video): Encoding? = video.encodings
        .filter { it.mp4 && it.height >= 720 }.maxByOrNull { it.height }

    fun height(label: String): Int {
        val value = label.trim().uppercase().removePrefix("DEF_")
        return when (value) {
            "4K", "UHD" -> 2160
            "2K", "QHD" -> 1440
            "FHD", "FULLHD", "FULL HD" -> 1080
            "HD" -> 720
            "SD" -> 480
            else -> Regex("\\d{3,4}").find(value)?.value?.toIntOrNull() ?: 0
        }
    }

    private fun findPrimary(value: JsonElement): JsonObject? {
        if (value.isJsonObject) {
            val obj = value.asJsonObject
            obj.get("primaryVideos")?.takeIf { it.isJsonObject }?.let { return it.asJsonObject }
            obj.entrySet().forEach { (_, child) -> findPrimary(child)?.let { return it } }
        } else if (value.isJsonArray) {
            value.asJsonArray.forEach { child -> findPrimary(child)?.let { return it } }
        }
        return null
    }

    private fun JsonObject.string(key: String): String? = get(key)
        ?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    fun probeScript(expectedId: String, titlePage: Boolean): String {
        require(Regex(if (titlePage) "tt\\d+" else "vi\\d+").matches(expectedId))
        val extract = if (titlePage) """
            function find(o) {
                if (!o || typeof o !== 'object') return null;
                if (o.primaryVideos && Array.isArray(o.primaryVideos.edges)) return o.primaryVideos;
                for (var k in o) { var r = find(o[k]); if (r) return r; }
                return null;
            }
            var primary = find(root);
            if (!primary) return 'pending';
            if (primary.edges.length === 0) return JSON.stringify({status:'missing'});
            var v = primary.edges[0].node;
        """ else """
            var p = root.props && root.props.pageProps;
            var v = p && p.videoPlaybackData && p.videoPlaybackData.video;
            if (!v || (v.id && v.id !== '$expectedId')) return 'pending';
            if (!v.id) v.id = '$expectedId';
        """
        return """(function(){try {
            if (location.pathname.indexOf('/$expectedId') < 0) return 'pending';
            var e = document.getElementById('__NEXT_DATA__');
            if (!e) return 'pending';
            var root = JSON.parse(e.textContent || '{}');
            $extract
            if (!v || !v.id) return 'pending';
            return JSON.stringify({status:'found', video:{id:v.id, playbackURLs:v.playbackURLs || []}});
        } catch(e) { return 'pending'; }})()""".trimIndent()
    }
}
