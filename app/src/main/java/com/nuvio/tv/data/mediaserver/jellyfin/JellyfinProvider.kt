package com.nuvio.tv.data.mediaserver.jellyfin

import com.nuvio.tv.data.mediaserver.ServerTitleQuery
import com.nuvio.tv.data.mediaserver.mediabrowser.CodecProfile
import com.nuvio.tv.data.mediaserver.mediabrowser.Endpoint
import com.nuvio.tv.data.mediaserver.mediabrowser.MediaBrowserProvider
import com.nuvio.tv.data.mediaserver.mediabrowser.ProfileCondition
import com.nuvio.tv.data.mediaserver.mediabrowser.ServerClientIdentity
import com.nuvio.tv.data.mediaserver.mediabrowser.TranscodingProfile
import com.nuvio.tv.data.mediaserver.mediabrowser.pathSegment
import okhttp3.OkHttpClient

internal open class JellyfinProvider(
    http: OkHttpClient,
    identity: ServerClientIdentity
) : MediaBrowserProvider(authorizationHeader = "Authorization", apiPath = "", http = http, identity = identity) {
    override val id: String = "jellyfin"
    override val displayName: String = "Jellyfin"
    override val minimumVersion: String = "10.9"
    override val tokenQueryName: String = "ApiKey"
    override val supportsQuickConnect: Boolean
        get() = true

    override fun viewsEndpoint(userId: String) = Endpoint("/UserViews", mapOf("userId" to userId))

    override fun itemsEndpoint(userId: String) = Endpoint("/Items", mapOf("userId" to userId))

    override fun itemEndpoint(userId: String, itemId: String) =
        Endpoint("/Items/${pathSegment(itemId)}", mapOf("userId" to userId))

    override fun resumeEndpoint(userId: String) = Endpoint("/UserItems/Resume", mapOf("userId" to userId))

    override fun playedEndpoint(userId: String, itemId: String) =
        Endpoint("/UserPlayedItems/${pathSegment(itemId)}", mapOf("userId" to userId))

    override fun titleLookupQuery(query: ServerTitleQuery): Map<String, String>? =
        query.name?.trim()?.takeIf { it.isNotEmpty() }?.let { mapOf("searchTerm" to it) }

    override fun titleRetryQuery(query: ServerTitleQuery): Map<String, String>? =
        query.originalName?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals(query.name?.trim(), ignoreCase = true) }
            ?.let { mapOf("searchTerm" to it) }

    override fun transcodingProfiles(): List<TranscodingProfile> = listOf(
        TranscodingProfile(
            container = "mp4",
            videoCodec = "hevc,h264",
            audioCodec = "ac3,eac3,aac,flac,opus,dts,truehd",
            protocol = "hls"
        )
    ) + super.transcodingProfiles()

    override fun codecProfiles(): List<CodecProfile> = listOf("hevc", "av1").map { codec ->
        CodecProfile(
            codec = codec,
            conditions = listOf(
                ProfileCondition(condition = "EqualsAny", property = "VideoRangeType", value = VIDEO_RANGE_TYPES)
            )
        )
    }

    private companion object {
        const val VIDEO_RANGE_TYPES = "SDR|HDR10|HDR10Plus|HLG|DOVI|DOVIWithHDR10|DOVIWithHLG|DOVIWithSDR|" +
            "DOVIWithEL|DOVIWithHDR10Plus|DOVIWithELHDR10Plus|DOVIInvalid|Unknown"
    }
}
