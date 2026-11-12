package com.nuvio.tv.data.mediaserver.silo

import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.data.mediaserver.DrawnArtworkWidths
import com.nuvio.tv.data.mediaserver.ServerCandidate
import com.nuvio.tv.data.mediaserver.ServerCapability
import com.nuvio.tv.data.mediaserver.ServerEpisode
import com.nuvio.tv.data.mediaserver.ServerIndexEntry
import com.nuvio.tv.data.mediaserver.ServerException
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerGenre
import com.nuvio.tv.data.mediaserver.ServerHomeRow
import com.nuvio.tv.data.mediaserver.ServerItemDetails
import com.nuvio.tv.data.mediaserver.ServerLibrary
import com.nuvio.tv.data.mediaserver.ServerLibraryFilter
import com.nuvio.tv.data.mediaserver.ServerLocation
import com.nuvio.tv.data.mediaserver.ServerMediaKind
import com.nuvio.tv.data.mediaserver.ServerPage
import com.nuvio.tv.data.mediaserver.ServerPlaybackRequest
import com.nuvio.tv.data.mediaserver.ServerPlaybackSession
import com.nuvio.tv.data.mediaserver.ServerProvider
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.data.mediaserver.ServerPlayMethod
import com.nuvio.tv.data.mediaserver.ServerPlaybackEvent
import com.nuvio.tv.data.mediaserver.ServerPlaybackEventType
import com.nuvio.tv.data.mediaserver.ServerPlaybackTarget
import com.nuvio.tv.data.mediaserver.ServerResumeEntry
import com.nuvio.tv.data.mediaserver.ServerChapter
import com.nuvio.tv.data.mediaserver.ServerSegment
import com.nuvio.tv.data.mediaserver.ServerSegmentKind
import com.nuvio.tv.data.mediaserver.ServerThumbnailFormat
import com.nuvio.tv.data.mediaserver.ServerTrack
import com.nuvio.tv.data.mediaserver.ServerTrickplay
import com.nuvio.tv.data.mediaserver.ServerTrickplaySet
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.data.mediaserver.ServerSession
import com.nuvio.tv.data.mediaserver.ServerSignIn
import com.nuvio.tv.data.mediaserver.ServerSignInIssue
import com.nuvio.tv.data.mediaserver.ServerSort
import com.nuvio.tv.data.mediaserver.ServerTitle
import com.nuvio.tv.data.mediaserver.ServerTitleQuery
import com.nuvio.tv.data.mediaserver.ServerUserState
import com.nuvio.tv.data.mediaserver.searchEach
import com.nuvio.tv.data.mediaserver.mediabrowser.MediaBrowserClient
import com.nuvio.tv.data.mediaserver.mediabrowser.ServerClientIdentity
import com.nuvio.tv.data.mediaserver.mediabrowser.dropsEncryption
import com.nuvio.tv.data.mediaserver.mediabrowser.normalizeServerAddress
import com.nuvio.tv.data.mediaserver.mediabrowser.pathSegment
import java.time.Instant
import java.util.Collections
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/** Silo through its own API (`/api/v2`): code, password and Tailscale sign-in, with Silo profiles. */
class SiloNativeProvider internal constructor(
    http: OkHttpClient,
    private val identity: ServerClientIdentity,
    private val now: () -> Long,
    private val kindName: (ServerMediaKind) -> String = { englishKindName(it) },
    private val drawnWidths: () -> DrawnArtworkWidths? = { DrawnArtworkWidths.current }
) : ServerProvider {
    constructor(
        http: OkHttpClient,
        identity: ServerClientIdentity,
        kindName: (ServerMediaKind) -> String = { englishKindName(it) }
    ) : this(http, identity, System::currentTimeMillis, kindName)

    override val id: String = ID
    override val displayName: String = "Silo"
    override val capabilities: Set<ServerCapability> = setOf(
        ServerCapability.SEARCH,
        ServerCapability.EXTERNAL_ID_LOOKUP,
        ServerCapability.USER_STATE_READ,
        ServerCapability.USER_STATE_WRITE,
        ServerCapability.HOME_ROWS
    )
    override val minimumVersion: String = ""

    private val client = MediaBrowserClient("Authorization", http, identity)
    private val json = client.json
    private val playStates = java.util.concurrent.ConcurrentHashMap<String, SiloPlayState>()
    private val installations = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val legacyQuality: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val playbackCapabilityCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, PlaybackCapabilitiesWire>>()
    private val recentlyPlayed = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val trickplayManifests = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, TrickplayWire>>()
    private val traversals = Collections.synchronizedMap(
        object : LinkedHashMap<String, Traversal>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Traversal>?): Boolean = size > MAX_TRAVERSALS
        }
    )

    /** The name the server shows for this sign-in in its list of signed-in devices. */
    private val deviceName: String
        get() = "Nuvio on ${identity.device}"

    suspend fun findServer(typed: String): SiloServer {
        val address = siloAddress(typed)
        return coroutineScope {
            val serverIdentity = async { serverId(address) }
            val providers = async { get(address, PROVIDERS_PATH, ProvidersWire.serializer()) }
            val branding = async { optional { get(address, BRANDING_PATH, BrandingWire.serializer()) } }
            val codes = async { optional { get(address, DEVICE_CAPABILITY_PATH, DeviceCapabilityWire.serializer()) } }
            val serverId = serverIdentity.await()
            val methods = providers.await()
            val network = methods.items.firstOrNull { it.mode == "network" && !it.networkSignInPath.isNullOrBlank() }
            val capability = codes.await()
            SiloServer(
                address = address,
                serverId = serverId,
                name = branding.await()?.serverName?.takeIf { it.isNotBlank() } ?: displayName,
                passwordSignIn = methods.passwordLogin || methods.items.any { it.mode == "credentials" },
                codeSignIn = capability == null || capability.state == "available",
                cancelsCodes = capability?.cancel ?: true,
                network = network?.let {
                    val person = it.networkIdentity
                    SiloNetworkSignIn(
                        name = person?.displayName?.takeIf(String::isNotBlank) ?: person?.username.orEmpty(),
                        path = it.networkSignInPath.orEmpty()
                    )
                }
            )
        }
    }

    suspend fun startCode(server: SiloServer): SiloCode {
        val startedAt = now()
        val reply = send(
            "POST",
            server.address,
            DEVICE_START_PATH,
            DeviceStartWire.serializer(),
            DeviceStartBody.serializer(),
            DeviceStartBody(deviceName.take(DEVICE_NAME_MAX))
        )
        if (reply.deviceCode.isBlank() || reply.userCode.isBlank()) throw ServerException(ServerFailure.FAILED)
        return SiloCode(
            deviceCode = reply.deviceCode,
            userCode = reply.userCode,
            link = reply.verificationUri,
            linkWithCode = reply.verificationUriComplete.ifBlank { reply.verificationUri },
            expiresAtMs = startedAt + reply.expiresIn.coerceAtLeast(1) * 1_000L,
            serverExpiresAt = reply.expiresAt,
            pollEveryMs = reply.interval.coerceAtLeast(1) * 1_000L
        )
    }

    suspend fun pollCode(server: SiloServer, code: SiloCode): SiloCodeStatus {
        val reply = try {
            send("POST", server.address, DEVICE_POLL_PATH, DevicePollWire.serializer(), DeviceCodeBody.serializer(), DeviceCodeBody(code.deviceCode))
        } catch (error: ServerException) {
            if (error.failure == ServerFailure.NOT_FOUND) return SiloCodeStatus.Ended(SiloCodeEnd.EXPIRED)
            throw error
        }
        val pollAfterMs = (reply.pollAfter ?: 0L).times(1_000L).coerceAtLeast(code.pollEveryMs)
        return when (reply.status) {
            "pending" -> SiloCodeStatus.Waiting(reply.opened, movedExpiry(code, reply.expiresAt), pollAfterMs)
            "approved" -> reply.tokens?.let { SiloCodeStatus.Approved(it.toTokens()) } ?: SiloCodeStatus.Ended(SiloCodeEnd.USED)
            "denied" -> SiloCodeStatus.Ended(SiloCodeEnd.DENIED)
            "canceled" -> SiloCodeStatus.Ended(SiloCodeEnd.CANCELED)
            "consumed" -> SiloCodeStatus.Ended(SiloCodeEnd.USED)
            "expired" -> SiloCodeStatus.Ended(SiloCodeEnd.EXPIRED)
            else -> SiloCodeStatus.Waiting(reply.opened, code.expiresAtMs, pollAfterMs)
        }
    }

    /** Silo pushes the end of a code back while someone has it open; the local deadline moves by the same amount. */
    private fun movedExpiry(code: SiloCode, serverExpiresAt: String?): Long {
        val moved = parseInstant(serverExpiresAt) ?: return code.expiresAtMs
        val original = parseInstant(code.serverExpiresAt) ?: return code.expiresAtMs
        return maxOf(code.expiresAtMs, code.expiresAtMs + (moved - original))
    }

    /** An abandoned code can't be approved later. */
    suspend fun cancelCode(server: SiloServer, code: SiloCode) {
        if (!server.cancelsCodes) return
        withContext(NonCancellable) {
            try {
                send<DeviceCodeBody, Unit>("POST", server.address, DEVICE_CANCEL_PATH, null, DeviceCodeBody.serializer(), DeviceCodeBody(code.deviceCode))
            } catch (_: Exception) {
            }
        }
    }

    suspend fun passwordSignIn(server: SiloServer, username: String, password: String): SiloTokens {
        val reply = try {
            send("POST", server.address, LOGIN_PATH, TokenPairWire.serializer(), LoginBody.serializer(), LoginBody(username.trim(), password))
        } catch (error: ServerException) {
            throw error.asSignInFailure()
        }
        return reply.toTokens().also { checkUsable(server, it) }
    }

    suspend fun networkSignIn(server: SiloServer): SiloTokens {
        val path = server.network?.path ?: throw ServerException(ServerFailure.UNSUPPORTED)
        val reply = try {
            send("POST", server.address, path, TokenPairWire.serializer(), EmptyBody.serializer(), EmptyBody())
        } catch (error: ServerException) {
            throw error.asSignInFailure()
        }
        return reply.toTokens().also { checkUsable(server, it) }
    }

    /** A temporary password has to be changed in Silo's web app before anything else works. */
    suspend fun checkUsable(server: SiloServer, tokens: SiloTokens) {
        if (!tokens.passwordChangeNeeded) return
        abandon(server, tokens)
        throw ServerException(ServerFailure.FORBIDDEN, issue = ServerSignInIssue.TEMPORARY_PASSWORD)
    }

    suspend fun profiles(server: SiloServer, tokens: SiloTokens): List<SiloProfile> = profiles(server.address, tokens.access)

    private suspend fun profiles(address: String, access: String): List<SiloProfile> =
        get(address, PROFILES_PATH, ProfilesWire.serializer(), bearer(access)).items
            .filter { it.id.isNotBlank() }
            .map { SiloProfile(it.id, it.name.ifBlank { it.id }, it.hasPin, it.isChild, it.isPrimary) }

    /** The profile's token, or null when the PIN is wrong. */
    suspend fun verifyPin(server: SiloServer, tokens: SiloTokens, profile: SiloProfile, pin: String): String? =
        verifyPin(server.address, tokens.access, profile, pin)

    private suspend fun verifyPin(address: String, access: String, profile: SiloProfile, pin: String): String? {
        if (pin.isBlank()) return null
        val reply = send(
            "POST",
            address,
            "$PROFILES_PATH/${pathSegment(profile.id)}/verify-pin",
            PinWire.serializer(),
            PinBody.serializer(),
            PinBody(pin),
            bearer(access)
        )
        return reply.profileToken?.takeIf { reply.valid && it.isNotBlank() }
    }

    fun signIn(server: SiloServer, tokens: SiloTokens, profile: SiloProfile, profileToken: String?): ServerSignIn =
        ServerSignIn(
            address = server.address,
            serverName = server.name,
            serverId = server.serverId,
            userId = tokens.userId,
            userName = tokens.userName,
            token = encode(
                SiloCredential(
                    access = tokens.access,
                    refresh = tokens.refresh,
                    accessExpiresAtMs = tokens.accessExpiresAtMs,
                    profileId = profile.id,
                    profileName = profile.name,
                    profileToken = profileToken?.takeIf { profile.hasPin }
                )
            )
        )

    suspend fun profiles(session: ServerSession): List<SiloProfile> {
        val credential = credentialOrNull(session.token) ?: throw ServerException(ServerFailure.AUTH_REQUIRED)
        return profiles(session.address, credential.access)
    }

    /** [session]'s token moved to [profile]; null when the PIN is wrong. */
    suspend fun withProfile(session: ServerSession, profile: SiloProfile, pin: String?): String? {
        val credential = credentialOrNull(session.token) ?: throw ServerException(ServerFailure.AUTH_REQUIRED)
        val profileToken = if (profile.hasPin) {
            verifyPin(session.address, credential.access, profile, pin.orEmpty()) ?: return null
        } else {
            null
        }
        return encode(credential.copy(profileId = profile.id, profileName = profile.name, profileToken = profileToken))
    }

    /** Ends a sign-in that was never saved, so it doesn't stay in the server's list of devices. */
    suspend fun abandon(server: SiloServer, tokens: SiloTokens) = logout(server.address, tokens.access)

    override suspend fun locate(address: String): ServerLocation? {
        val normalized = normalizeServerAddress(address) ?: return null
        val serverId = try {
            serverId(normalized)
        } catch (_: ServerException) {
            return null
        }
        return ServerLocation(normalized, serverId)
    }

    /**
     * Silo's own id for the server. Silo versions without the identity document get the id their Jellyfin-compatible
     * side reports at the same address, else the address itself.
     */
    private suspend fun serverId(address: String): String {
        try {
            return get(address, IDENTITY_PATH, IdentityWire.serializer()).serverId.takeIf { it.isNotBlank() }
                ?: throw notSilo()
        } catch (error: ServerException) {
            if (error.failure != ServerFailure.NOT_FOUND) throw error
        }
        val compatible = optional { get(address, JELLYFIN_INFO_PATH, JellyfinInfoWire.serializer()) }
        return compatible?.id?.takeIf { it.isNotBlank() } ?: address
    }

    override suspend fun libraries(session: ServerSession): List<ServerLibrary> =
        get(session.address, USER_LIBRARIES_PATH, UserLibrariesWire.serializer(), authHeaders(session)).items
            .sortedBy { it.sortOrder }
            .flatMap { library ->
                val name = library.name.ifBlank { library.id }
                when {
                    libraryKind(library.type) != null ->
                        listOf(ServerLibrary(id = library.id, name = name, kind = libraryKind(library.type)!!))
                    isMixed(library.type) -> listOf(ServerMediaKind.MOVIE, ServerMediaKind.SERIES).map { kind ->
                        ServerLibrary(id = "${library.id}$PART${kind.contentType}", name = "$name · ${kindName(kind)}", kind = kind)
                    }
                    else -> emptyList()
                }
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
            ServerSort.TITLE -> "title"
            ServerSort.RELEASED -> "-release_date"
            ServerSort.ADDED -> "-added_at"
        }
        val query = libraryQuery(library, sort = order, drawn("medium")) + mapOf(
            "genre" to genreId?.takeIf { it.isNotBlank() },
            "name_prefix" to filter.letter?.lowercaseChar()?.toString(),
            "groups" to UNWATCHED_GROUPS.takeIf { filter.unwatched }
        )
        val page = catalogPage(session, "library", query, start, limit)
        val mapper = mapper(session)
        return ServerPage(page.items.mapNotNull(mapper::title), page.total.takeIf { page.totalExact })
    }

    override suspend fun libraryGenres(session: ServerSession, library: ServerLibrary): List<ServerGenre> =
        get(
            session.address,
            CATALOG_FILTERS_PATH,
            CatalogFiltersWire.serializer(),
            authHeaders(session),
            mapOf("library_id" to library.id.substringBefore(PART), "skip_technical" to "true")
        ).genres.map { it.trim() }.filter { it.isNotEmpty() }.distinct().map { ServerGenre(it, it) }

    override suspend fun search(
        session: ServerSession,
        kind: ServerMediaKind,
        libraries: List<ServerLibrary>,
        query: String,
        limit: Int
    ): List<ServerTitle> {
        val mapper = mapper(session)
        val items = searchEach(libraries) { library ->
            get(
                session.address,
                CATALOG_PATH,
                CatalogPageWire.serializer(),
                authHeaders(session),
                libraryQuery(library, sort = null, drawn("medium")) + mapOf(
                    "q" to query.trim(),
                    "limit" to limit.coerceIn(1, SEARCH_MAX).toString()
                )
            ).items
        }
        return items.mapNotNull(mapper::title).distinctBy { it.preview.id }.take(limit)
    }

    override suspend fun details(session: ServerSession, itemId: String): ServerItemDetails {
        val item = item(session, itemId, drawnBackdrop("large"))
        val episodes = if (siloKind(item.type) == ServerMediaKind.SERIES) episodes(session, itemId) else emptyList()
        val mapper = mapper(session)
        val states = if (episodes.isEmpty()) listOfNotNull(mapper.userState(item)) else episodes.mapNotNull(mapper::userState)
        return ServerItemDetails(meta = mapper.details(item, episodes), externalIds = item.externalIds(), userStates = states)
    }

    override suspend fun externalIds(session: ServerSession, itemId: String): TrackingExternalIds =
        item(session, itemId, mapOf("image_size" to "small")).externalIds()

    override suspend fun setPlayed(session: ServerSession, itemId: String, played: Boolean) {
        // A title being played is marked by Silo itself when the play ends past its watched point.
        val justPlayed = recentlyPlayed[itemId]?.let { now() - it < RECENT_PLAY_MS } == true
        if (played && (justPlayed || playStates.values.any { it.started && it.itemId == itemId })) return
        send<Unit, Unit>(
            if (played) "POST" else "DELETE",
            session.address,
            "$WATCHED_PATH/${pathSegment(itemId)}",
            null,
            null,
            null,
            authHeaders(session)
        )
    }

    override suspend fun resumeItems(session: ServerSession, limit: Int): List<ServerResumeEntry> {
        val layout = get(session.address, HOME_LAYOUT_PATH, HomeLayoutWire.serializer(), authHeaders(session))
        val section = layout.sections.firstOrNull { it.sectionType == "continue_watching" } ?: return emptyList()
        val items = get(
            session.address,
            "$HOME_SECTIONS_PATH/${pathSegment(section.id)}/items",
            HomeSectionWire.serializer(),
            authHeaders(session),
            drawn("medium")
        ).items
        val allowed = session.connection.selectedLibraries.map { it.kind.contentType }.toSet()
        val mapper = mapper(session)
        return items
            .filter { it.itemSource != "next_up" && (it.positionSeconds ?: 0.0) > 0.0 }
            .mapNotNull { item ->
                val title = mapper.resumeTitle(item) ?: return@mapNotNull null
                val state = mapper.userState(item)?.copy(played = false) ?: return@mapNotNull null
                ServerResumeEntry(title, state)
            }
            .filter { it.title.preview.apiType in allowed }
            .distinctBy { it.title.preview.id }
            .take(limit)
    }

    override suspend fun externalIdIndex(
        session: ServerSession,
        library: ServerLibrary,
        start: Int,
        limit: Int
    ): ServerPage<ServerIndexEntry> {
        val mapper = mapper(session)
        val entries = mutableListOf<ServerIndexEntry>()
        var total: Int? = null
        while (entries.size < limit) {
            val size = minOf(CATALOG_MAX, limit - entries.size)
            val page = catalogPage(session, "index", libraryQuery(library, sort = "title", mapOf("image_size" to "small")), start + entries.size, size)
            entries += page.items.map(mapper::indexEntry)
            total = page.total.takeIf { page.totalExact }
            if (page.items.size < size || page.page?.hasMore != true) break
        }
        return ServerPage(entries, total)
    }

    /** Silo names most titles after an external id, so a title is usually one read away; otherwise a title search. */
    override suspend fun lookupTitle(
        session: ServerSession,
        library: ServerLibrary,
        query: ServerTitleQuery
    ): List<ServerIndexEntry> {
        val found = mutableListOf<ServerIndexEntry>()
        for (contentId in contentIdCandidates(library.kind, query.ids)) {
            val item = try {
                item(session, contentId, mapOf("image_size" to "small"))
            } catch (error: ServerException) {
                if (error.failure == ServerFailure.NOT_FOUND) continue
                throw error
            }
            if (siloKind(item.type) == library.kind) found += ServerIndexEntry(item.contentId, item.externalIds(), item.year)
            if (found.isNotEmpty()) return found
        }
        val name = query.name?.trim()?.takeIf { it.isNotEmpty() } ?: return found
        val text = query.year?.let { "$name $it" } ?: name
        val reply = get(
            session.address,
            CATALOG_PATH,
            CatalogPageWire.serializer(),
            authHeaders(session),
            libraryQuery(library, sort = null, mapOf("image_size" to "small")) + mapOf("q" to text, "limit" to TITLE_SEARCH_LIMIT.toString())
        )
        val mapper = mapper(session)
        return reply.items.filter { siloKind(it.type) == library.kind }.map(mapper::indexEntry)
    }

    override suspend fun findEpisode(session: ServerSession, seriesItemId: String, season: Int, episode: Int): ServerEpisode? {
        episodeContentId(seriesItemId, season, episode)?.let { contentId ->
            try {
                val item = item(session, contentId, mapOf("image_size" to "small"))
                if (item.type.equals("episode", ignoreCase = true) && item.seasonNumber == season && item.episodeNumber == episode) {
                    return ServerEpisode(item.contentId, item.airDate)
                }
            } catch (error: ServerException) {
                if (error.failure != ServerFailure.NOT_FOUND) throw error
            }
        }
        val match = try {
            seasonEpisodes(session, seriesItemId, season).filter { it.episodeNumber == episode }.singleOrNull()
        } catch (error: ServerException) {
            if (error.failure == ServerFailure.NOT_FOUND) return null
            throw error
        }
        return match?.let { ServerEpisode(it.contentId, it.airDate) }
    }

    override suspend fun episodeStates(session: ServerSession, seriesItemId: String): List<ServerUserState> {
        val mapper = mapper(session)
        return episodes(session, seriesItemId).mapNotNull(mapper::userState)
    }

    /** Silo's Home rows for the profile, without Continue Watching (Nuvio has its own) and rows with nothing to watch. */
    override suspend fun homeRows(session: ServerSession): List<ServerHomeRow> =
        homeSections(session)
            .filter { it.id.isNotBlank() && it.sectionType != "continue_watching" }
            .mapNotNull { section ->
                val kinds = section.items.mapNotNull { siloKind(it.type) ?: ServerMediaKind.SERIES.takeIf { _ -> it.isEpisodeOrSeason() } }
                val kind = kinds.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: return@mapNotNull null
                ServerHomeRow(section.id, section.title.ifBlank { section.id }, kind)
            }

    /**
     * The rows with a few cards each, enough to tell what a row holds. A row emptied by the smaller read (Silo leaves
     * out cards other rows already show) would look like an empty row, so those ask again in full.
     */
    private suspend fun homeSections(session: ServerSession): List<HomeSectionWire> {
        suspend fun read(limit: Int?) = get(
            session.address,
            HOME_SECTIONS_PATH,
            HomeLayoutWire.serializer(),
            authHeaders(session),
            drawn("small") + mapOf("item_limit" to limit?.toString())
        ).sections
        val most = optional {
            get(session.address, HOME_CAPABILITIES_PATH, HomeCapabilitiesWire.serializer(), authHeaders(session))
        }?.sectionItemLimitMax?.takeIf { it > 0 } ?: return read(null)
        val sections = read(minOf(HOME_ROW_KIND_CARDS, most))
        return if (sections.any { it.items.isEmpty() && (it.totalCount ?: 0) > 0 }) read(null) else sections
    }

    override suspend fun homeRowItems(session: ServerSession, rowId: String): List<ServerTitle> {
        val items = get(
            session.address,
            "$HOME_SECTIONS_PATH/${pathSegment(rowId)}/items",
            HomeSectionWire.serializer(),
            authHeaders(session),
            drawn("medium")
        ).items
        val mapper = mapper(session)
        return items
            .mapNotNull { item -> if (item.isEpisodeOrSeason()) mapper.resumeTitle(item) else mapper.title(item) }
            .distinctBy { it.preview.id }
    }

    private fun CatalogItemWire.isEpisodeOrSeason(): Boolean =
        type.equals("episode", ignoreCase = true) || type.equals("season", ignoreCase = true)

    /** Fresh artwork links for [contentId]; Silo's signed links run out after a few hours. */
    internal suspend fun freshArtwork(session: ServerSession, contentId: String): Map<SiloArtworkKind, String> {
        val item = item(session, contentId, drawn("medium"))
        val mapper = mapper(session)
        return buildMap {
            mapper.artwork(item.posterUrl)?.let { put(SiloArtworkKind.POSTER, it) }
            mapper.artwork(item.backdropUrl)?.let { put(SiloArtworkKind.BACKGROUND, it) }
            mapper.artwork(item.logoUrl)?.let { put(SiloArtworkKind.LOGO, it) }
            mapper.artwork(item.thumbUrl)?.let { put(SiloArtworkKind.LANDSCAPE, it) }
        }
    }

    private suspend fun item(session: ServerSession, contentId: String, pictures: Map<String, String?>): CatalogItemWire =
        get(
            session.address,
            "$CATALOG_ITEMS_PATH/${pathSegment(contentId)}",
            CatalogItemWire.serializer(),
            authHeaders(session),
            pictures
        )

    /**
     * Artwork at the width Home draws it on this screen. Silo from mid October 2026 picks the nearest size it holds,
     * backdrops up to 2560 wide; older servers ignore the widths and go by [imageSize].
     */
    private fun drawn(imageSize: String): Map<String, String?> {
        val widths = drawnWidths() ?: return mapOf("image_size" to imageSize)
        return mapOf(
            "image_size" to imageSize,
            "poster_width" to widths.poster.toString(),
            "backdrop_width" to widths.backdrop.toString(),
            "logo_width" to widths.logo.toString()
        )
    }

    /** A title's own page draws its poster and logo larger than Home does: only the backdrop goes by the screen. */
    private fun drawnBackdrop(imageSize: String): Map<String, String?> =
        mapOf("image_size" to imageSize) + drawnWidths()?.let { mapOf("backdrop_width" to it.backdrop.toString()) }.orEmpty()

    /** Every episode of a series, a few seasons at a time to stay inside Silo's request limit. */
    private suspend fun episodes(session: ServerSession, seriesId: String): List<EpisodeWire> {
        val seasons = get(
            session.address,
            "$SERIES_PATH/${pathSegment(seriesId)}/seasons",
            SeasonsWire.serializer(),
            authHeaders(session),
            mapOf("include_artwork" to "false")
        ).items.map { it.seasonNumber }.distinct().sorted()
        return seasons.chunked(SEASONS_AT_ONCE).flatMap { chunk ->
            coroutineScope { chunk.map { number -> async { seasonEpisodes(session, seriesId, number) } }.awaitAll() }.flatten()
        }
    }

    private suspend fun seasonEpisodes(session: ServerSession, seriesId: String, season: Int): List<EpisodeWire> =
        get(
            session.address,
            "$SERIES_PATH/${pathSegment(seriesId)}/seasons/$season/episodes",
            EpisodesWire.serializer(),
            authHeaders(session),
            mapOf("image_size" to "small")
        ).items.filter { it.contentId.isNotBlank() }

    private fun libraryQuery(library: ServerLibrary, sort: String?, pictures: Map<String, String?>): Map<String, String?> = mapOf(
        "source" to "query",
        "library_id" to library.id.substringBefore(PART),
        "type" to library.kind.takeIf { it == ServerMediaKind.MOVIE || it == ServerMediaKind.SERIES }?.contentType,
        "sort" to sort
    ) + pictures

    /**
     * One page of a catalog list at an offset. Silo pages with cursors, so the cursor for the next page is kept,
     * and a page reached any other way is found with Silo's seek from the list's window cursor.
     */
    private suspend fun catalogPage(
        session: ServerSession,
        purpose: String,
        query: Map<String, String?>,
        start: Int,
        limit: Int
    ): CatalogPageWire {
        val profile = credentialOrNull(session.token)?.profileId.orEmpty()
        val key = listOf(session.connection.id, profile, purpose, query.toString(), limit).joinToString("|")
        val pageQuery = query + ("limit" to limit.toString())
        repeat(2) { attempt ->
            val traversal = traversals[key]?.takeIf { now() - it.createdAtMs < TRAVERSAL_TTL_MS } ?: Traversal(now())
            if (traversals[key] !== traversal) traversals[key] = traversal
            val position: Map<String, String?> = when {
                start == 0 -> emptyMap()
                traversal.cursors[start] != null -> mapOf("cursor" to traversal.cursors[start])
                else -> mapOf(
                    "cursor" to (traversal.window ?: catalogPage(session, purpose, query, 0, limit).let { traversals[key]?.window }),
                    "seek" to start.toString()
                )
            }
            val reply = try {
                get(session.address, CATALOG_PATH, CatalogPageWire.serializer(), authHeaders(session), pageQuery + position)
            } catch (error: ServerException) {
                if (error.code != "invalid_cursor" || attempt > 0) throw error
                traversals.remove(key)
                return@repeat
            }
            reply.windowCursor?.takeIf { it.isNotBlank() }?.let { traversal.window = it }
            val next = reply.page?.nextCursor?.takeIf { reply.page.hasMore && it.isNotBlank() }
            if (next != null) traversal.cursors[start + limit] = next
            return reply
        }
        throw ServerException(ServerFailure.FAILED)
    }

    private fun mapper(session: ServerSession) = SiloNativeMapper(session.address, session.connection.id)

    private class Traversal(val createdAtMs: Long) {
        @Volatile
        var window: String? = null
        val cursors: MutableMap<Int, String> = java.util.concurrent.ConcurrentHashMap()
    }

    override suspend fun candidates(session: ServerSession, itemId: String): List<ServerCandidate> {
        val ref = ServerItemRef(session.connection.id, itemId)
        return watchState(session, itemId).versions.filter { it.fileId.isNotBlank() }.map { version ->
            ServerCandidate(
                target = ServerPlaybackTarget(ref, version.fileId),
                title = version.resolution.ifBlank { version.container.uppercase() },
                filename = version.fileName,
                sizeBytes = version.fileSize?.takeIf { it > 0 },
                bitrateBps = version.bitrate?.takeIf { it > 0 }?.times(1_000L),
                video = version.videoLabel(),
                audio = (version.audioTracks.firstOrNull { it.default } ?: version.audioTracks.firstOrNull())?.label(),
                container = version.container.takeIf { it.isNotBlank() }?.uppercase()?.let { if (it == "MATROSKA") "MKV" else it },
                versionName = version.editionRaw?.trim()?.takeIf { it.isNotEmpty() },
                durationMs = version.durationSeconds?.takeIf { it > 0 }?.times(1_000L)
            )
        }
    }

    override suspend fun preparePlayback(session: ServerSession, request: ServerPlaybackRequest): ServerPlaybackSession {
        val credential = credentialOrNull(session.token) ?: throw ServerException(ServerFailure.AUTH_REQUIRED)
        val itemId = request.target.item.itemId
        val installationId = installationId(session)
        val state = watchState(session, itemId)
        val fileId = request.target.mediaSourceId
            ?: state.userData?.lastFileId?.takeIf { last -> state.versions.any { it.fileId == last } }
            ?: state.versions.firstOrNull()?.fileId
            ?: throw ServerException(ServerFailure.NOT_FOUND)
        val direct = request.capabilities.allowDirectPlay
        val attemptId = java.util.UUID.randomUUID().toString()
        fun startBody(legacy: Boolean) = siloStartBody(
            installationId = installationId,
            fileId = fileId,
            profileId = credential.profileId,
            attemptId = attemptId,
            direct = direct,
            audioIndex = request.audioStreamIndex,
            subtitleIndex = request.subtitleStreamIndex,
            model = identity.device,
            appVersion = identity.version,
            legacyQuality = legacy
        )
        val key = cacheKey(session)
        var body = startBody(key in legacyQuality)
        val decision = try {
            sendJson(session, "POST", PLAYBACK_START_PATH, body.toString(), DecisionWire.serializer())
        } catch (error: ServerException) {
            if (error.httpStatus != 422 && error.code != VALIDATION_FAILED) throw error
            // Rejected in the old form as well: the next play asks in the new form again.
            if (key in legacyQuality) {
                legacyQuality -= key
                throw error
            }
            body = startBody(legacy = true)
            sendJson(session, "POST", PLAYBACK_START_PATH, body.toString(), DecisionWire.serializer())
                .also { legacyQuality += key }
        }
        val started = decision.plan
        val startedSession = decision.sessionId
        if (decision.outcome != "playable" || started == null || startedSession.isNullOrBlank() || started.stream.url.isBlank()) {
            if (installationIdChanged(decision)) installations.remove(cacheKey(session))
            val failure = if (decision.terminal?.reason in STREAM_LIMIT_REASONS) ServerFailure.STREAM_LIMIT else ServerFailure.UNSUPPORTED
            throw ServerException(failure, decision.terminal?.message, detail = decision.terminal?.message, code = decision.terminal?.reason)
        }
        val (sessionId, plan) = seekablePlan(session, body, startedSession, started)
        val effectiveFile = plan.effectiveMediaFileId?.takeIf { it.isNotBlank() } ?: fileId
        val version = state.versions.firstOrNull { it.fileId == effectiveFile }
        val method = when {
            plan.delivery == "original_http" -> ServerPlayMethod.DIRECT_PLAY
            plan.delivery.startsWith("server_remux") -> ServerPlayMethod.DIRECT_STREAM
            else -> ServerPlayMethod.TRANSCODE
        }
        val subtitles = plan.subtitle.inventory
            .filter { it.delivery == "sidecar" && !it.url.isNullOrBlank() }
            .filter { method != ServerPlayMethod.DIRECT_PLAY || it.source != "embedded" }
            .mapNotNull { entry ->
                val url = playbackUrl(session, entry.url) ?: return@mapNotNull null
                Subtitle(
                    id = "silo:${entry.trackId}",
                    url = url,
                    lang = entry.language?.takeIf { it.isNotBlank() } ?: "und",
                    addonName = entry.label?.takeIf { it.isNotBlank() } ?: displayName,
                    addonLogo = null,
                    isStreamProvided = true,
                    headers = authHeaders(session)
                )
            }
        val selectedAudio = plan.selectedTracks.audio?.index
        val selectedSubtitle = plan.selectedTracks.subtitle?.index
        val duration = plan.source.durationSeconds?.takeIf { it > 0.0 } ?: version?.durationSeconds?.toDouble() ?: 0.0
        playStates[sessionId] = SiloPlayState(installationId, itemId, plan.timeline.timelineOffsetSeconds, duration)
        return ServerPlaybackSession(
            target = ServerPlaybackTarget(request.target.item, effectiveFile),
            mediaSourceId = effectiveFile,
            url = playbackUrl(session, plan.stream.url) ?: throw ServerException(ServerFailure.FAILED),
            headers = authHeaders(session),
            subtitles = subtitles,
            playSessionId = sessionId,
            playMethod = method,
            audioTracks = version?.audioTracks.orEmpty().mapIndexed { index, track ->
                ServerTrack(index, track.label(), track.language, selected = index == selectedAudio)
            },
            burnInSubtitles = plan.subtitle.inventory.filter { it.delivery == "burn_in_only" && !it.unplayable() }.map { entry ->
                ServerTrack(entry.combinedIndex, entry.label ?: entry.language ?: entry.combinedIndex.toString(), entry.language, entry.combinedIndex == selectedSubtitle)
            },
            transcodeReasons = if (method == ServerPlayMethod.DIRECT_PLAY) {
                emptyList()
            } else {
                listOfNotNull(plan.decisionReason) + plan.degradationWarnings.map { it.code }
            },
            transcodeOffered = direct && "server_transcode_hls" in playbackCapabilities(session).deliveries
        )
    }

    /**
     * Swaps a copy remux (a repackaged file that only moves when Silo is asked again for every seek) for another route
     * when Silo has one, normally a conversion the player can seek in. Without one the copy is kept: it starts at 0, so
     * its positions still match the file. A conversion of a file whose length Silo does not know cannot be seeked
     * either, so there is nothing to gain then.
     */
    private suspend fun seekablePlan(
        session: ServerSession,
        start: JsonObject,
        sessionId: String,
        started: PlanWire
    ): Pair<String, PlanWire> {
        var current = sessionId to started
        val attempted = mutableListOf<String>()
        while (current.second.needsSeekableRoute() && attempted.size < MAX_REPLANS) {
            val plan = current.second
            attempted += plan.planAttemptKey
            val body = siloReplanBody(start, plan, attempted, java.util.UUID.randomUUID().toString())
            val next = try {
                sendJson(session, "POST", "$PLAYBACK_PATH/${pathSegment(current.first)}/replan", body.toString(), DecisionWire.serializer())
            } catch (_: ServerException) {
                break
            }
            val nextPlan = next.plan?.takeIf { next.outcome == "playable" && it.stream.url.isNotBlank() } ?: break
            current = (next.sessionId?.takeIf { it.isNotBlank() } ?: current.first) to nextPlan
        }
        return current
    }

    private fun PlanWire.needsSeekableRoute(): Boolean =
        !timeline.canSeekAnywhere && delivery.startsWith("server_remux") && (source.durationSeconds ?: 0.0) > 0.0 &&
            planId.isNotBlank() && planAttemptKey.isNotBlank()

    override suspend fun report(session: ServerSession, playback: ServerPlaybackSession, event: ServerPlaybackEvent) {
        val sessionId = playback.playSessionId ?: return
        val state = playStates[sessionId] ?: return
        if (event.type != ServerPlaybackEventType.STOP) state.started = true
        val position = event.positionMs.coerceAtLeast(0L) / 1_000.0 + state.timelineOffsetSeconds
        val path = "$PLAYBACK_PATH/${pathSegment(sessionId)}"
        try {
            when (event.type) {
                ServerPlaybackEventType.START -> Unit
                ServerPlaybackEventType.STOP -> {
                    playStates.remove(sessionId)
                    if (state.durationSeconds > 0.0 && position >= state.durationSeconds * WatchProgress.COMPLETED_THRESHOLD) {
                        recentlyPlayed[state.itemId] = now()
                    }
                    val body = siloStopBody(state.installationId, java.util.UUID.randomUUID().toString(), state.nextSequence(), position)
                    stopWithRetries(session, path, body.toString())
                }
                else -> {
                    val body = siloProgressBody(state.installationId, state.nextSequence(), position, event.isPaused)
                    sendJson<Unit>(session, "POST", "$path/progress", body.toString(), null)
                }
            }
        } catch (error: ServerException) {
            if (error.failure != ServerFailure.NOT_FOUND && error.httpStatus != SESSION_ENDED_STATUS) throw error
            playStates.remove(sessionId)
        }
    }

    /** Silo keeps the first stop; the same stop is sent again after a lost connection or a busy server. */
    private suspend fun stopWithRetries(session: ServerSession, path: String, body: String) {
        repeat(STOP_TRIES) { attempt ->
            try {
                sendJson<Unit>(session, "DELETE", path, body, null)
                return
            } catch (error: ServerException) {
                if (error.failure != ServerFailure.UNREACHABLE || attempt == STOP_TRIES - 1) throw error
                kotlinx.coroutines.delay(STOP_RETRY_MS * (attempt + 1))
            }
        }
    }

    override val thumbnailFormat: ServerThumbnailFormat = ServerThumbnailFormat.TRICKPLAY

    override suspend fun trickplay(session: ServerSession, itemId: String, mediaSourceId: String): ServerTrickplaySet? {
        val version = watchState(session, itemId).versions.firstOrNull { it.fileId == mediaSourceId }
        if (version?.trickplayAvailable != true) return null
        val manifest = trickplayManifest(session, itemId, mediaSourceId) ?: return null
        val level = with(manifest) {
            if (thumbnailWidth <= 0 || thumbnailHeight <= 0 || tileColumns <= 0 || tileRows <= 0 ||
                thumbnailCount <= 0 || intervalMs <= 0 || sheets.isEmpty()
            ) {
                return null
            }
            ServerTrickplay(thumbnailWidth, thumbnailHeight, tileColumns, tileRows, thumbnailCount, intervalMs)
        }
        return ServerTrickplaySet(mediaSourceId, listOf(level))
    }

    /** Silo's sheet links carry their own permission, like its artwork, so they are fetched without the sign-in. */
    override suspend fun trickplaySheet(
        session: ServerSession,
        itemId: String,
        mediaSourceId: String,
        width: Int,
        index: Int,
        maxBytes: Long
    ): ByteArray {
        val manifest = trickplayManifest(session, itemId, mediaSourceId) ?: throw ServerException(ServerFailure.NOT_FOUND)
        val sheet = manifest.sheets.firstOrNull { it.index == index } ?: throw ServerException(ServerFailure.NOT_FOUND)
        val url = playbackUrl(session, sheet.url) ?: throw ServerException(ServerFailure.NOT_FOUND)
        val reply = client.bytes(url, token = null, maxBytes = maxBytes, signed = false)
        if (reply.truncated) throw ServerException(ServerFailure.FAILED, "sheet over size limit")
        return reply.bytes
    }

    private suspend fun trickplayManifest(session: ServerSession, itemId: String, fileId: String): TrickplayWire? {
        val key = cacheKey(session) + "|" + itemId + "|" + fileId
        trickplayManifests[key]?.takeIf { now() < it.first }?.let { return it.second }
        val manifest = try {
            get(
                session.address,
                "$WATCH_PATH/${pathSegment(itemId)}/trickplay",
                TrickplayWire.serializer(),
                authHeaders(session) + deviceHeaders(),
                mapOf("file_id" to fileId)
            )
        } catch (error: ServerException) {
            if (error.failure == ServerFailure.NOT_FOUND) return null
            throw error
        }
        val renewAt = parseInstant(manifest.expiresAt)?.minus(TRICKPLAY_RENEW_MS) ?: Long.MAX_VALUE
        if (trickplayManifests.size >= TRICKPLAY_MANIFESTS) trickplayManifests.clear()
        trickplayManifests[key] = minOf(renewAt, now() + TRICKPLAY_TTL_MS) to manifest
        return manifest
    }

    override suspend fun segments(session: ServerSession, itemId: String, mediaSourceId: String?): List<ServerSegment> = try {
        val versions = watchStateOf(session, itemId, mediaSourceId).versions
        val version = versions.firstOrNull { it.fileId == mediaSourceId } ?: versions.firstOrNull()
        version?.markerSegments.orEmpty().mapNotNull { marker ->
            val kind = when (marker.kind.lowercase()) {
                "intro" -> ServerSegmentKind.INTRO
                "recap" -> ServerSegmentKind.RECAP
                "credits", "outro" -> ServerSegmentKind.OUTRO
                "preview" -> ServerSegmentKind.PREVIEW
                else -> return@mapNotNull null
            }
            val start = (marker.startSeconds * 1_000).toLong()
            val end = (marker.endSeconds * 1_000).toLong()
            ServerSegment(kind, start, end).takeIf { end > start }
        }
    } catch (_: ServerException) {
        emptyList()
    }

    override suspend fun chapters(session: ServerSession, itemId: String, mediaSourceId: String?): List<ServerChapter> = try {
        val versions = watchStateOf(session, itemId, mediaSourceId).versions
        val version = versions.firstOrNull { it.fileId == mediaSourceId } ?: versions.firstOrNull()
        version?.chapters.orEmpty().mapNotNull { chapter ->
            val start = chapter.startSeconds?.takeIf { it >= 0.0 } ?: return@mapNotNull null
            ServerChapter((start * 1_000).toLong(), chapter.title?.trim()?.takeIf { it.isNotEmpty() })
        }
    } catch (_: ServerException) {
        emptyList()
    }

    /** Silo sends plays to the tracking services a profile connected in Silo itself; those are left to Silo. */
    override suspend fun scrobbledServices(session: ServerSession): Set<String> = coroutineScope {
        SCROBBLE_SERVICES.map { service ->
            async {
                try {
                    val connection = get(
                        session.address,
                        "$WATCH_PROVIDERS_PATH/$service/connection",
                        WatchProviderConnectionWire.serializer(),
                        authHeaders(session)
                    )
                    service.takeIf {
                        connection.connected && connection.scrobbleEnabled && connection.capabilities?.scrobblePlayback != false
                    }
                } catch (error: ServerException) {
                    if (error.failure == ServerFailure.AUTH_REQUIRED) throw error
                    null
                }
            }
        }.awaitAll().filterNotNull().toSet()
    }

    private suspend fun watchState(session: ServerSession, itemId: String): WatchStateWire =
        get(session.address, "$WATCH_PATH/${pathSegment(itemId)}", WatchStateWire.serializer(), authHeaders(session) + deviceHeaders())

    /** Silo fills on-demand markers only for the file it is told about; an old server may refuse the parameter. */
    private suspend fun watchStateOf(session: ServerSession, itemId: String, fileId: String?): WatchStateWire {
        if (fileId.isNullOrBlank()) return watchState(session, itemId)
        return try {
            get(
                session.address,
                "$WATCH_PATH/${pathSegment(itemId)}",
                WatchStateWire.serializer(),
                authHeaders(session) + deviceHeaders(),
                mapOf("file_id" to fileId)
            )
        } catch (error: ServerException) {
            if (error.httpStatus != 422) throw error
            watchState(session, itemId)
        }
    }

    private suspend fun playbackCapabilities(session: ServerSession): PlaybackCapabilitiesWire {
        val key = cacheKey(session)
        return playbackCapabilityCache[key]?.takeIf { now() - it.first < CAPABILITIES_TTL_MS }?.second
            ?: get(session.address, PLAYBACK_CAPABILITIES_PATH, PlaybackCapabilitiesWire.serializer(), authHeaders(session))
                .also { playbackCapabilityCache[key] = now() to it }
    }

    private fun cacheKey(session: ServerSession): String =
        session.address + "|" + credentialOrNull(session.token)?.profileId.orEmpty()

    /** A link from a playback plan: root-relative to the address Silo is used at, path prefix included. */
    private fun playbackUrl(session: ServerSession, url: String?): String? {
        val value = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true) -> value
            value.startsWith("/") -> session.address.trimEnd('/') + value
            else -> null
        }
    }

    /** The server's playback installation, which every playback call carries; Silo playback off or too old says so. */
    private suspend fun installationId(session: ServerSession): String {
        installations[cacheKey(session)]?.let { return it }
        val document = playbackCapabilities(session)
        val usable = document.state == "available" && document.allowed && PLAYBACK_PROTOCOL in document.protocolVersions &&
            "sequenced_progress_v1" in document.features
        val id = document.installationId?.takeIf { usable && it.isNotBlank() } ?: throw ServerException(ServerFailure.UNSUPPORTED)
        installations[cacheKey(session)] = id
        return id
    }

    private fun installationIdChanged(decision: DecisionWire): Boolean = decision.terminal?.reason == "installation_changed"

    private suspend fun <T> sendJson(
        session: ServerSession,
        method: String,
        path: String,
        body: String,
        reply: DeserializationStrategy<T>?
    ): T {
        val response = try {
            client.execute(
                method,
                session.address,
                path,
                token = null,
                body = body,
                authorization = authHeaders(session)[AUTHORIZATION],
                headers = baseHeaders() + deviceHeaders() + (authHeaders(session) - AUTHORIZATION)
            )
        } catch (error: ServerException) {
            if (error.code == "installation_changed") installations.remove(cacheKey(session))
            throw error.profileLocked()
        }
        @Suppress("UNCHECKED_CAST")
        if (reply == null) return Unit as T
        return decodeOrNull(reply, response.body) ?: throw ServerException(ServerFailure.FAILED)
    }

    private fun deviceHeaders(): Map<String, String> = mapOf(
        "X-Device-ID" to identity.deviceId(),
        "X-Silo-Device-Id" to identity.deviceId(),
        "X-Client-Name" to "Nuvio",
        "X-Client-Version" to identity.version.take(64)
    )

    override suspend fun signOut(session: ServerSession) {
        val credential = credentialOrNull(session.token) ?: return
        logout(session.address, credential.access)
    }

    override fun authHeaders(session: ServerSession): Map<String, String> {
        val credential = credentialOrNull(session.token) ?: return emptyMap()
        return buildMap {
            put(AUTHORIZATION, "Bearer ${credential.access}")
            put(PROFILE_ID, credential.profileId)
            credential.profileToken?.let { put(PROFILE_TOKEN, it) }
        }
    }

    override fun renewalDue(session: ServerSession): Boolean {
        val credential = credentialOrNull(session.token) ?: return false
        return credential.accessExpiresAtMs - now() < RENEW_BEFORE_MS
    }

    override suspend fun renewCredential(session: ServerSession, failure: ServerException?): String? {
        val renewable = failure == null ||
            failure.failure == ServerFailure.AUTH_REQUIRED && failure.code != "authentication_required" && failure.code != PROFILE_VERIFICATION
        if (!renewable) return null
        val credential = credentialOrNull(session.token) ?: return null
        val startedAt = now()
        val reply = try {
            send(
                "POST",
                session.address,
                REFRESH_PATH,
                TokenPairWire.serializer(),
                RefreshBody.serializer(),
                RefreshBody(credential.refresh)
            )
        } catch (error: ServerException) {
            val signedOut = error.httpStatus == 400 || error.httpStatus == 401 || error.httpStatus == 403 || error.httpStatus == 422
            if (signedOut) throw ServerException(ServerFailure.AUTH_REQUIRED, code = error.code, httpStatus = error.httpStatus)
            throw error
        }
        if (reply.accessToken.isBlank()) throw ServerException(ServerFailure.FAILED)
        return encode(
            credential.copy(
                access = reply.accessToken,
                refresh = reply.refreshToken.ifBlank { credential.refresh },
                accessExpiresAtMs = startedAt + reply.expiresIn * 1_000L
            )
        )
    }

    internal fun credentialOrNull(token: String): SiloCredential? = try {
        json.decodeFromString(SiloCredential.serializer(), token)
    } catch (_: IllegalArgumentException) {
        null
    }

    fun profileName(session: ServerSession): String? = credentialOrNull(session.token)?.profileName?.takeIf { it.isNotBlank() }

    fun profileId(session: ServerSession): String? = credentialOrNull(session.token)?.profileId

    private fun encode(credential: SiloCredential): String = json.encodeToString(SiloCredential.serializer(), credential)

    private suspend fun logout(address: String, access: String) {
        withContext(NonCancellable) {
            try {
                send<Unit, Unit>("POST", address, LOGOUT_PATH, null, null, null, bearer(access))
            } catch (_: Exception) {
            }
        }
    }

    /** The address once it answers as Silo's own API, following a move from http to https on the same host. */
    private suspend fun siloAddress(typed: String): String {
        var site = normalizeServerAddress(typed) ?: throw ServerException(ServerFailure.NOT_FOUND)
        repeat(2) {
            val reply = client.execute(
                "GET",
                site,
                SYSTEM_INFO_PATH,
                token = null,
                allowRedirect = true,
                authorization = null,
                headers = baseHeaders()
            )
            if (reply.status !in 300..399) {
                val info = decodeOrNull(SystemInfoWire.serializer(), reply.body)
                if (info?.apiMajor == API_MAJOR) return site
                throw notSilo()
            }
            site = reply.location
                ?.substringBefore(SYSTEM_INFO_PATH)
                ?.let { normalizeServerAddress(it) }
                ?.takeIf { it != site && it.toHttpUrlOrNull()?.host == site.toHttpUrlOrNull()?.host && !dropsEncryption(site, it) }
                ?: throw notSilo()
        }
        throw notSilo()
    }

    private fun notSilo() = ServerException(ServerFailure.FAILED, issue = ServerSignInIssue.SILO_NOT_NATIVE)

    private suspend fun <T> optional(block: suspend () -> T): T? = try {
        block()
    } catch (error: ServerException) {
        if (error.failure == ServerFailure.CERTIFICATE) throw error
        null
    }

    private suspend fun <T> get(
        address: String,
        path: String,
        reply: DeserializationStrategy<T>,
        headers: Map<String, String> = emptyMap(),
        query: Map<String, String?> = emptyMap()
    ): T = send<Unit, T>("GET", address, path, reply, null, null, headers, query)

    private suspend fun <B, T> send(
        method: String,
        address: String,
        path: String,
        reply: DeserializationStrategy<T>?,
        bodySerializer: SerializationStrategy<B>?,
        body: B?,
        headers: Map<String, String> = emptyMap(),
        query: Map<String, String?> = emptyMap()
    ): T {
        val response = try {
            client.execute(
                method,
                address,
                path,
                token = null,
                query = query,
                body = if (bodySerializer != null && body != null) json.encodeToString(bodySerializer, body) else null,
                authorization = headers[AUTHORIZATION],
                headers = baseHeaders() + (headers - AUTHORIZATION)
            )
        } catch (error: ServerException) {
            throw error.profileLocked()
        }
        @Suppress("UNCHECKED_CAST")
        if (reply == null) return Unit as T
        return decodeOrNull(reply, response.body) ?: throw ServerException(ServerFailure.FAILED)
    }

    private fun <T> decodeOrNull(reply: DeserializationStrategy<T>, body: String): T? = try {
        json.decodeFromString(reply, body)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun baseHeaders(): Map<String, String> = mapOf("User-Agent" to deviceName)

    private fun bearer(access: String): Map<String, String> = mapOf(AUTHORIZATION to "Bearer $access")

    private fun TokenPairWire.toTokens(): SiloTokens {
        if (accessToken.isBlank() || refreshToken.isBlank()) throw ServerException(ServerFailure.FAILED)
        val account = user ?: throw ServerException(ServerFailure.FAILED)
        return SiloTokens(
            access = accessToken,
            refresh = refreshToken,
            accessExpiresAtMs = now() + expiresIn * 1_000L,
            userId = account.id,
            userName = account.username,
            passwordChangeNeeded = account.passwordChangeRequired
        )
    }

    /** A PIN profile Silo wants checked again (the account's access rules changed): the sign-in needs renewing. */
    private fun ServerException.profileLocked(): ServerException =
        if (code != PROFILE_VERIFICATION) this
        else ServerException(ServerFailure.AUTH_REQUIRED, message, network, ServerSignInIssue.PROFILE_PIN, detail, httpStatus, address, code)

    private fun ServerException.asSignInFailure(): ServerException {
        val issue = when (code) {
            "local_login_disabled" -> ServerSignInIssue.PASSWORD_SIGN_IN_OFF
            "rate_limited" -> ServerSignInIssue.TOO_MANY_TRIES
            "password_change_required" -> ServerSignInIssue.TEMPORARY_PASSWORD
            "not_permitted", "account_required", "password_expired", "email_in_use", "identity_linked_elsewhere",
            "network_identity_required" -> ServerSignInIssue.SERVER_MESSAGE
            else -> return this
        }
        return ServerException(failure, message, network, issue, detail, httpStatus, address, code)
    }

    internal companion object {
        const val ID = "silo-native"
        const val API_MAJOR = 2
        const val SYSTEM_INFO_PATH = "/api/v2/system/info"
        const val IDENTITY_PATH = "/api/v2/system/identity"
        const val JELLYFIN_INFO_PATH = "/System/Info/Public"
        const val PROVIDERS_PATH = "/api/v2/auth/providers"
        const val BRANDING_PATH = "/api/v2/theme/branding"
        const val DEVICE_CAPABILITY_PATH = "/api/v2/auth/device/capability"
        const val DEVICE_START_PATH = "/api/v2/auth/device/start"
        const val DEVICE_POLL_PATH = "/api/v2/auth/device/poll"
        const val DEVICE_CANCEL_PATH = "/api/v2/auth/device/cancel"
        const val LOGIN_PATH = "/api/v2/auth/login"
        const val REFRESH_PATH = "/api/v2/auth/refresh"
        const val LOGOUT_PATH = "/api/v2/auth/logout"
        const val PROFILES_PATH = "/api/v2/profiles"
        const val USER_LIBRARIES_PATH = "/api/v2/user/libraries"
        const val CATALOG_PATH = "/api/v2/catalog"
        const val CATALOG_FILTERS_PATH = "/api/v2/catalog/filters"
        const val CATALOG_ITEMS_PATH = "/api/v2/catalog/items"
        const val SERIES_PATH = "/api/v2/catalog/series"
        const val WATCHED_PATH = "/api/v2/watched"
        const val HOME_LAYOUT_PATH = "/api/v2/home/layout"
        const val HOME_SECTIONS_PATH = "/api/v2/home/sections"
        const val HOME_CAPABILITIES_PATH = "/api/v2/home/capabilities"
        const val HOME_ROW_KIND_CARDS = 20
        const val WATCH_PATH = "/api/v2/watch"
        const val PLAYBACK_PATH = "/api/v2/playback"
        const val PLAYBACK_START_PATH = "/api/v2/playback/start"
        const val PLAYBACK_CAPABILITIES_PATH = "/api/v2/playback/capabilities"
        const val WATCH_PROVIDERS_PATH = "/api/v2/watch-providers"
        const val PLAYBACK_PROTOCOL = 3
        const val CAPABILITIES_TTL_MS = 10 * 60_000L
        const val RECENT_PLAY_MS = 10 * 60_000L
        const val STOP_TRIES = 3
        const val MAX_REPLANS = 3
        const val TRICKPLAY_TTL_MS = 60 * 60_000L
        const val TRICKPLAY_RENEW_MS = 5 * 60_000L
        const val TRICKPLAY_MANIFESTS = 16
        const val STOP_RETRY_MS = 250L
        const val CATALOG_MAX = 200
        val SCROBBLE_SERVICES = listOf("trakt", "simkl", "mdblist")
        const val SEARCH_MAX = 100
        const val TITLE_SEARCH_LIMIT = 10
        const val SEASONS_AT_ONCE = 4
        const val MAX_TRAVERSALS = 32
        const val TRAVERSAL_TTL_MS = 2 * 60 * 60_000L
        const val AUTHORIZATION = "Authorization"
        const val PROFILE_ID = "X-Profile-Id"
        const val PROFILE_TOKEN = "X-Profile-Token"
        const val PROFILE_VERIFICATION = "profile_verification_required"
        val STREAM_LIMIT_REASONS = setOf("capacity_unavailable", "stream_limit_reached", "transcode_limit_reached")
        const val VALIDATION_FAILED = "validation_failed"
        const val SESSION_ENDED_STATUS = 410
        const val DEVICE_NAME_MAX = 120
        const val UNWATCHED_GROUPS = "[{\"match\":\"all\",\"rules\":[{\"field\":\"watched\",\"op\":\"is\",\"value\":false}]}]"
        const val RENEW_BEFORE_MS = 60_000L

        /** A mixed Silo library (movies and shows together) shows as two libraries, one per kind. */
        const val PART = ":"

        fun isMixed(type: String): Boolean = type.trim().lowercase() in setOf("mixed", "video")

        fun englishKindName(kind: ServerMediaKind): String = if (kind == ServerMediaKind.SERIES) "Series" else "Movies"

        fun libraryKind(type: String): ServerMediaKind? = when (type.trim().lowercase()) {
            "movies", "movie" -> ServerMediaKind.MOVIE
            "series", "shows", "tv", "tvshows" -> ServerMediaKind.SERIES
            else -> null
        }

        fun parseInstant(value: String?): Long? = value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    }
}
