package com.nuvio.tv.core.iptv

data class ConnectionChange(val accountId: String, val limit: Int, val reassign: Boolean)

object SourceConnections {
    const val MAX = 4

    fun automaticLimit(provider: Int?): Int? = provider?.takeIf { it > 0 }?.coerceAtMost(MAX)

    fun ownAccount(sourceId: String) = "src-" + sourceId.take(76)

    fun change(sourceId: String, accountId: String, accountSources: Int, current: Int, limit: Int): ConnectionChange? {
        require(limit in 1..MAX)
        val target = if (accountSources > 1 || accountId == DEFAULT_ACCOUNT_ID) ownAccount(sourceId) else accountId
        if (target == accountId && current == limit) return null
        return ConnectionChange(target, limit, target != accountId)
    }
}
