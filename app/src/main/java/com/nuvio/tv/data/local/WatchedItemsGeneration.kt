package com.nuvio.tv.data.local

/** Captured before suspended work; invalid after clear, deletion, recreation or reset. */
class WatchedItemsGeneration internal constructor(
    internal val profileId: Int,
    internal val owner: WatchedItemsOwner,
    internal val epoch: Long
)
