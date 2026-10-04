package com.nuvio.tv.core.player

import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.DebridStreamPreferences
import com.nuvio.tv.domain.model.Stream

/**
 * The single call site for auto-play selection, so the stream screen, the
 * details-page pre-resolve and the prefetch rank cannot pick differently.
 * A divergent pre-resolve would cost a wasted debrid link on every play.
 *
 * No selection logic lives here: it assembles arguments and delegates to
 * StreamAutoPlaySelector and StreamQualityRank. preferBingeGroupInSelection is
 * derived from [Inputs]; only [bingeGroupOnly] is passed.
 *
 * [debridStreamPreferences] is not part of [Inputs]: callers read it at call
 * time, and a snapshot would change when it is read.
 *
 * Upstream: NuvioMedia/NuvioTV. Licensed under GPL-3.0.
 */
object AutoPlaySelection {

    /**
     * Settings-derived selector inputs, snapshotted once per loadStreams().
     * Every field is already a snapshot in the caller: playerSettings comes
     * from a single .first(), installedAddonOrder from a single
     * getInstalledAddons().first(), and preferredBingeGroup from a single
     * BingeGroupCacheDataStore.get().
     */
    data class Inputs(
        val mode: StreamAutoPlayMode,
        val regexPattern: String,
        val source: StreamAutoPlaySource,
        val installedAddonNames: Set<String>,
        val selectedAddons: Set<String>,
        val selectedPlugins: Set<String>,
        val preferredBingeGroup: String?
    )

    /**
     * Selects the stream to auto-play, or null for the picker.
     *
     * @param bingeGroupOnly when true, a binge-group miss returns null instead
     *   of falling back to the configured mode -- the eager pre-timeout check.
     */
    fun select(
        streams: List<Stream>,
        inputs: Inputs,
        debridStreamPreferences: DebridStreamPreferences?,
        bingeGroupOnly: Boolean = false
    ): Stream? = StreamAutoPlaySelector.selectAutoPlayStream(
        streams = streams,
        mode = inputs.mode,
        regexPattern = inputs.regexPattern,
        source = inputs.source,
        installedAddonNames = inputs.installedAddonNames,
        selectedAddons = inputs.selectedAddons,
        selectedPlugins = inputs.selectedPlugins,
        preferredBingeGroup = inputs.preferredBingeGroup,
        preferBingeGroupInSelection = inputs.preferredBingeGroup != null,
        bingeGroupOnly = bingeGroupOnly,
        debridStreamPreferences = debridStreamPreferences
    )
}
