package com.nuvio.tv.data.mediaserver.plex

import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.data.mediaserver.ServerAddresses
import com.nuvio.tv.data.mediaserver.ServerBytes
import com.nuvio.tv.data.mediaserver.ServerCandidate
import com.nuvio.tv.data.mediaserver.ServerCapability
import com.nuvio.tv.data.mediaserver.ServerChapter
import com.nuvio.tv.data.mediaserver.ServerEpisode
import com.nuvio.tv.data.mediaserver.ServerException
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerGenre
import com.nuvio.tv.data.mediaserver.ServerHomeRow
import com.nuvio.tv.data.mediaserver.ServerIndexEntry
import com.nuvio.tv.data.mediaserver.ServerItemDetails
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.data.mediaserver.ServerLibrary
import com.nuvio.tv.data.mediaserver.ServerLibraryFilter
import com.nuvio.tv.data.mediaserver.ServerLocation
import com.nuvio.tv.data.mediaserver.ServerMediaKind
import com.nuvio.tv.data.mediaserver.ServerPage
import com.nuvio.tv.data.mediaserver.ServerPlayMethod
import com.nuvio.tv.data.mediaserver.ServerPlaybackEvent
import com.nuvio.tv.data.mediaserver.ServerPlaybackEventType
import com.nuvio.tv.data.mediaserver.ServerPlaybackRequest
import com.nuvio.tv.data.mediaserver.ServerPlaybackSession
import com.nuvio.tv.data.mediaserver.ServerPlaybackTarget
import com.nuvio.tv.data.mediaserver.ServerProvider
import com.nuvio.tv.data.mediaserver.ServerResumeEntry
import com.nuvio.tv.data.mediaserver.ServerSegment
import com.nuvio.tv.data.mediaserver.ServerSegmentKind
import com.nuvio.tv.data.mediaserver.ServerSession
import com.nuvio.tv.data.mediaserver.ServerSignIn
import com.nuvio.tv.data.mediaserver.ServerSignInIssue
import com.nuvio.tv.data.mediaserver.ServerSort
import com.nuvio.tv.data.mediaserver.ServerThumbnailFormat
import com.nuvio.tv.data.mediaserver.ServerTitle
import com.nuvio.tv.data.mediaserver.ServerTitleQuery
import com.nuvio.tv.data.mediaserver.ServerTrack
import com.nuvio.tv.data.mediaserver.ServerUserState
import com.nuvio.tv.data.mediaserver.mediabrowser.MediaBrowserClient
import com.nuvio.tv.data.mediaserver.mediabrowser.ServerClientIdentity
import com.nuvio.tv.data.mediaserver.mediabrowser.buildUrl
import com.nuvio.tv.data.mediaserver.mediabrowser.normalizeServerAddress
import com.nuvio.tv.data.mediaserver.mediabrowser.pathSegment
import com.nuvio.tv.data.mediaserver.searchEach
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.domain.model.WatchProgress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.DeserializationStrategy
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/** Plex Media Server: signed in through plex.tv with a code, then a server and a Plex Home user. */
class PlexProvider internal constructor(
    http: OkHttpClient,
    private val identity: ServerClientIdentity,
    private val now: () -> Long
) : ServerProvider {
    constructor(http: OkHttpClient, identity: ServerClientIdentity) : this(http, identity, System::currentTimeMillis)

    override val id: String = ID
    override val displayName: String = "Plex"
    override val capabilities: Set<ServerCapability> = setOf(
        ServerCapability.SEARCH,
        ServerCapability.EXTERNAL_ID_LOOKUP,
        ServerCapability.USER_STATE_READ,
        ServerCapability.USER_STATE_WRITE,
        ServerCapability.TRANSCODING,
        ServerCapability.HOME_ROWS
    )
    override val minimumVersion: String = ""

    internal val account = PlexAccountClient(http, identity, now)
    private val client = MediaBrowserClient(PlexAccountClient.TOKEN_HEADER, http, identity)
    /** Plex's replies vary by field and version: a tag's id is a number in some and a string in others. */
    private val json = kotlinx.serialization.json.Json(client.json) {
        isLenient = true
        coerceInputValues = true
    }
    private val plays = ConcurrentHashMap<String, PlexPlay>()
    private val recentlyPlayed = ConcurrentHashMap<String, Long>()
    private val leaves = ConcurrentHashMap<String, Pair<Long, List<MetadataWire>>>()
    private val items = ConcurrentHashMap<String, Pair<Long, MetadataWire>>()
    private val datedSections = ConcurrentHashMap<String, Boolean>()

    // Sign-in

    /** The server's own id at [address]; null when nothing that answers as Plex is there. */
    suspend fun machineId(address: String): String? = try {
        get(address, null, IDENTITY_PATH).machineIdentifier?.takeIf { it.isNotBlank() }
    } catch (error: ServerException) {
        if (error.failure == ServerFailure.CERTIFICATE) throw error
        null
    }

    suspend fun reach(server: PlexServer): PlexServer = account.reach(server, ::machineId)

    /** plex.tv's current addresses for the server; its home address and plex.direct names change with the network. */
    override suspend fun listedAddresses(session: ServerSession): ServerAddresses? {
        val credential = credentialOrNull(session.token) ?: return null
        val server = account.servers(credential.userToken).firstOrNull { it.machineId == session.connection.remoteServerId }
            ?: return null
        val reached = reach(server)
        val address = reached.address ?: return null
        return ServerAddresses(address, reached.secondAddress?.takeIf { it != address })
    }

    fun signIn(server: PlexServer, owner: PlexAccount, user: PlexAccount): ServerSignIn {
        val address = server.address ?: throw ServerException(ServerFailure.UNREACHABLE)
        return ServerSignIn(
            address = address,
            serverName = server.name,
            serverId = server.machineId,
            userId = owner.id,
            userName = owner.name,
            token = encode(
                PlexCredential(
                    accountToken = owner.token,
                    userToken = user.token,
                    serverToken = server.accessToken,
                    userId = user.id,
                    userUuid = user.uuid,
                    userName = user.name,
                    homeUser = user.uuid != owner.uuid
                )
            )
        )
    }

    /** The Plex Home users of the account behind [session]. */
    suspend fun homeUsers(session: ServerSession): List<PlexHomeUser> {
        val credential = credential(session)
        return account.homeUsers(account.account(credential.accountToken))
    }

    /** [session]'s token moved to [user]; null when the PIN is wrong. */
    suspend fun withUser(session: ServerSession, user: PlexHomeUser, pin: String?): String? {
        val credential = credential(session)
        val owner = account.account(credential.accountToken)
        val switched = account.switchUser(owner, user, pin) ?: return null
        val server = account.servers(switched.token).firstOrNull { it.machineId == session.connection.remoteServerId }
            ?: throw ServerException(ServerFailure.FORBIDDEN)
        return encode(
            credential.copy(
                userToken = switched.token,
                serverToken = server.accessToken,
                userId = switched.id,
                userUuid = switched.uuid,
                userName = switched.name,
                homeUser = switched.uuid != owner.uuid
            )
        )
    }

    fun homeUserName(session: ServerSession): String? = credentialOrNull(session.token)?.userName?.takeIf { it.isNotBlank() }

    fun homeUserId(session: ServerSession): String? = credentialOrNull(session.token)?.userUuid

    internal fun serverToken(session: ServerSession): String? = credentialOrNull(session.token)?.serverToken

    override suspend fun locate(address: String): ServerLocation? {
        val normalized = normalizeServerAddress(address) ?: return null
        val machineId = machineId(normalized) ?: return null
        return ServerLocation(normalized, machineId)
    }

    // Browsing

    override suspend fun libraries(session: ServerSession): List<ServerLibrary> =
        get(session, SECTIONS_PATH).directories.mapNotNull { section ->
            val kind = plexKind(section.type) ?: return@mapNotNull null
            section.key.takeIf { it.isNotBlank() }?.let { ServerLibrary(it, section.title.ifBlank { it }, kind) }
        }

    override suspend fun libraryPage(
        session: ServerSession,
        library: ServerLibrary,
        start: Int,
        limit: Int,
        sort: ServerSort,
        genreId: String?,
        filter: ServerLibraryFilter
    ): ServerPage<ServerTitle> {
        val order = when (sort) {
            ServerSort.TITLE -> "titleSort"
            ServerSort.RELEASED -> if (hasReleaseDates(session, library)) "originallyAvailableAt:desc" else "addedAt:desc"
            ServerSort.ADDED -> "addedAt:desc"
        }
        val query = sectionQuery(library) + page(start, limit) + ("sort" to order) + ("genre" to genreId?.takeIf { it.isNotBlank() }) +
            ("unwatched" to "1".takeIf { filter.unwatched })
        val path = filter.letter?.let { "$SECTIONS_PATH/${pathSegment(library.id)}/firstCharacter/$it" } ?: sectionPath(library)
        val reply = get(session, path, query)
        val mapper = mapper(session)
        return ServerPage(reply.metadata.mapNotNull(mapper::title), reply.totalSize)
    }

    override suspend fun libraryGenres(session: ServerSession, library: ServerLibrary): List<ServerGenre> =
        get(session, "$SECTIONS_PATH/${pathSegment(library.id)}/genre")
            .directories
            .mapNotNull { genre -> genre.title.trim().takeIf { it.isNotEmpty() && genre.key.isNotBlank() }?.let { ServerGenre(genre.key, it) } }

    /** Personal media libraries carry file dates, not release dates, so they keep the added order. */
    private suspend fun hasReleaseDates(session: ServerSession, library: ServerLibrary): Boolean {
        val key = "${session.connection.id}:${library.id}"
        datedSections[key]?.let { return it }
        val agents = get(session, SECTIONS_PATH).directories.associate { it.key to it.agent.orEmpty() }
        agents.forEach { (id, agent) -> datedSections["${session.connection.id}:$id"] = !agent.endsWith(".none") }
        return datedSections[key] ?: true
    }

    override suspend fun search(
        session: ServerSession,
        kind: ServerMediaKind,
        libraries: List<ServerLibrary>,
        query: String,
        limit: Int
    ): List<ServerTitle> {
        val mapper = mapper(session)
        val items = searchEach(libraries) { library ->
            get(session, sectionPath(library), sectionQuery(library) + page(0, limit.coerceIn(1, SEARCH_MAX)) + ("title" to query.trim())).metadata
        }
        return items.mapNotNull(mapper::title).distinctBy { it.preview.id }.take(limit)
    }

    override suspend fun details(session: ServerSession, itemId: String): ServerItemDetails {
        val item = item(session, itemId)
        val mapper = mapper(session)
        val episodes = if (item.type == "show") allLeaves(session, itemId, fresh = true) else emptyList()
        val states = if (item.type == "show") episodes.map(mapper::userState) else listOf(mapper.userState(item))
        return ServerItemDetails(meta = mapper.details(item, episodes), externalIds = item.externalIds(), userStates = states)
    }

    override suspend fun externalIds(session: ServerSession, itemId: String): TrackingExternalIds = item(session, itemId).externalIds()

    override suspend fun setPlayed(session: ServerSession, itemId: String, played: Boolean) {
        val justPlayed = recentlyPlayed[itemId]?.let { now() - it < RECENT_PLAY_MS } == true
        if (played && (justPlayed || plays.values.any { it.started && it.ratingKey == itemId && !it.expired() })) return
        get(session, if (played) SCROBBLE_PATH else UNSCROBBLE_PATH, mapOf("key" to itemId, "identifier" to LIBRARY_IDENTIFIER), decode = false)
        leaves.clear()
        items.clear()
    }

    /** Items started and not finished; Plex's On Deck also lists the next unwatched episode, which Nuvio works out itself. */
    override suspend fun resumeItems(session: ServerSession, limit: Int): List<ServerResumeEntry> {
        val reply = try {
            get(session, CONTINUE_WATCHING_PATH, page(0, limit.coerceAtLeast(1) * 2) + ("includeGuids" to "1"))
        } catch (error: ServerException) {
            if (error.failure != ServerFailure.NOT_FOUND) throw error
            get(session, ON_DECK_PATH, page(0, limit.coerceAtLeast(1) * 2) + ("includeGuids" to "1"))
        }
        val items = reply.metadata.ifEmpty { reply.hubs.flatMap { it.metadata } }
        val sections = session.connection.selectedLibraries.map { it.id }.toSet()
        val mapper = mapper(session)
        return items
            .filter { (it.viewOffset ?: 0L) > 0L }
            .filter { item -> item.librarySectionId == null || sections.isEmpty() || item.librarySectionId.toString() in sections }
            .mapNotNull { item ->
                val title = mapper.resumeTitle(item) ?: return@mapNotNull null
                ServerResumeEntry(title, mapper.userState(item).copy(played = false))
            }
            .distinctBy { it.title.preview.id }
            .take(limit)
    }

    override suspend fun externalIdIndex(
        session: ServerSession,
        library: ServerLibrary,
        start: Int,
        limit: Int
    ): ServerPage<ServerIndexEntry> {
        val reply = get(session, sectionPath(library), sectionQuery(library) + page(start, limit) + ("sort" to "titleSort"))
        val mapper = mapper(session)
        return ServerPage(reply.metadata.map(mapper::indexEntry), reply.totalSize)
    }

    /** A title search in [library]; the matcher compares the ids of what comes back. */
    override suspend fun lookupTitle(session: ServerSession, library: ServerLibrary, query: ServerTitleQuery): List<ServerIndexEntry>? {
        val names = listOfNotNull(query.name, query.originalName).map(String::trim).filter(String::isNotEmpty).distinct()
        if (names.isEmpty()) return null
        val mapper = mapper(session)
        val found = mutableListOf<ServerIndexEntry>()
        val years = listOfNotNull(query.year?.toString()) + listOf(null)
        for (name in names) {
            for (year in years) {
                val reply = get(
                    session,
                    sectionPath(library),
                    sectionQuery(library) + page(0, TITLE_SEARCH_LIMIT) + mapOf("title" to name, "year" to year)
                )
                found += reply.metadata.filter { plexKind(it.type) == library.kind }.map(mapper::indexEntry)
                if (found.any { it.ids.sharesAnyWith(query.ids) }) return found.distinctBy { it.itemId }
            }
        }
        return found.distinctBy { it.itemId }
    }

    override suspend fun findEpisode(session: ServerSession, seriesItemId: String, season: Int, episode: Int): ServerEpisode? =
        allLeaves(session, seriesItemId)
            .firstOrNull { it.parentIndex == season && it.index == episode }
            ?.let { ServerEpisode(it.ratingKey, it.originallyAvailableAt) }

    override suspend fun episodeStates(session: ServerSession, seriesItemId: String): List<ServerUserState> {
        val mapper = mapper(session)
        return allLeaves(session, seriesItemId, fresh = true).map(mapper::userState)
    }

    /** Plex's own Home hubs (Recently Added and the like), without Continue Watching and On Deck: Nuvio has its own. */
    override suspend fun homeRows(session: ServerSession): List<ServerHomeRow> =
        get(session, HUBS_PATH, mapOf("count" to HUB_PEEK.toString())).hubs
            .filter { hub -> !hub.key.isNullOrBlank() && SKIPPED_HUBS.none { hub.hubIdentifier.contains(it, ignoreCase = true) } }
            .mapNotNull { hub ->
                val kind = when {
                    hub.type == "movie" -> ServerMediaKind.MOVIE
                    hub.type in SERIES_HUB_TYPES -> ServerMediaKind.SERIES
                    hub.type == "mixed" && "television" in hub.hubIdentifier.lowercase() -> ServerMediaKind.SERIES
                    else -> return@mapNotNull null
                }
                ServerHomeRow(hub.key!!, hub.title.ifBlank { hub.hubIdentifier }, kind)
            }

    override suspend fun homeRowItems(session: ServerSession, rowId: String): List<ServerTitle> {
        val link = ("http://h" + rowId).toHttpUrlOrNull() ?: throw ServerException(ServerFailure.NOT_FOUND)
        val query = link.queryParameterNames.associateWith { link.queryParameter(it) } + page(0, HUB_ITEMS) + ("includeGuids" to "1")
        val reply = get(session, link.encodedPath, query)
        val items = reply.metadata.ifEmpty { reply.hubs.flatMap { it.metadata } }
        val mapper = mapper(session)
        return items.mapNotNull { if (it.type == "episode" || it.type == "season") mapper.resumeTitle(it) else mapper.title(it) }
            .distinctBy { it.preview.id }
    }

    // Playback

    override suspend fun candidates(session: ServerSession, itemId: String): List<ServerCandidate> {
        val item = item(session, itemId)
        val ref = ServerItemRef(session.connection.id, itemId)
        return item.media.mapNotNull { media ->
            val part = media.parts.firstOrNull() ?: return@mapNotNull null
            ServerCandidate(
                target = ServerPlaybackTarget(ref, part.id.toString()),
                title = media.label(),
                filename = part.file?.substringAfterLast('/')?.substringAfterLast('\\'),
                sizeBytes = part.size?.takeIf { it > 0 },
                bitrateBps = media.bitrate?.takeIf { it > 0 }?.times(1_000L),
                video = listOfNotNull(media.videoCodec?.uppercase(), media.videoDynamicRange).joinToString(" ").ifBlank { null },
                audio = media.audioCodec?.uppercase()?.let { codec -> media.audioChannels?.let { "$codec ${channelLabel(it)}" } ?: codec },
                container = media.container?.uppercase()?.let { if (it == "MATROSKA") "MKV" else it },
                versionName = media.editionTitle?.takeIf { it.isNotBlank() } ?: media.title?.takeIf { it.isNotBlank() },
                durationMs = (part.duration ?: media.duration)?.takeIf { it > 0 }
            )
        }
    }

    override suspend fun preparePlayback(session: ServerSession, request: ServerPlaybackRequest): ServerPlaybackSession {
        val token = serverToken(session) ?: throw ServerException(ServerFailure.AUTH_REQUIRED)
        val ratingKey = request.target.item.itemId
        val item = item(session, ratingKey)
        val mediaIndex = item.media.indexOfFirst { media -> media.parts.any { it.id.toString() == request.target.mediaSourceId } }
            .takeIf { it >= 0 } ?: 0
        val media = item.media.getOrNull(mediaIndex) ?: throw ServerException(ServerFailure.NOT_FOUND)
        val part = media.parts.firstOrNull() ?: throw ServerException(ServerFailure.NOT_FOUND)
        if (request.audioStreamIndex != null || request.subtitleStreamIndex != null) {
            chooseStreams(session, part.id, request.audioStreamIndex, request.subtitleStreamIndex)
        }
        val playSession = UUID.randomUUID().toString()
        val headers = playbackHeaders(token, playSession)
        val audio = part.streams.filter { it.streamType == STREAM_AUDIO }
        val audioTracks = audio.map { stream ->
            val selected = request.audioStreamIndex?.let { it.toLong() == stream.id } ?: stream.selected
            ServerTrack(stream.id.toInt(), stream.label(), stream.languageCode, selected)
        }
        val target = ServerPlaybackTarget(request.target.item, part.id.toString())
        val playback = if (request.capabilities.allowDirectPlay) {
            val url = buildUrl(session.address, part.key.ifBlank { "/library/parts/${part.id}/file" })
            checkReadable(session, url, token)
            ServerPlaybackSession(
                target = target,
                mediaSourceId = part.id.toString(),
                url = url,
                headers = headers,
                subtitles = externalSubtitles(session, part, token),
                playSessionId = playSession,
                playMethod = ServerPlayMethod.DIRECT_PLAY,
                audioTracks = audioTracks,
                transcodeOffered = true
            )
        } else {
            val query = transcodeQuery(item, mediaIndex, playSession, request.subtitleStreamIndex != null)
            val decision = decide(session, query, headers)
            val method = if (decision.transcodeDecisionText.isNullOrBlank() && decision.directPlayDecisionText != null) {
                ServerPlayMethod.DIRECT_STREAM
            } else {
                ServerPlayMethod.TRANSCODE
            }
            ServerPlaybackSession(
                target = target,
                mediaSourceId = part.id.toString(),
                url = buildUrl(session.address, TRANSCODE_START_PATH, query),
                headers = headers + (PROFILE_EXTRA_HEADER to PROFILE_EXTRA),
                subtitles = emptyList(),
                playSessionId = playSession,
                playMethod = method,
                audioTracks = audioTracks,
                burnInSubtitles = part.streams.filter { it.streamType == STREAM_SUBTITLE }.map { stream ->
                    val selected = request.subtitleStreamIndex?.let { it.toLong() == stream.id } ?: false
                    ServerTrack(stream.id.toInt(), stream.label(), stream.languageCode, selected)
                },
                transcodeReasons = listOfNotNull(decision.transcodeDecisionText, decision.generalDecisionText)
                    .filter { it.isNotBlank() }
                    .distinct()
            )
        }
        dropUnstartedPlays()
        plays[playSession] = PlexPlay(
            session,
            ratingKey,
            item.key.ifBlank { "$METADATA_PATH/$ratingKey" },
            item.duration ?: media.duration ?: 0L,
            playback.playMethod != ServerPlayMethod.DIRECT_PLAY,
            now()
        )
        return playback
    }

    /** A new playback replaces any that never started (failed direct play, fallback, track change); old entries expire. */
    private suspend fun dropUnstartedPlays() {
        val dropped = plays.entries.filter { (_, play) -> !play.started || play.expired() }
        dropped.forEach { (playSession, play) ->
            if (!plays.remove(playSession, play) || !play.transcoded) return@forEach
            withTimeoutOrNull(TRANSCODE_STOP_WAIT_MS) {
                runCatching {
                    send(play.session, "GET", TRANSCODE_STOP_PATH, mapOf("session" to playSession), mapOf("X-Plex-Session-Identifier" to playSession))
                }
            }
        }
    }

    /** Plex refuses the file itself (not the browsing) when it blocks remote play without a pass; one byte shows it. */
    private suspend fun checkReadable(session: ServerSession, url: String, token: String) {
        try {
            client.bytes(withToken(url, token), token = null, maxBytes = 1, range = 0L..0L, signed = false)
        } catch (error: ServerException) {
            if (error.failure != ServerFailure.FORBIDDEN) return
            throw ServerException(ServerFailure.FORBIDDEN, issue = ServerSignInIssue.PLEX_REMOTE_PASS, httpStatus = error.httpStatus, address = session.address)
        }
    }

    private suspend fun decide(session: ServerSession, query: Map<String, String?>, headers: Map<String, String>): MediaContainerWire {
        val reply = try {
            client.execute(
                "GET",
                session.address,
                TRANSCODE_DECISION_PATH,
                token = null,
                query = query,
                authorization = headers[PlexAccountClient.TOKEN_HEADER],
                headers = (headers - PlexAccountClient.TOKEN_HEADER) + (PROFILE_EXTRA_HEADER to PROFILE_EXTRA)
            )
        } catch (error: ServerException) {
            if (error.failure == ServerFailure.FORBIDDEN) {
                throw ServerException(ServerFailure.FORBIDDEN, issue = ServerSignInIssue.PLEX_REMOTE_PASS, httpStatus = error.httpStatus)
            }
            throw error
        }
        val container = decodeOrNull(ContainerReply.serializer(), reply.body)?.container ?: return MediaContainerWire()
        val code = container.generalDecisionCode ?: 0
        if (code >= DECISION_FAILED_FROM) {
            throw ServerException(ServerFailure.UNSUPPORTED, container.generalDecisionText, detail = container.generalDecisionText, code = code.toString())
        }
        return container
    }

    private fun transcodeQuery(item: MetadataWire, mediaIndex: Int, playSession: String, subtitles: Boolean): Map<String, String?> = mapOf(
        "path" to item.key.ifBlank { "$METADATA_PATH/${item.ratingKey}" },
        "mediaIndex" to mediaIndex.toString(),
        "partIndex" to "0",
        "protocol" to "hls",
        "fastSeek" to "1",
        "directPlay" to "0",
        "directStream" to "1",
        "directStreamAudio" to "1",
        "subtitleSize" to "100",
        "audioBoost" to "100",
        "subtitles" to if (subtitles) "burn" else "none",
        "session" to playSession,
        "X-Plex-Session-Identifier" to playSession,
        "copyts" to "1",
        "hasMDE" to "1"
    )

    /** Plex keeps the chosen audio and subtitle on the file for the user; both are set before asking for a stream. */
    private suspend fun chooseStreams(session: ServerSession, partId: Long, audio: Int?, subtitle: Int?) {
        val query = mapOf(
            "audioStreamID" to audio?.toString(),
            "subtitleStreamID" to subtitle?.toString(),
            "allParts" to "1"
        )
        send(session, "PUT", "$PARTS_PATH/$partId", query)
    }

    private fun externalSubtitles(session: ServerSession, part: PartWire, token: String): List<Subtitle> =
        part.streams.filter { it.streamType == STREAM_SUBTITLE && !it.key.isNullOrBlank() }.map { stream ->
            Subtitle(
                id = "plex:${stream.id}",
                url = buildUrl(session.address, stream.key!!),
                lang = stream.languageCode?.takeIf { it.isNotBlank() } ?: "und",
                addonName = stream.label(),
                addonLogo = null,
                isStreamProvided = true,
                headers = mapOf(PlexAccountClient.TOKEN_HEADER to token)
            )
        }

    override suspend fun report(session: ServerSession, playback: ServerPlaybackSession, event: ServerPlaybackEvent) {
        val playSession = playback.playSessionId ?: return
        val play = plays[playSession] ?: return
        if (event.type != ServerPlaybackEventType.STOP) play.started = true
        val state = when {
            event.type == ServerPlaybackEventType.STOP -> "stopped"
            event.isPaused || event.type == ServerPlaybackEventType.PAUSE -> "paused"
            else -> "playing"
        }
        val query = mapOf(
            "ratingKey" to play.ratingKey,
            "key" to play.key,
            "state" to state,
            "time" to event.positionMs.coerceAtLeast(0L).toString(),
            "duration" to play.durationMs.takeIf { it > 0 }?.toString(),
            "hasMDE" to "1"
        )
        val headers = mapOf("X-Plex-Session-Identifier" to playSession)
        try {
            send(session, "GET", TIMELINE_PATH, query, headers)
        } finally {
            if (event.type == ServerPlaybackEventType.STOP) {
                plays.remove(playSession)
                if (play.durationMs > 0 && event.positionMs >= (play.durationMs * WatchProgress.COMPLETED_THRESHOLD).toLong()) {
                    recentlyPlayed[play.ratingKey] = now()
                }
                leaves.clear()
                items.clear()
                if (play.transcoded) {
                    runCatching { send(session, "GET", TRANSCODE_STOP_PATH, mapOf("session" to playSession), headers) }
                }
            }
        }
    }

    override val thumbnailFormat: ServerThumbnailFormat = ServerThumbnailFormat.BIF

    override suspend fun bif(
        session: ServerSession,
        itemId: String,
        mediaSourceId: String,
        width: Int,
        range: LongRange?,
        maxBytes: Long
    ): ServerBytes {
        val token = serverToken(session) ?: throw ServerException(ServerFailure.AUTH_REQUIRED)
        val url = buildUrl(session.address, "$PARTS_PATH/${pathSegment(mediaSourceId)}/indexes/sd")
        return client.bytes(withToken(url, token), token = null, maxBytes = maxBytes, range = range, signed = false)
    }

    override suspend fun segments(session: ServerSession, itemId: String, mediaSourceId: String?): List<ServerSegment> = try {
        item(session, itemId).markers.mapNotNull { marker ->
            val kind = when (marker.type.lowercase()) {
                "intro" -> ServerSegmentKind.INTRO
                "credits" -> ServerSegmentKind.OUTRO
                else -> return@mapNotNull null
            }
            ServerSegment(kind, marker.startTimeOffset, marker.endTimeOffset).takeIf { marker.endTimeOffset > marker.startTimeOffset }
        }
    } catch (_: ServerException) {
        emptyList()
    }

    override suspend fun chapters(session: ServerSession, itemId: String, mediaSourceId: String?): List<ServerChapter> = try {
        item(session, itemId).chapters.mapNotNull { chapter ->
            val start = chapter.startTimeOffset?.takeIf { it >= 0 } ?: return@mapNotNull null
            ServerChapter(start, chapter.tag?.trim()?.takeIf { it.isNotEmpty() })
        }
    } catch (_: ServerException) {
        emptyList()
    }

    override fun authHeaders(session: ServerSession): Map<String, String> {
        val token = serverToken(session) ?: return emptyMap()
        return mapOf(PlexAccountClient.TOKEN_HEADER to token, "X-Plex-Client-Identifier" to identity.deviceId())
    }

    override fun staticStreamUrl(session: ServerSession, target: ServerPlaybackTarget): String? {
        val token = serverToken(session) ?: return null
        val partId = target.mediaSourceId?.takeIf { it.isNotBlank() } ?: return null
        return withToken(buildUrl(session.address, "$PARTS_PATH/${pathSegment(partId)}/file"), token)
    }

    // Requests

    private fun playbackHeaders(token: String, playSession: String): Map<String, String> =
        plexHeaders(identity) + mapOf(PlexAccountClient.TOKEN_HEADER to token, "X-Plex-Session-Identifier" to playSession)

    private suspend fun item(session: ServerSession, ratingKey: String): MetadataWire {
        val key = cacheKey(session) + "|" + ratingKey
        items[key]?.takeIf { now() - it.first < ITEM_TTL_MS }?.let { return it.second }
        val item = get(
            session,
            "$METADATA_PATH/${pathSegment(ratingKey)}",
            mapOf("includeGuids" to "1", "includeMarkers" to "1", "includeChapters" to "1", "includeExtras" to "0")
        ).metadata.firstOrNull() ?: throw ServerException(ServerFailure.NOT_FOUND)
        if (items.size >= MAX_ITEMS) items.clear()
        items[key] = now() to item
        return item
    }

    private fun cacheKey(session: ServerSession): String =
        session.connection.id + "|" + credentialOrNull(session.token)?.userId.orEmpty()

    /** Every episode of a show in one call, kept for a minute for the next-episode and matching lookups around a play. */
    private suspend fun allLeaves(session: ServerSession, showKey: String, fresh: Boolean = false): List<MetadataWire> {
        val key = cacheKey(session) + "|" + showKey
        val maxAge = if (fresh) FRESH_LEAVES_MS else LEAVES_TTL_MS
        leaves[key]?.takeIf { now() - it.first < maxAge }?.let { return it.second }
        val episodes = get(
            session,
            "$METADATA_PATH/${pathSegment(showKey)}/allLeaves",
            mapOf("excludeElements" to LEAVES_EXCLUDED, "excludeFields" to "summary,tagline")
        ).metadata.filter { it.ratingKey.isNotBlank() }
        if (leaves.size >= MAX_LEAVES) leaves.clear()
        leaves[key] = now() to episodes
        return episodes
    }

    private fun sectionPath(library: ServerLibrary) = "$SECTIONS_PATH/${pathSegment(library.id)}/all"

    private fun sectionQuery(library: ServerLibrary): Map<String, String?> =
        mapOf("type" to plexTypeNumber(library.kind), "includeGuids" to "1")

    private fun page(start: Int, size: Int): Map<String, String?> =
        mapOf("X-Plex-Container-Start" to start.coerceAtLeast(0).toString(), "X-Plex-Container-Size" to size.coerceAtLeast(1).toString())

    private fun mapper(session: ServerSession) = PlexMapper(session.address, session.connection.id)

    private suspend fun get(
        session: ServerSession,
        path: String,
        query: Map<String, String?> = emptyMap(),
        decode: Boolean = true
    ): MediaContainerWire {
        val body = send(session, "GET", path, query)
        if (!decode) return MediaContainerWire()
        return decodeOrNull(ContainerReply.serializer(), body)?.container ?: throw ServerException(ServerFailure.FAILED)
    }

    private suspend fun get(address: String, token: String?, path: String): MediaContainerWire {
        val reply = client.execute("GET", address, path, token = null, authorization = token, headers = plexHeaders(identity))
        return decodeOrNull(ContainerReply.serializer(), reply.body)?.container ?: throw ServerException(ServerFailure.FAILED)
    }

    private suspend fun send(
        session: ServerSession,
        method: String,
        path: String,
        query: Map<String, String?>,
        headers: Map<String, String> = emptyMap()
    ): String {
        val token = serverToken(session) ?: throw ServerException(ServerFailure.AUTH_REQUIRED)
        return client.execute(
            method,
            session.address,
            path,
            token = null,
            query = query,
            body = "".takeIf { method == "PUT" },
            authorization = token,
            headers = plexHeaders(identity) + headers
        ).body
    }

    private fun <T> decodeOrNull(reply: DeserializationStrategy<T>, body: String): T? = try {
        json.decodeFromString(reply, body)
    } catch (error: IllegalArgumentException) {
        android.util.Log.w("PlexProvider", "Plex reply not readable: ${error.message?.substringBefore("JSON input")?.take(160)}")
        null
    }

    private fun credential(session: ServerSession): PlexCredential =
        credentialOrNull(session.token) ?: throw ServerException(ServerFailure.AUTH_REQUIRED)

    internal fun credentialOrNull(token: String): PlexCredential? = try {
        json.decodeFromString(PlexCredential.serializer(), token)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun encode(credential: PlexCredential): String = json.encodeToString(PlexCredential.serializer(), credential)

    private fun withToken(url: String, token: String): String =
        url.toHttpUrlOrNull()?.newBuilder()?.setQueryParameter(PlexAccountClient.TOKEN_HEADER, token)?.build()?.toString() ?: url

    private inner class PlexPlay(
        val session: ServerSession,
        val ratingKey: String,
        val key: String,
        val durationMs: Long,
        val transcoded: Boolean,
        val preparedAtMs: Long
    ) {
        @Volatile var started = false

        fun expired(): Boolean = now() - preparedAtMs > PLAY_EXPIRY_MS
    }

    internal companion object {
        const val ID = "plex"
        const val IDENTITY_PATH = "/identity"
        const val SECTIONS_PATH = "/library/sections"
        const val METADATA_PATH = "/library/metadata"
        const val PARTS_PATH = "/library/parts"
        const val HUBS_PATH = "/hubs"
        const val CONTINUE_WATCHING_PATH = "/hubs/continueWatching/items"
        const val ON_DECK_PATH = "/library/onDeck"
        const val SCROBBLE_PATH = "/:/scrobble"
        const val UNSCROBBLE_PATH = "/:/unscrobble"
        const val TIMELINE_PATH = "/:/timeline"
        const val TRANSCODE_DECISION_PATH = "/video/:/transcode/universal/decision"
        const val TRANSCODE_START_PATH = "/video/:/transcode/universal/start.m3u8"
        const val TRANSCODE_STOP_PATH = "/video/:/transcode/universal/stop"
        const val LIBRARY_IDENTIFIER = "com.plexapp.plugins.library"
        const val PROFILE_EXTRA_HEADER = "X-Plex-Client-Profile-Extra"

        /** What Nuvio's player takes from a Plex conversion: HLS in MPEG-TS with H.264 or HEVC, and the common audio codecs. */
        const val PROFILE_EXTRA =
            "add-transcode-target(type=videoProfile&context=streaming&protocol=hls&container=mpegts&videoCodec=h264,hevc&audioCodec=aac,ac3,eac3,mp3&replace=true)" +
                "+add-transcode-target-audio-codec(type=videoProfile&context=streaming&protocol=hls&audioCodec=ac3,eac3)" +
                "+add-direct-play-profile(type=videoProfile&container=mkv,mp4,mov,ts,avi,webm&videoCodec=*&audioCodec=*)"
        const val DECISION_FAILED_FROM = 2000
        const val RECENT_PLAY_MS = 10 * 60_000L
        const val PLAY_EXPIRY_MS = 6 * 60 * 60_000L
        const val TRANSCODE_STOP_WAIT_MS = 2_000L
        const val LEAVES_TTL_MS = 60_000L
        const val FRESH_LEAVES_MS = 10_000L
        const val LEAVES_EXCLUDED = "Media,Genre,Country,Director,Writer,Role,Producer,Similar,Collection,Field,Mood,Style,Rating"
        const val MAX_LEAVES = 16
        const val SEARCH_MAX = 100
        const val TITLE_SEARCH_LIMIT = 50
        const val ITEM_TTL_MS = 30_000L
        const val MAX_ITEMS = 32
        const val HUB_PEEK = 1
        const val HUB_ITEMS = 40
        val SKIPPED_HUBS = listOf("continue", "ondeck", "inprogress", "playlists")
        val SERIES_HUB_TYPES = setOf("show", "episode", "season")

        fun channelLabel(channels: Int): String = when (channels) {
            1 -> "1.0"
            2 -> "2.0"
            6 -> "5.1"
            8 -> "7.1"
            else -> "${channels}ch"
        }
    }
}

private fun TrackingExternalIds.sharesAnyWith(other: TrackingExternalIds): Boolean =
    (imdb != null && imdb == other.imdb) || (tmdb != null && tmdb == other.tmdb) || (tvdb != null && tvdb == other.tvdb)

private fun MediaWire.label(): String = when {
    !videoResolution.isNullOrBlank() -> videoResolution.uppercase().let { if (it.all(Char::isDigit)) "${it}p" else it }
    !container.isNullOrBlank() -> container.uppercase()
    else -> "Plex"
}

private fun StreamWire.label(): String =
    extendedDisplayTitle?.takeIf { it.isNotBlank() }
        ?: displayTitle?.takeIf { it.isNotBlank() }
        ?: title?.takeIf { it.isNotBlank() }
        ?: language?.takeIf { it.isNotBlank() }
        ?: codec?.uppercase()
        ?: id.toString()
