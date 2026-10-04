package com.nuvio.tv.data.mediaserver.silo

import com.nuvio.tv.data.mediaserver.jellyfin.JellyfinProvider
import com.nuvio.tv.data.mediaserver.mediabrowser.ServerClientIdentity
import okhttp3.OkHttpClient

internal class SiloProvider(
    http: OkHttpClient,
    identity: ServerClientIdentity
) : JellyfinProvider(http, identity) {
    override val id: String = ID
    override val displayName: String = "Silo"
    override val supportsQuickConnect: Boolean
        get() = false

    override fun segmentItemId(itemId: String, mediaSourceId: String?): String = mediaSourceId ?: itemId

    companion object {
        const val ID = "silo"
    }
}
