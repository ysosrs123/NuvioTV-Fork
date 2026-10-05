package com.nuvio.tv.core.iptv

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Counts requests entering open(), including blocked connects; rejects late Media3 loader work. */
class LiveRequestFence {
    class Ticket internal constructor()
    private var accepting = true
    private val tickets = mutableSetOf<Ticket>()
    private val count = MutableStateFlow(0)
    val active = count.asStateFlow()
    @Synchronized fun enter(): Ticket? {
        if (!accepting) return null
        return Ticket().also { tickets += it; count.value = tickets.size }
    }
    @Synchronized fun leave(ticket: Ticket) { tickets.remove(ticket); count.value = tickets.size }
    @Synchronized fun stopAccepting() { accepting = false }
}
