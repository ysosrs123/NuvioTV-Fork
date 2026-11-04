package com.nuvio.tv.core.party

import com.nuvio.tv.core.party.nuvio.ActiveParty
import com.nuvio.tv.core.party.nuvio.PartyError
import com.nuvio.tv.core.party.nuvio.PartyPlayback
import com.nuvio.tv.core.party.nuvio.PartyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.nuvio.tv.core.party.nuvio.PartyMedia as NuvioMedia
import com.nuvio.tv.core.party.nuvio.PartyMemberStatus as NuvioStatus

/** Nuvio's own party service, as [NuvioPartySession] uses it. [scope] runs on the main thread. */
internal class NuvioRepositoryLink(
    private val repository: PartyRepository,
    private val scope: CoroutineScope,
) : NuvioPartyLink {
    override val party: ActiveParty? get() = repository.state.value.party

    override val playback: PartyPlayback? get() = repository.playback.value

    override fun nowMs(): Long = repository.nowMs()

    override fun create(media: NuvioMedia, done: (PartyError?) -> Unit) {
        scope.launch { done(repository.create(media)) }
    }

    override fun join(code: String, done: (PartyError?) -> Unit) {
        scope.launch { done(repository.join(code)) }
    }

    override fun leave() = repository.leave()

    override fun end() = repository.end()

    override fun setHostOnly(enabled: Boolean) = repository.setHostOnly(enabled)

    override fun publishPlayback(paused: Boolean, positionMs: Long, rate: Float) =
        repository.publishPlayback(paused = paused, positionMs = positionMs, rate = rate)

    override fun publishMedia(media: NuvioMedia, positionMs: Long) = repository.publishMedia(media, positionMs)

    override fun setLocalStatus(status: NuvioStatus, mediaKey: String?, behindMs: Long?) =
        repository.setLocalStatus(status, mediaKey, behindMs)
}
