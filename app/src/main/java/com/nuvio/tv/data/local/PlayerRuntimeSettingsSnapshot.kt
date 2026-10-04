package com.nuvio.tv.data.local

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Store emission with its owning profile, including the unchanged profile's first emission. */
internal data class PlayerRuntimeSettingsSnapshot(val profileId: Int, val settings: PlayerSettings)

/**
 * Layout edits must not rerun subtitle selection, AFR-off cleanup or other playback work.
 * Keep every other decoded setting in equality, and reapply after a profile switch even
 * when its values match. Each collection starts with an unfiltered initial emission.
 */
internal fun Flow<PlayerRuntimeSettingsSnapshot>.playbackSettingsChanges(): Flow<PlayerRuntimeSettingsSnapshot> =
    distinctUntilChanged { previous, current ->
        previous.profileId == current.profileId &&
            previous.settings.copy(controlLayout = null) == current.settings.copy(controlLayout = null)
    }
