package com.nuvio.tv.domain.model

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.core.tracking.LOCAL_LIBRARY_LIST_KEY
import com.nuvio.tv.core.tracking.TrackingProviderId

@Composable
fun LibraryListTab.localizedMembershipTitle(): String {
    val provider = when (TrackingProviderId.fromStorage(trackingProviderId)) {
        TrackingProviderId.TRAKT -> stringResource(R.string.trakt_name)
        TrackingProviderId.SIMKL -> stringResource(R.string.simkl_name)
        TrackingProviderId.MDBLIST -> stringResource(R.string.mdblist_name)
        TrackingProviderId.FLOPPY -> stringResource(R.string.floppy_name)
        TrackingProviderId.WETRAKR -> stringResource(R.string.wetrakr_name)
        null -> null
    }
    return provider?.let { "$it · ${localizedTitle()}" } ?: localizedTitle()
}

@Composable
fun LibraryListTab.localizedTitle(): String = titleRes()?.let { stringResource(it) } ?: title

/** The translated name of a built-in list, or null when the list's own [LibraryListTab.title] is its name. */
@StringRes
fun LibraryListTab.titleRes(): Int? = when {
    key == LOCAL_LIBRARY_LIST_KEY -> null
    key == "simkl:status:watching" -> R.string.library_status_watching
    key == "simkl:status:plantowatch" -> R.string.library_status_plan_to_watch
    key == "simkl:status:hold" -> R.string.library_status_on_hold
    key == "simkl:status:completed" -> R.string.library_status_completed
    key == "simkl:status:dropped" -> R.string.library_status_dropped
    key == "wetrakr:status:planning" -> R.string.library_status_plan_to_watch
    key == "wetrakr:status:watching" -> R.string.library_status_watching
    key == "wetrakr:status:watched" -> R.string.library_filter_watched
    key == "wetrakr:status:hold" -> R.string.library_status_on_hold
    key == "wetrakr:status:dropped" -> R.string.library_status_dropped
    type == LibraryListTab.Type.WATCHLIST -> R.string.library_watchlist
    else -> null
}
