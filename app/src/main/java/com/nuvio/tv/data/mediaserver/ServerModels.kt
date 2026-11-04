package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.Subtitle
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.Serializable

@Serializable
enum class ServerMediaKind(val contentType: String) {
    MOVIE("movie"),
    SERIES("series"),
    COLLECTION("collection"),
    MIXED("mixed");

    companion object {
        fun fromContentType(type: String?): ServerMediaKind? = when (type?.trim()?.lowercase()) {
            "movie", "film" -> MOVIE
            "series", "show", "tv" -> SERIES
            else -> null
        }
    }
}

fun ServerMediaKind.domainType(): ContentType = when (this) {
    ServerMediaKind.MOVIE -> ContentType.MOVIE
    ServerMediaKind.SERIES -> ContentType.SERIES
    ServerMediaKind.COLLECTION,
    ServerMediaKind.MIXED -> ContentType.UNKNOWN
}

@Serializable
data class ServerLibrary(
    val id: String,
    val name: String,
    val kind: ServerMediaKind,
    val selected: Boolean = true
) {
    fun holds(kind: ServerMediaKind): Boolean =
        this.kind == kind || (this.kind == ServerMediaKind.MIXED && (kind == ServerMediaKind.MOVIE || kind == ServerMediaKind.SERIES))
}

/** A row from the server's own home page, shown on Nuvio Home next to the libraries. */
@Serializable
data class ServerHomeRow(
    val id: String,
    val title: String,
    val kind: ServerMediaKind
)

data class ServerAddresses(val address: String, val alternateAddress: String?)

@Serializable
data class ServerConnection(
    val id: String,
    val providerId: String,
    val name: String,
    val address: String,
    val remoteServerId: String,
    val remoteUserId: String,
    val userName: String,
    val credentialRef: String,
    val libraries: List<ServerLibrary> = emptyList(),
    val enabled: Boolean = true,
    val useCatalogMetadata: Boolean = false,
    val importContinueWatching: Boolean = true,
    val alternateAddress: String? = null,
    val lastGoodAddress: String? = null,
    val tlsPins: Map<String, String> = emptyMap(),
    /** Null: never renamed. Empty: renamed and reset, so other devices drop their name too. */
    val displayName: String? = null,
    val homeRows: List<ServerHomeRow> = emptyList(),
    val showHomeRows: Boolean = true,
    /** The account's import_watch_state for this server as other apps set it, sent back unchanged. */
    val syncedImportWatchState: Boolean? = null
) {
    val shownName: String
        get() = displayName?.takeIf { it.isNotBlank() } ?: name

    val isRenamed: Boolean
        get() = shownName != name

    val selectedLibraries: List<ServerLibrary>
        get() = libraries.filter { it.selected }

    /**
     * Every library that holds [kind], ticked or not, for search and stream matching; the ticks only
     * choose Home rows. A library of movies and shows comes back narrowed to [kind].
     */
    fun librariesHolding(kind: ServerMediaKind): List<ServerLibrary> =
        libraries.filter { it.holds(kind) }.map { if (it.kind == kind) it else it.copy(kind = kind) }
}

const val SERVER_DISPLAY_NAME_MAX_LENGTH = 30

private val WHITESPACE = Regex("\\s+")

/** Cleans a typed server name; null means the server's own name is used. */
fun serverDisplayName(input: String?, serverName: String): String? {
    val cleaned = input.orEmpty()
        .replace(WHITESPACE, " ")
        .trim()
        .take(SERVER_DISPLAY_NAME_MAX_LENGTH)
        .let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
        .trim()
    return cleaned.takeIf { it.isNotEmpty() && it != serverName }
}

class ServerSession(
    val connection: ServerConnection,
    val token: String,
    val address: String = connection.address
) {
    override fun toString(): String = "ServerSession(connection=${connection.id})"
}

class ServerSignIn(
    val address: String,
    val serverName: String,
    val serverId: String,
    val userId: String,
    val userName: String,
    val token: String
) {
    override fun toString(): String = "ServerSignIn(serverId=$serverId)"
}

class ServerQuickConnect(
    val address: String,
    val serverName: String,
    val serverId: String?,
    val code: String,
    internal val secret: String,
    val expiresAtMs: Long
) {
    override fun toString(): String = "ServerQuickConnect(serverId=$serverId)"
}

data class ServerItemRef(
    val connectionId: String,
    val itemId: String
) {
    fun encode(): String = "$PREFIX$connectionId:${itemId.escapeSegment()}"

    companion object {
        private const val PREFIX = "srv1:"

        fun isServerId(id: String?): Boolean = id?.startsWith(PREFIX) == true

        fun parse(id: String?): ServerItemRef? {
            if (id == null || !id.startsWith(PREFIX)) return null
            val body = id.removePrefix(PREFIX)
            val separator = body.indexOf(':')
            if (separator <= 0 || separator == body.lastIndex) return null
            val itemId = body.substring(separator + 1).unescapeSegment() ?: return null
            return ServerItemRef(connectionId = body.substring(0, separator), itemId = itemId)
        }
    }
}

private fun String.escapeSegment(): String = replace("%", "%25").replace(":", "%3A")

