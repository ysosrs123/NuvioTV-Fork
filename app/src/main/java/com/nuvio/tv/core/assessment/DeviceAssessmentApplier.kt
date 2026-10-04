package com.nuvio.tv.core.assessment

import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.data.local.Dv7HandlingMode
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.VodCacheSizeMode
import com.nuvio.tv.ui.screens.settings.MemoryBudget
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Apply path for the device assessment.
 *
 *  - Writes go through the existing public setters, so each keeps its own
 *    coercions and side effects.
 *  - Order matters: setNuvioPerformanceModeEnabled(true) rewrites the whole
 *    buffer/parallel set, so it goes first. Min buffer is written before max
 *    because setBufferMaxBufferMs clamps to the stored min.
 *  - Before the first write the touchable settings are saved per profile as a
 *    JSON snapshot; revert restores them and clears the snapshot, so it always
 *    undoes the most recent apply.
 *  - Only plan fields (null = untouched) and the selected profile are written.
 */
object DeviceAssessmentApplier {
    // Serialize assessment Apply/Revert sequences; ordinary setting edits retain their existing behavior.
    private val mutation = Mutex()

    data class ApplyOutcome(val writtenCount: Int)
    internal class StaleAssessment : IllegalStateException()

    @androidx.annotation.OptIn(UnstableApi::class)
    internal suspend fun applyIfCurrent(
        dataStore: PlayerSettingsDataStore, plan: AssessmentApplyPlan,
        profile: IntentProfileOption?, safeLimitMb: Int, profileId: Int,
        isCurrent: () -> Boolean
    ): ApplyOutcome = mutation.withLock {
        // Recheck after waiting for another mutation, before snapshot creation or any setting write.
        if (!isCurrent()) throw StaleAssessment()
        applyOwned(dataStore, plan, profile, safeLimitMb, profileId) {
            if (!isCurrent()) throw StaleAssessment()
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    internal suspend fun applyValidated(
        dataStore: PlayerSettingsDataStore, plan: AssessmentApplyPlan,
        profile: IntentProfileOption?, safeLimitMb: Int, profileId: Int,
        inputs: AssessmentInputs, isCurrent: () -> Boolean
    ): ApplyOutcome = mutation.withLock {
        if (!isCurrent()) throw StaleAssessment()
        applyOwned(dataStore, plan, profile, safeLimitMb, profileId) { before ->
            val (savedSettings, source) = dataStore.assessmentInputValuesForProfile(profileId).first()
            if (profileId != dataStore.activeProfileId || !inputs.matches(before, source) ||
                !inputs.matches(savedSettings, source) || !isCurrent())
                throw StaleAssessment()
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    suspend fun apply(
        dataStore: PlayerSettingsDataStore,
        plan: AssessmentApplyPlan,
        profile: IntentProfileOption?,
        safeLimitMb: Int,
        profileId: Int = dataStore.activeProfileId
    ): ApplyOutcome = mutation.withLock {
        applyOwned(dataStore, plan, profile, safeLimitMb, profileId)
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private suspend fun applyOwned(
        dataStore: PlayerSettingsDataStore, plan: AssessmentApplyPlan,
        profile: IntentProfileOption?, safeLimitMb: Int, profileId: Int,
        validateBeforeWrite: suspend (PlayerSettings) -> Unit = {}
    ): ApplyOutcome {
        val before = dataStore.playerSettingsForProfile(profileId).first()
        validateBeforeWrite(before)
        dataStore.setAssessmentRevertSnapshot(snapshotJson(before), profileId = profileId)

        var written = 0
        suspend fun step(block: suspend () -> Unit) {
            block()
            written += 1
        }

        // 1. Performance mode first: enabling it rewrites the buffer set.
        plan.nuvioPerformanceModeEnabled?.let { step { dataStore.setNuvioPerformanceModeEnabled(it, profileId = profileId) } }
        // 2. Masters and budget mode.
        plan.bufferEngineEnabled?.let { step { dataStore.setBufferEngineEnabled(it, profileId = profileId) } }
        plan.parallelNetworkEnabled?.let { step { dataStore.setParallelNetworkEnabled(it, profileId = profileId) } }
        plan.bufferBudgetManaged?.let { step { dataStore.setBufferBudgetManaged(it, profileId = profileId) } }
        // 3. Slider range before the value that needs it.
        plan.allowLargeTargetBuffer?.let { step { dataStore.setAllowLargeTargetBuffer(it, profileId = profileId) } }
        plan.targetBufferSizeMb?.let { step { dataStore.setBufferTargetSizeMb(it, profileId = profileId) } }
        // 4. Profile trio (min BEFORE max), then the max cap.
        profile?.let {
            step { dataStore.setBufferMinBufferMs(it.minBufferMs, profileId = profileId) }
            step { dataStore.setBufferForPlaybackMs(it.initialBufferMs, profileId = profileId) }
            step { dataStore.setBufferForPlaybackAfterRebufferMs(it.rebufferMs, profileId = profileId) }
        }
        plan.maxBufferMs?.let { newMax ->
            if (profile == null && before.bufferSettings.minBufferMs > newMax) {
                // Max setter clamps to >= stored min; honour the intent by
                // lowering the min with it.
                step { dataStore.setBufferMinBufferMs(newMax, profileId = profileId) }
            }
            step { dataStore.setBufferMaxBufferMs(newMax, profileId = profileId) }
        }
        // 5. Parallel pipe.
        plan.useParallelConnections?.let { step { dataStore.setUseParallelConnections(it, profileId = profileId) } }
        plan.parallelConnectionCount?.let { step { dataStore.setParallelConnectionCount(it, profileId = profileId) } }
        plan.parallelChunkSizeKb?.let { step { dataStore.setParallelChunkSizeKb(it, profileId = profileId) } }
        plan.enableHttp2?.let { step { dataStore.setEnableHttp2(it, profileId = profileId) } }
        // 6. VOD, display, DV, audio route.
        plan.vodCacheEnabled?.let { step { dataStore.setVodCacheEnabled(it, profileId = profileId) } }
        plan.vodCacheSizeMode?.let { step { dataStore.setVodCacheSizeMode(it, profileId = profileId) } }
        plan.frameRateMatchingMode?.let { step { dataStore.setFrameRateMatchingMode(it, profileId = profileId) } }
        plan.resolutionMatchingEnabled?.let { step { dataStore.setResolutionMatchingEnabled(it, profileId = profileId) } }
        plan.dv7HandlingMode?.let { step { dataStore.setDv7HandlingMode(it, profileId = profileId) } }
        plan.dv5ToDv81Enabled?.let { step { dataStore.setDv5ToDv81Enabled(it, profileId = profileId) } }
        plan.stripHdr10PlusSei?.let { step { dataStore.setStripHdr10PlusSei(it, profileId = profileId) } }
        plan.forceOpticalPassthrough?.let { step { dataStore.setForceOpticalPassthrough(it, profileId = profileId) } }

        // Pre-commit safe-limit re-check ("recommend to safe"). Read
        // the settled state back and confirm the native footprint - target
        // buffer plus parallel overhead - fits the safe budget; clamp the
        // target down to the largest fitting step if not. Reading BACK rather
        // than trusting the plan is deliberate: enabling performance mode
        // presets target = safeLimitMb and 4c/16 parallel, and the plan only
        // rewrites a field when its recommendation differs from the PRE-apply
        // value, so a recommendation equal to the pre-preset value can leave
        // the preset's over-budget target in place. The MIN_BUFFER_MB floor is
        // preserved: on a device where overhead alone already exceeds safe (the
        // engine's deliberate constrained-device fallback) the clamp settles on
        // MIN_BUFFER_MB and does not fight it. No-op on the normal path, where
        // the engine already sized the target to safe minus overhead.
        val after = dataStore.playerSettingsForProfile(profileId).first()
        val afterOverheadMb = if (after.useParallelConnections) {
            MemoryBudget.parallelOverheadMb(
                after.parallelConnectionCount,
                (after.parallelChunkSizeKb + 1023) / 1024
            )
        } else {
            0
        }
        val maxSafeTargetMb =
            (((safeLimitMb - afterOverheadMb) / MemoryBudget.BUFFER_STEP_MB) * MemoryBudget.BUFFER_STEP_MB)
                .coerceAtLeast(MemoryBudget.MIN_BUFFER_MB)
        if (after.bufferSettings.targetBufferSizeMb > maxSafeTargetMb) {
            step { dataStore.setBufferTargetSizeMb(maxSafeTargetMb, profileId = profileId) }
        }

        return ApplyOutcome(writtenCount = written)
    }

    /** Restores every captured field; returns false when no snapshot exists. */
    suspend fun revert(dataStore: PlayerSettingsDataStore, profileId: Int = dataStore.activeProfileId): Boolean =
        mutation.withLock { revertOwned(dataStore, profileId) }

    private suspend fun revertOwned(dataStore: PlayerSettingsDataStore, profileId: Int): Boolean {
        val json = dataStore.assessmentRevertSnapshotForProfile(profileId).first() ?: return false
        val snap = runCatching { JSONObject(json) }.getOrNull() ?: run {
            dataStore.setAssessmentRevertSnapshot(null, profileId = profileId)
            return false
        }

        // The mode preset below also rewrites rewind duration. Older snapshots
        // did not capture it, so preserve the current value before that setter
        // runs; its original pre-assessment value cannot be recovered.
        val backBufferDurationMs = if (snap.has("backBufferDurationMs")) {
            snap.getInt("backBufferDurationMs")
        } else {
            dataStore.playerSettingsForProfile(profileId).first().bufferSettings.backBufferDurationMs
        }

        // Same locked order as apply. The snapshot is internally consistent
        // (min <= max), and min is written first, so the mutual clamps in the
        // buffer setters settle on the captured values.
        dataStore.setNuvioPerformanceModeEnabled(snap.getBoolean("nuvioPerformanceModeEnabled"), profileId = profileId)
        dataStore.setBufferEngineEnabled(snap.getBoolean("bufferEngineEnabled"), profileId = profileId)
        dataStore.setParallelNetworkEnabled(snap.getBoolean("parallelNetworkEnabled"), profileId = profileId)
        dataStore.setBufferBudgetManaged(snap.getBoolean("bufferBudgetManaged"), profileId = profileId)
        dataStore.setAllowLargeTargetBuffer(snap.getBoolean("allowLargeTargetBuffer"), profileId = profileId)
        dataStore.setBufferTargetSizeMb(snap.getInt("targetBufferSizeMb"), profileId = profileId)
        dataStore.setBufferMinBufferMs(snap.getInt("minBufferMs"), profileId = profileId)
        dataStore.setBufferForPlaybackMs(snap.getInt("bufferForPlaybackMs"), profileId = profileId)
        dataStore.setBufferForPlaybackAfterRebufferMs(snap.getInt("bufferForPlaybackAfterRebufferMs"), profileId = profileId)
        dataStore.setBufferMaxBufferMs(snap.getInt("maxBufferMs"), profileId = profileId)
        dataStore.setBufferBackBufferDurationMs(backBufferDurationMs, profileId = profileId)
        dataStore.setUseParallelConnections(snap.getBoolean("useParallelConnections"), profileId = profileId)
        dataStore.setParallelConnectionCount(snap.getInt("parallelConnectionCount"), profileId = profileId)
        dataStore.setParallelChunkSizeKb(snap.getInt("parallelChunkSizeKb"), profileId = profileId)
        dataStore.setEnableHttp2(snap.getBoolean("enableHttp2"), profileId = profileId)
        dataStore.setVodCacheEnabled(snap.getBoolean("vodCacheEnabled"), profileId = profileId)
        dataStore.setVodCacheSizeMode(VodCacheSizeMode.valueOf(snap.getString("vodCacheSizeMode")), profileId = profileId)
        dataStore.setFrameRateMatchingMode(FrameRateMatchingMode.valueOf(snap.getString("frameRateMatchingMode")), profileId = profileId)
        dataStore.setResolutionMatchingEnabled(snap.getBoolean("resolutionMatchingEnabled"), profileId = profileId)
        dataStore.setDv7HandlingMode(Dv7HandlingMode.valueOf(snap.getString("dv7HandlingMode")), profileId = profileId)
        dataStore.setDv5ToDv81Enabled(snap.getBoolean("dv5ToDv81Enabled"), profileId = profileId)
        dataStore.setStripHdr10PlusSei(snap.getBoolean("stripHdr10PlusSei"), profileId = profileId)
        dataStore.setForceOpticalPassthrough(snap.getBoolean("forceOpticalPassthrough"), profileId = profileId)
        // Audio format keys in older snapshots are ignored.

        dataStore.setAssessmentRevertSnapshot(null, profileId = profileId)
        return true
    }

    private fun snapshotJson(s: PlayerSettings): String = JSONObject().apply {
        put("timestampMs", System.currentTimeMillis())
        put("nuvioPerformanceModeEnabled", s.nuvioPerformanceModeEnabled)
        put("bufferEngineEnabled", s.bufferEngineEnabled)
        put("parallelNetworkEnabled", s.parallelNetworkEnabled)
        put("bufferBudgetManaged", s.bufferBudgetManaged)
        put("allowLargeTargetBuffer", s.allowLargeTargetBuffer)
        put("targetBufferSizeMb", s.bufferSettings.targetBufferSizeMb)
        put("minBufferMs", s.bufferSettings.minBufferMs)
        put("maxBufferMs", s.bufferSettings.maxBufferMs)
        put("backBufferDurationMs", s.bufferSettings.backBufferDurationMs)
        put("bufferForPlaybackMs", s.bufferSettings.bufferForPlaybackMs)
        put("bufferForPlaybackAfterRebufferMs", s.bufferSettings.bufferForPlaybackAfterRebufferMs)
        put("useParallelConnections", s.useParallelConnections)
        put("parallelConnectionCount", s.parallelConnectionCount)
        put("parallelChunkSizeKb", s.parallelChunkSizeKb)
        put("enableHttp2", s.enableHttp2)
        put("vodCacheEnabled", s.vodCacheEnabled)
        put("vodCacheSizeMode", s.vodCacheSizeMode.name)
        put("frameRateMatchingMode", s.frameRateMatchingMode.name)
        put("resolutionMatchingEnabled", s.resolutionMatchingEnabled)
        put("dv7HandlingMode", s.dv7HandlingMode.name)
        put("dv5ToDv81Enabled", s.dv5ToDv81Enabled)
        put("stripHdr10PlusSei", s.stripHdr10PlusSei)
        put("forceOpticalPassthrough", s.forceOpticalPassthrough)
    }.toString()
}
