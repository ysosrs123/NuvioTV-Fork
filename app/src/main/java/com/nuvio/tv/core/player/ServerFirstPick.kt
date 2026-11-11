package com.nuvio.tv.core.player

import com.nuvio.tv.domain.model.Stream

/**
 * Auto-play's pick from the servers set to go first, in the order of the servers list: the viewer's own rule among
 * that server's copies, else its first copy. Null when none of them has the title, so auto-play carries on as usual.
 */
fun serverFirstPick(streams: List<Stream>, firstNames: Set<String>, select: (List<Stream>) -> Stream?): Stream? {
    for (name in firstNames) {
        val own = streams.filter { it.addonName == name }
        if (own.isNotEmpty()) return select(own) ?: own.first()
    }
    return null
}
