package com.nuvio.tv.core.iptv

enum class RefreshOutcome { RUNNING, DONE, FAILED }

data class RefreshBatchProgress(val total: Int, val finished: Int, val failed: Int) {
    val running: Boolean get() = finished < total
    val updated: Int get() = finished - failed
}

fun refreshBatchProgress(keys: Collection<String>, outcome: (String) -> RefreshOutcome?): RefreshBatchProgress {
    var finished = 0; var failed = 0
    for (key in keys.distinct()) when (outcome(key)) {
        RefreshOutcome.RUNNING -> Unit
        RefreshOutcome.FAILED -> { finished++; failed++ }
        RefreshOutcome.DONE, null -> finished++
    }
    return RefreshBatchProgress(keys.distinct().size, finished, failed)
}

fun refreshAccountKey(profileId: Int, accountId: String?, sourceId: String): String =
    "$profileId:" + (accountId?.takeIf(String::isNotBlank) ?: "source-$sourceId")
