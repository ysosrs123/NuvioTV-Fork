package com.nuvio.tv.data.iptv

class IptvProfileAccess(private val catalogue: IptvCatalogueStore, private val guides: IptvGuideStore) {
    class Session internal constructor(val profileId: Int, internal val revision: Long, internal val global: Long)
    private val revisions = mutableMapOf<Int, Long>()
    private var global = 0L
    @Synchronized fun open(profileId: Int): Session {
        require(profileId >= 0)
        return Session(profileId, revisions[profileId] ?: 0, global)
    }
    @Synchronized fun <T> use(session: Session, block: () -> T): T {
        check(session.global == global && session.revision == (revisions[session.profileId] ?: 0L)) { "IPTV profile session expired" }
        return block()
    }
    @Synchronized fun removeProfile(profileId: Int) {
        revisions[profileId] = Math.addExact(revisions[profileId] ?: 0, 1)
        catalogue.removeProfile(profileId)
        guides.removeProfile(profileId)
    }
    @Synchronized fun clearAllProfiles() {
        global = Math.addExact(global, 1)
        catalogue.clearAllProfiles(); guides.clearAllProfiles()
    }
}
