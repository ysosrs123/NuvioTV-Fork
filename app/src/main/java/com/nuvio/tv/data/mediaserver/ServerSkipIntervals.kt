package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.data.repository.SkipInterval

private const val MIN_SEGMENT_MS = 3_000L

fun List<ServerSegment>.toSkipIntervals(isMovie: Boolean): List<SkipInterval> =
    filter { it.endMs - it.startMs >= MIN_SEGMENT_MS && it.startMs >= 0L }
        .mapNotNull { segment ->
            val type = when (segment.kind) {
                ServerSegmentKind.INTRO -> "intro"
                ServerSegmentKind.RECAP -> "recap"
                ServerSegmentKind.OUTRO -> if (isMovie) "movie-credits" else "outro"
                ServerSegmentKind.PREVIEW -> return@mapNotNull null
            }
            SkipInterval(segment.startMs / 1000.0, segment.endMs / 1000.0, type, provider = "server")
        }
        .distinctBy { it.type }

/** Server markers are timed against the file being played, so they win over every other provider per segment. */
fun mergeServerSkipIntervals(server: List<SkipInterval>, others: List<SkipInterval>): List<SkipInterval> {
    val covered = server.mapNotNullTo(mutableSetOf()) { skipCategory(it.type) }
    return server + others.filter { it.provider != "server" && skipCategory(it.type) !in covered }
}

private fun skipCategory(type: String): String? = when (type.lowercase()) {
    "intro", "op", "mixed-op" -> "opening"
    "outro", "ed", "mixed-ed", "credits", "ending", "movie-credits" -> "ending"
    "recap" -> "recap"
    else -> null
}
