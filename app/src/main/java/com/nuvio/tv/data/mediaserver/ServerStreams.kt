package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.data.mediaserver.mediabrowser.formatBitrate
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

class ServerStreamSource(
    val name: String,
    val preferred: Boolean,
    private val loader: suspend () -> List<Stream>
) {
    suspend fun load(): List<Stream> = loader()
}

@Singleton
class ServerStreams @Inject constructor(
    private val repository: ServerRepository,
    private val matcher: ServerMatcher
) {
    val revision: Int
        get() = repository.uiState.value.revision

    fun isNativeRequest(videoId: String): Boolean = ServerItemRef.isServerId(videoId)

    fun canServe(type: String, videoId: String): Boolean {
        ServerItemRef.parse(videoId)?.let { ref -> return repository.connection(ref.connectionId)?.enabled == true }
        val connections = repository.enabledConnections().ifEmpty { return false }
        val request = matcher.request(type, videoId, season = null, episode = null) ?: return false
        return connections.any { matcher.supports(it, request.kind) }
    }

    fun sources(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        forceRefresh: Boolean = false
    ): List<ServerStreamSource> {
        ServerItemRef.parse(videoId)?.let { ref ->
            val connection = repository.connection(ref.connectionId) ?: return emptyList()
            return listOf(source(connection, preferred = true) { candidates(ref) })
        }
        val connections = repository.enabledConnections().ifEmpty { return emptyList() }
        val request = matcher.request(type, videoId, season, episode) ?: return emptyList()
        return connections
            .filter { matcher.supports(it, request.kind) }
            .map { connection ->
                source(connection, preferred = connection.useCatalogMetadata) {
                    matcher.match(connection, request, forceRefresh).flatMap { candidates(it) }
                }
            }
    }

    fun preferredSourceNames(type: String, videoId: String): Set<String> =
        sources(type, videoId, season = null, episode = null)
            .filter { it.preferred }
            .mapTo(mutableSetOf()) { it.name }

    private fun source(
        connection: ServerConnection,
        preferred: Boolean,
        loader: suspend () -> List<Stream>
    ): ServerStreamSource = ServerStreamSource(repository.sourceLabel(connection), preferred, loader)

    suspend fun candidates(ref: ServerItemRef): List<Stream> {
        val connection = repository.connection(ref.connectionId) ?: throw ServerException(ServerFailure.NOT_FOUND)
        val label = repository.sourceLabel(connection)
        val providerName = repository.provider(connection)?.displayName ?: connection.providerId
        val logo = serverLogoUri(connection.providerId)
        val candidates = repository.call(ref.connectionId) { provider, session -> provider.candidates(session, ref.itemId) }
        return candidates.zip(serverStreamTexts(candidates, providerName, connection.name))
            .map { (candidate, text) ->
                Stream(
                    name = text.name,
                    title = null,
                    description = text.description,
                    url = null,
                    ytId = null,
                    infoHash = null,
                    fileIdx = null,
                    externalUrl = null,
                    behaviorHints = StreamBehaviorHints(
                        notWebReady = null,
                        bingeGroup = null,
                        countryWhitelist = null,
                        proxyHeaders = null,
                        videoSize = candidate.sizeBytes,
                        filename = candidate.filename
                    ),
                    addonName = label,
                    addonLogo = logo,
                    serverTarget = candidate.target
                )
            }
    }
}

internal data class ServerStreamText(
    val name: String,
    val description: String?
)

internal fun serverStreamTexts(
    candidates: List<ServerCandidate>,
    providerName: String,
    serverName: String
): List<ServerStreamText> {
    val names = candidates.map { candidate ->
        listOf(providerName, candidate.title).filter { it.isNotBlank() }.joinToString(" · ")
    }
    val repeated = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    return candidates.mapIndexed { index, candidate ->
        val versionName = candidate.versionName?.takeIf { names[index] in repeated }
        ServerStreamText(names[index], serverStreamDescription(candidate, serverName, versionName))
    }
}

internal fun serverStreamDescription(
    candidate: ServerCandidate,
    serverName: String,
    versionName: String? = null
): String? {
    val file = candidate.filename?.takeIf { it.isNotBlank() }?.let { "📄 $it" }
    val media = listOfNotNull(
        candidate.video?.takeIf { it.isNotBlank() }?.let { "🎥 $it" },
        candidate.audio?.takeIf { it.isNotBlank() }?.let { "🔊 $it" }
    ).joinToString(" | ").ifEmpty { null }
    val details = listOfNotNull(
        candidate.sizeBytes?.takeIf { it > 0 }?.let(::formatServerSize),
        candidate.bitrateBps?.let(::formatBitrate),
        candidate.container?.takeIf { it.isNotBlank() }
    )
    val source = listOfNotNull(
        serverName.takeIf { it.isNotBlank() }?.let { "🔍 $it" },
        versionName?.takeIf { it.isNotBlank() }
    )
    val last = when {
        details.isNotEmpty() -> "💾 " + (details + source).joinToString(" • ")
        source.isNotEmpty() -> source.joinToString(" • ")
        else -> null
    }
    return listOfNotNull(file, media, last).joinToString("\n").ifEmpty { null }
}

internal fun formatServerSize(bytes: Long): String {
    val gib = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gib >= 1.0) {
        String.format(Locale.US, "%.1f GB", gib)
    } else {
        "${(bytes / (1024.0 * 1024.0)).roundToInt()} MB"
    }
}
