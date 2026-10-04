package com.nuvio.tv.core.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A token proves only that no relevant default-route change was observed in this lifetime. */
internal class DiagnosticNetworkTracker(private val requireRouteFacts: Boolean = true) {
    data class Token internal constructor(val generation: Long, val network: Any, val routeFactsKnown: Boolean)
    private var observing = false
    private var network: Any? = null
    private var capabilities: String? = null
    private var links: String? = null
    private var blocked = false
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    private fun changed() { revision.value += 1 }

    @Synchronized fun start() { observing = true; network = null; capabilities = null; links = null; changed() }
    @Synchronized fun available(value: Any) {
        if (!observing || value == network) return
        network = value; capabilities = null; links = null; blocked = false; changed()
    }
    @Synchronized fun capabilities(value: Any, signature: String) {
        if (!observing || value != network || signature == capabilities) return
        capabilities = signature; changed()
    }
    @Synchronized fun links(value: Any, signature: String) {
        if (!observing || value != network || signature == links) return
        links = signature; changed()
    }
    @Synchronized fun blocked(value: Any, isBlocked: Boolean) {
        if (!observing || value != network || blocked == isBlocked) return
        blocked = isBlocked; changed()
    }
    @Synchronized fun lost(value: Any) {
        if (!observing || value != network) return
        network = null; capabilities = null; links = null; changed()
    }
    @Synchronized fun token(currentNetwork: Any?): Token? =
        if (observing && !blocked && currentNetwork != null && currentNetwork == network &&
            (!requireRouteFacts || (capabilities != null && links != null)))
            Token(revision.value, currentNetwork, capabilities != null && links != null) else null
    @Synchronized fun close() {
        if (!observing) return
        observing = false; network = null; capabilities = null; links = null; changed()
    }
}