private fun String.unescapeSegment(): String? {
    if (':' in this) return null
    return replace("%3A", ":", ignoreCase = true).replace("%25", "%")
}

data class ServerPlaybackTarget(
    val item: ServerItemRef,
    val mediaSourceId: String?
) {
    fun key(): String = "${item.encode()}:${mediaSourceId.orEmpty()}"
}

data class ServerPage<T>(
    val items: List<T>,
    val totalCount: Int?
)

data class ServerTitle(
    val preview: MetaPreview,
    val externalIds: TrackingExternalIds = TrackingExternalIds()
)

data class ServerItemDetails(
    val meta: Meta,
    val externalIds: TrackingExternalIds,
    val userStates: List<ServerUserState> = emptyList()
)

data class ServerUserState(
    val videoId: String,
    val positionMs: Long,
    val durationMs: Long,
    val played: Boolean,
    val lastPlayedEpochMs: Long?,
    val season: Int? = null,
    val episode: Int? = null,
    val title: String? = null
)

data class ServerResumeEntry(
    val title: ServerTitle,
    val state: ServerUserState
)

data class ServerCandidate(
    val target: ServerPlaybackTarget,
    val title: String,
    val filename: String?,
    val sizeBytes: Long?,
    val bitrateBps: Long? = null,
    val video: String? = null,
    val audio: String? = null,
    val container: String? = null,
    val versionName: String? = null,
    val durationMs: Long? = null
)

data class ServerPlaybackRequest(
    val target: ServerPlaybackTarget,
    val capabilities: ServerPlayerCapabilities,
    val audioStreamIndex: Int? = null,
    val subtitleStreamIndex: Int? = null
)

data class ServerPlayerCapabilities(
    val allowDirectPlay: Boolean = true
)

enum class ServerPlayMethod(val wireName: String) {
    DIRECT_PLAY("DirectPlay"),
    DIRECT_STREAM("DirectStream"),
    TRANSCODE("Transcode")
}

class ServerPlaybackSession(
    val target: ServerPlaybackTarget,
    val mediaSourceId: String,
    val url: String,
    val headers: Map<String, String>,
    val subtitles: List<Subtitle>,
    val playSessionId: String?,
    val playMethod: ServerPlayMethod,
    val audioTracks: List<ServerTrack> = emptyList(),
    val burnInSubtitles: List<ServerTrack> = emptyList(),
    val transcodeReasons: List<String> = emptyList(),
    val transcodeOffered: Boolean = false
) {
    override fun toString(): String = "ServerPlaybackSession(item=${target.item.itemId}, method=$playMethod)"
}

enum class ServerSegmentKind {
    INTRO,
    RECAP,
    OUTRO,
    PREVIEW
}

data class ServerSegment(
    val kind: ServerSegmentKind,
    val startMs: Long,
    val endMs: Long
)

/** A named chapter the server knows for the file; [name] is null when it has none. */
data class ServerChapter(
    val startMs: Long,
    val name: String?
)

data class ServerTrack(
    val index: Int,
    val label: String,
    val language: String?,
    val selected: Boolean
)

enum class ServerPlaybackEventType {
    START,
    PROGRESS,
    PAUSE,
    RESUME,
    STOP
}

data class ServerPlaybackEvent(
    val type: ServerPlaybackEventType,
    val positionMs: Long,
    val isPaused: Boolean
)

enum class ServerFailure {
    AUTH_REQUIRED,
    FORBIDDEN,
    UNREACHABLE,
    UNSUPPORTED,
    STREAM_LIMIT,
    NOT_FOUND,
    INCOMPLETE,
    CERTIFICATE,
    FAILED
}

enum class ServerSignInIssue {
    WEBSITE,
    SILO_WEBSITE,
    SILO_JELLYFIN_OFF,
    PROFILE_NEEDED,
    PROFILE_NOT_FOUND,
    PROFILE_PIN,
    PROFILE_AND_PIN,
    PIN_WRONG,
    TEMPORARY_PASSWORD,
    PASSWORD_SIGN_IN_OFF,
    TOO_MANY_TRIES,
    SILO_NOT_NATIVE,
    PLEX_REMOTE_PASS,
    SERVER_MESSAGE
}

class ServerException(
    val failure: ServerFailure,
    message: String? = null,
    val network: Boolean = false,
    val issue: ServerSignInIssue? = null,
    val detail: String? = null,
    val httpStatus: Int? = null,
    val address: String? = null,
    val code: String? = null
) : Exception(message ?: failure.name)

/** A failure for a log line: its kind, HTTP status, Silo's problem code and whether the network failed. */
internal fun Throwable.forLog(): String = when (this) {
    is ServerException -> listOfNotNull(
        failure.name,
        httpStatus?.let { "http $it" },
        code,
        "network".takeIf { network }
    ).joinToString(" ")
    else -> javaClass.simpleName
}

data class ServerLocation(
    val address: String,
    val serverId: String?
)

fun Throwable.serverFailure(): ServerFailure = (this as? ServerException)?.failure ?: ServerFailure.FAILED

/** A shorter connect limit for server requests that have another address to fall back to. */
class ServerConnectTimeout(val millis: Long) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ServerConnectTimeout>
}
