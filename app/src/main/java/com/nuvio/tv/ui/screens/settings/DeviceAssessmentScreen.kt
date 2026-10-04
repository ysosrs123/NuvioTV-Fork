package com.nuvio.tv.ui.screens.settings

import android.app.Activity
import com.nuvio.tv.core.network.DiagnosticRunCoordinator
import com.nuvio.tv.core.network.displayMessage
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.assessment.AssessmentInputs
import com.nuvio.tv.core.assessment.AssessmentDeviceFacts
import com.nuvio.tv.core.assessment.AssessmentBitrateScope
import com.nuvio.tv.core.assessment.AssessmentItem
import com.nuvio.tv.core.network.StreamSweepEngine
import com.nuvio.tv.core.assessment.AssessmentResult
import com.nuvio.tv.core.assessment.AssessmentTier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.core.assessment.DeviceAssessmentApplier
import com.nuvio.tv.core.assessment.DeviceAssessmentEngine
import com.nuvio.tv.core.assessment.ProfileKind
import com.nuvio.tv.core.player.LastPlaybackDiagnostics
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Device Settings Assessment UI.
 *
 * State lives at the SCREEN level (rememberDeviceAssessmentState in
 * AdvancedSettingsContent) and the results render as MULTIPLE LazyColumn
 * items via deviceAssessmentItems. Both are deliberate: lazy items are
 * disposed when scrolled out of composition, so state held inside a single
 * tall item would die on scroll-away, and one tall item would also
 * cut cards off between focus stops. Per-card items scroll individually and
 * screen-level state survives recycling; the run coroutine launches on the
 * screen scope so a mid-run scroll can't cancel the sweep.
 */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
internal interface AssessmentDataStoreEntryPoint {
    fun playerSettingsDataStore(): com.nuvio.tv.data.local.PlayerSettingsDataStore
}

internal fun assessmentDataStore(context: Context): com.nuvio.tv.data.local.PlayerSettingsDataStore =
    dagger.hilt.android.EntryPointAccessors.fromApplication(
        context.applicationContext,
        AssessmentDataStoreEntryPoint::class.java
    ).playerSettingsDataStore()

// Assessment inner cards use a gently rounded rectangle instead of the
// settings pill: with multi-line content the pill's corner curvature
// clips first/last-line text at the card edges.
@Composable
private fun assessmentCardShape() = RoundedCornerShape(12.dp)

internal class DeviceAssessmentState {
    val run = DiagnosticUiRun()
    val running get() = run.running
    fun cancel() {
        run.cancel()
        passRows = emptyList(); passNotes = emptyMap(); sweepState = ""
        result = null; selectedProfile = null; runError = null; applyArmed = false; assessedProfileId = null
        networkIdentity = null; assessedNetwork = null; appliedCount = null
        assessedInputs = null; inputIdentity = null; deviceFactsCurrent = null; networkLabel = null
    }
    var sweepState by mutableStateOf("")
    var runError by mutableStateOf<String?>(null)
    var passRows by mutableStateOf(listOf<Pair<String, Double?>>())
    // Why a row has no number, keyed by row label.
    var passNotes by mutableStateOf(mapOf<String, StreamSweepEngine.PassNote>())
    var result by mutableStateOf<AssessmentResult?>(null)
    var selectedProfile by mutableStateOf<ProfileKind?>(null)
    var applyArmed by mutableStateOf(false)
    val mutation = DiagnosticUiRun()
    val applying get() = mutation.running
    var assessedProfileId: Int? = null
    var networkIdentity: (() -> Any?)? = null
    var assessedNetwork: Any? = null
    fun networkValid(): Boolean = networkIdentity?.let { assessedNetwork != null && assessedNetwork == it() } ?: true
    fun invalidateNetwork(context: Context) {
        if (networkValid()) return
        cancel(); runError = context.getString(R.string.network_test_network_changed)
    }
    var networkLabel: String? = null
    var assessedInputs: AssessmentInputs? = null
    var inputIdentity: (() -> AssessmentInputs)? = null
    var deviceFactsCurrent: ((AssessmentDeviceFacts) -> Boolean)? = null
    fun inputsValid(): Boolean = inputIdentity?.let { provider ->
        assessedInputs?.sameAs(provider()) == true
    } ?: true
    fun factsValid(result: AssessmentResult): Boolean = deviceFactsCurrent?.let { check ->
        result.deviceFacts?.let { runCatching { check(it) }.getOrDefault(false) } == true
    } ?: true
    fun invalidateInputs(context: Context) {
        // Own Apply writes change settings too; its captured final guard handles queued changes.
        if (applying || inputsValid()) return
        cancel(); runError = context.getString(R.string.assessment_inputs_changed)
    }
    var appliedCount by mutableStateOf<Int?>(null)
}

/** A copy for the test run only; the saved diagnostics never hold these headers. */
private fun LastPlaybackDiagnostics.withHeaders(extra: Map<String, String>): LastPlaybackDiagnostics {
    if (extra.isEmpty()) return this
    val merged = runCatching { org.json.JSONObject(headersJson?.takeIf { it.isNotBlank() } ?: "{}") }
        .getOrElse { org.json.JSONObject() }
    extra.forEach { (name, value) -> merged.put(name, value) }
    return copy(headersJson = merged.toString())
}

@Composable
internal fun rememberDeviceAssessmentState(): DeviceAssessmentState =
    remember { DeviceAssessmentState() }

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun runDeviceAssessment(
    scope: CoroutineScope,
    context: Context,
    state: DeviceAssessmentState,
    settings: PlayerSettings,
    diagnostics: LastPlaybackDiagnostics,
    profileId: Int? = null,
    networkIdentity: (() -> Any?)? = null,
    inputIdentity: (() -> AssessmentInputs)? = null,
    deviceFactsCurrent: ((AssessmentDeviceFacts) -> Boolean)? = null,
    networkLabel: String? = null,
    extraHeaders: Map<String, String> = emptyMap()
) {
    if (state.running || state.applying) return
    state.networkIdentity = networkIdentity
    state.assessedNetwork = networkIdentity?.invoke()
    if (!state.networkValid()) {
        state.cancel(); state.runError = context.getString(R.string.network_test_route_unavailable); return
    }
    state.assessedInputs = inputIdentity?.let { AssessmentInputs.capture(settings, diagnostics) }
    state.inputIdentity = inputIdentity
    state.deviceFactsCurrent = deviceFactsCurrent
    state.networkLabel = networkLabel
    if (!state.inputsValid()) { state.invalidateInputs(context); return }
    state.assessedProfileId = profileId
    state.passRows = emptyList()
    state.passNotes = emptyMap()
    state.sweepState = ""
    state.runError = null
    state.selectedProfile = null
    state.result = null
    state.applyArmed = false
    state.appliedCount = null
    state.run.start(scope) execute@{ current ->
        try {
            if (!state.inputsValid()) { state.invalidateInputs(context); return@execute }
            val outcome = DeviceAssessmentEngine.run(
                context = context,
                activity = context.findActivity(),
                settings = settings,
                diagnostics = diagnostics.withHeaders(extraHeaders),
                onSweepState = { if (current()) state.sweepState = it },
                onSweepPassAdded = { label ->
                    if (current()) state.passRows = state.passRows + (label to null)
                },
                onSweepPassResult = { label, mbps, note -> if (current()) {
                    state.passRows = state.passRows.map { if (it.first == label) label to mbps else it }
                    if (note != null) state.passNotes = state.passNotes + (label to note)
                } }
            )
            if (current() && !state.networkValid()) { state.invalidateNetwork(context) }
            else if (current() && (!state.inputsValid() || !state.factsValid(outcome))) {
                state.cancel(); state.runError = context.getString(R.string.assessment_inputs_changed)
            } else if (current()) {
                state.result = outcome
                state.selectedProfile = outcome.suggestedProfile
            }
        } catch (e: DiagnosticRunCoordinator.Unavailable) {
            if (current()) state.runError = e.displayMessage(context)
        }
    }
}

internal fun runApplyAssessment(
    scope: CoroutineScope,
    context: Context,
    state: DeviceAssessmentState,
    dataStore: com.nuvio.tv.data.local.PlayerSettingsDataStore = assessmentDataStore(context)
) {
    val res = state.result ?: return
    if (state.applying || state.running) return
    if (!state.networkValid()) { state.invalidateNetwork(context); return }
    if (!state.inputsValid() || !state.factsValid(res)) {
        state.cancel(); state.runError = context.getString(R.string.assessment_inputs_changed); return
    }
    val profileId = state.assessedProfileId
    if (profileId == null || profileId != dataStore.activeProfileId) {
        state.appliedCount = null
        state.applyArmed = false
        state.runError = context.getString(R.string.assessment_profile_changed)
        return
    }
    val observedNetwork = state.assessedNetwork
    val networkIdentity = state.networkIdentity
    fun networkCurrent() = networkIdentity == null || (observedNetwork != null && observedNetwork == networkIdentity())
    // Retain guards independently of mutable UI state/cancellation.
    val observedInputs = state.assessedInputs
    val inputIdentity = state.inputIdentity
    val factsCheck = state.deviceFactsCurrent
    val observedFacts = if (factsCheck != null) res.deviceFacts else null
    fun inputsCurrent() = inputIdentity == null || (observedInputs != null && observedInputs.sameAs(inputIdentity()))
    fun factsCurrent() = factsCheck == null || (observedFacts != null && runCatching { factsCheck(observedFacts) }.getOrDefault(false))
    fun stillCurrent() = networkCurrent() && inputsCurrent() && factsCurrent() && profileId == dataStore.activeProfileId
    val selectedProfile = res.profiles.firstOrNull { it.kind == state.selectedProfile }
    state.runError = null
    state.appliedCount = null
    state.applyArmed = false
    state.mutation.start(scope) { current ->
        try {
            // Recheck after dispatch: queued Apply must not start after a profile switch.
            if (!networkCurrent()) {
                state.invalidateNetwork(context)
                state.runError = context.getString(R.string.network_test_network_changed)
            } else if (profileId != dataStore.activeProfileId) {
                state.runError = context.getString(R.string.assessment_profile_changed)
            } else if (!inputsCurrent() || !factsCurrent()) {
                state.cancel(); state.runError = context.getString(R.string.assessment_inputs_changed)
            } else {
                val outcome = if (observedInputs != null) DeviceAssessmentApplier.applyValidated(
                    dataStore, res.applyPlan, selectedProfile, res.header.safeLimitMb, profileId, observedInputs, ::stillCurrent)
                else if (networkIdentity != null) DeviceAssessmentApplier.applyIfCurrent(
                    dataStore, res.applyPlan, selectedProfile, res.header.safeLimitMb, profileId, ::networkCurrent)
                else DeviceAssessmentApplier.apply(dataStore, res.applyPlan,
                    selectedProfile, res.header.safeLimitMb, profileId)
                if (current() && networkCurrent() && profileId == dataStore.activeProfileId) {
                    state.appliedCount = outcome.writtenCount
                    if (observedInputs != null) {
                        state.result = null; state.selectedProfile = null; state.assessedInputs = null
                        state.inputIdentity = null; state.deviceFactsCurrent = null
                        state.passRows = emptyList(); state.passNotes = emptyMap(); state.sweepState = ""
                    }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: DeviceAssessmentApplier.StaleAssessment) {
            if (current()) {
                val message = if (!networkCurrent()) R.string.network_test_network_changed else R.string.assessment_inputs_changed
                state.cancel(); state.runError = context.getString(message)
            }
        } catch (_: Exception) {
            if (current()) state.runError = context.getString(R.string.assessment_update_failed)
        } finally {
            state.applyArmed = false
        }
    }
}

internal fun runRevertAssessment(
    scope: CoroutineScope,
    context: Context,
    state: DeviceAssessmentState,
    dataStore: com.nuvio.tv.data.local.PlayerSettingsDataStore = assessmentDataStore(context)
) {
    if (state.applying || state.running) return
    val profileId = dataStore.activeProfileId
    state.runError = null
    state.appliedCount = null
    state.applyArmed = false
    state.mutation.start(scope) { current ->
        try {
            if (profileId != dataStore.activeProfileId) {
                state.runError = context.getString(R.string.assessment_profile_changed)
            } else {
                DeviceAssessmentApplier.revert(dataStore, profileId)
                if (current()) state.appliedCount = null
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (current()) state.runError = context.getString(R.string.assessment_update_failed)
        } finally {
            state.applyArmed = false
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun LazyListScope.deviceAssessmentItems(
    state: DeviceAssessmentState,
    diagnostics: LastPlaybackDiagnostics,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
    onRevert: () -> Unit
) {
    item(key = "assessment_run") {
        val hasStream = !diagnostics.streamUrl.isNullOrBlank()
        SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
            SettingsActionRow(
                // The three-line subtitle crowds the CLASSIC pill's curved corner;
                // match the screen's own 12 dp results-card shape instead.
                shape = assessmentCardShape(),
                title = stringResource(
                    if (state.running) R.string.action_cancel else R.string.assessment_run_title
                ),
                subtitle = if (state.running) {
                    null
                } else {
                    stringResource(
                        if (hasStream) R.string.assessment_run_subtitle else R.string.assessment_no_stream
                    ) + if (hasStream) "\n" + stringResource(R.string.diagnostic_comparison_limits) else ""
                },
                value = if (state.running && state.sweepState.isNotBlank()) state.sweepState else null,
                enabled = true,
                onClick = if (state.running) onCancel else onRun
            )
        }
    }

    if (state.passRows.isNotEmpty()) {
        item(key = "assessment_passes") {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(NuvioTheme.spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
                ) {
                    state.passRows.forEach { (label, speed) ->
                        AssessmentPassRow(
                            label = label,
                            speed = speed,
                            note = state.passNotes[label],
                            isRunning = state.running && state.sweepState == label &&
                                speed == null && state.passNotes[label] == null
                        )
                    }
                }
            }
        }
    }

    state.runError?.let { error ->
        item(key = "assessment_run_error") {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                Text(text = error, style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.Error, modifier = Modifier.padding(NuvioTheme.spacing.sm))
            }
        }
    }
    val res = state.result
    if (res == null) {
        assessmentMutationItems(state, onApply, onRevert)
        return
    }

    res.errorText?.let { err ->
        item(key = "assessment_error") {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.assessment_error_prefix, err),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.Error,
                    modifier = Modifier.padding(NuvioTheme.spacing.sm)
                )
            }
        }
    }

    item(key = "assessment_facts") {
        SettingsGroupCard(
            modifier = Modifier.fillMaxWidth(),
            title = stringResource(R.string.assessment_header_title)
        ) {
            // Focusable shell, so DPAD brings the facts block fully into view
            // instead of leaving it straddling the viewport between focus stops.
            AssessmentFocusCard {
            Column(
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
            ) {
                AssessmentFactRow(
                    label = stringResource(R.string.assessment_header_device),
                    value = stringResource(
                        R.string.assessment_header_device_value,
                        res.header.deviceRamLabel,
                        res.header.safeLimitMb,
                        res.header.warningLimitMb
                    )
                )
                AssessmentFactRow(
                    label = stringResource(R.string.assessment_header_display),
                    value = res.header.displaySummary
                        ?: stringResource(R.string.assessment_header_display_unknown)
                )
                AssessmentFactRow(
                    label = stringResource(R.string.assessment_header_stream),
                    value = res.header.streamLabel
                        ?: stringResource(R.string.assessment_header_stream_none),
                    valueMaxLines = 2
                )
                res.header.streamBitrateMbps?.let { mbps ->
                    AssessmentFactRow(
                        label = stringResource(when (res.header.bitrateScope) {
                            AssessmentBitrateScope.VIDEO_TRACK -> R.string.assessment_bitrate_video
                            AssessmentBitrateScope.FILE_AVERAGE -> R.string.assessment_bitrate_file
                            AssessmentBitrateScope.UNKNOWN -> R.string.assessment_header_bitrate
                        }),
                        value = "%.1f Mbps".format(mbps)
                    )
                }
                AssessmentFactRow(
                    label = stringResource(R.string.assessment_test_observed),
                    value = res.timestampMs.takeIf { it > 0 }?.let {
                        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                            .format(java.util.Date(it))
                    } ?: stringResource(R.string.assessment_observation_unknown)
                )
                state.networkLabel?.let { label ->
                    AssessmentFactRow(label = stringResource(R.string.assessment_network_observed), value = label, valueMaxLines = 2)
                }
                AssessmentFactRow(
                    label = stringResource(R.string.assessment_source_observed),
                    value = res.header.sourceObservedAtMs.takeIf { it > 0 }?.let {
                        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                            .format(java.util.Date(it))
                    } ?: stringResource(R.string.assessment_observation_unknown)
                )
                Text(text = stringResource(R.string.assessment_measurement_scope), style = MaterialTheme.typography.bodySmall)
                val hdrBits = listOfNotNull(res.header.streamDvProfile, res.header.streamHdrType)
                if (hdrBits.isNotEmpty()) {
                    AssessmentFactRow(
                        label = stringResource(R.string.assessment_header_hdr),
                        value = hdrBits.joinToString(" \u00b7 ")
                    )
                }
            }
            }
        }
    }

    item(key = "assessment_sec_measured") {
        AssessmentSectionLabel(stringResource(R.string.assessment_section_measured))
    }
    item(key = "assessment_measured") {
        val measured = res.items.filter { it.tier == AssessmentTier.MEASURED }
        SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
            if (res.sweepRan && measured.isNotEmpty()) {
                measured.forEach { AssessmentItemRow(it) }
                res.sweepVerdictText?.let { verdict ->
                    Text(
                        text = verdict,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.padding(
                            horizontal = NuvioTheme.spacing.sm,
                            vertical = NuvioTheme.spacing.xs
                        )
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.assessment_measured_skipped),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                    modifier = Modifier.padding(NuvioTheme.spacing.sm)
                )
            }
        }
    }

    item(key = "assessment_sec_calculated") {
        AssessmentSectionLabel(stringResource(R.string.assessment_section_calculated))
    }
    res.items.filter { it.tier == AssessmentTier.CALCULATED }.forEach { calcItem ->
        item(key = "assessment_calc_${calcItem.key}") {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                AssessmentItemRow(calcItem)
            }
        }
    }

    item(key = "assessment_sec_priority") {
        AssessmentSectionLabel(stringResource(R.string.assessment_section_priority))
    }
    item(key = "assessment_priority") {
        SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
            res.profiles.forEach { profile ->
                val values = if (profile.minCappedFromMs != null) {
                    stringResource(
                        R.string.assessment_profile_values_capped,
                        profile.initialBufferMs / 1000,
                        profile.rebufferMs / 1000,
                        profile.minBufferMs / 1000,
                        profile.minCappedFromMs / 1000
                    )
                } else {
                    stringResource(
                        R.string.assessment_profile_values,
                        profile.initialBufferMs / 1000,
                        profile.rebufferMs / 1000,
                        profile.minBufferMs / 1000
                    )
                }
                val suggested = res.suggestedProfile == profile.kind
                SettingsToggleRow(
                    title = if (suggested) {
                        profile.title + " \u00b7 " + stringResource(R.string.assessment_profile_suggested_tag)
                    } else {
                        profile.title
                    },
                    subtitle = profile.subtitle + "\n" + values,
                    checked = state.selectedProfile == profile.kind,
                    onToggle = {
                        state.selectedProfile =
                            if (state.selectedProfile == profile.kind) null else profile.kind
                    }
                )
            }
            run {
                val cov = res.stabilityCoV
                Text(
                    text = if (cov != null) {
                        stringResource(
                            R.string.assessment_stability_line,
                            "%.2f".format(cov),
                            res.stabilityPassCount
                        )
                    } else {
                        stringResource(
                            R.string.assessment_stability_insufficient,
                            res.stabilityPassCount
                        )
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.TextSecondary.copy(alpha = 0.6f),
                    modifier = Modifier.padding(
                        horizontal = NuvioTheme.spacing.sm,
                        vertical = NuvioTheme.spacing.xs
                    )
                )
            }
        }
    }

    item(key = "assessment_sec_verify") {
        AssessmentSectionLabel(stringResource(R.string.assessment_section_verify))
    }
    res.items.filter { it.tier == AssessmentTier.VERIFY }.forEach { verifyItem ->
        item(key = "assessment_verify_${verifyItem.key}") {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                AssessmentItemRow(verifyItem)
            }
        }
    }

    assessmentMutationItems(state, onApply, onRevert)

    item(key = "assessment_footer") {
        Text(
            text = stringResource(R.string.assessment_footer),
            style = MaterialTheme.typography.labelSmall,
            color = NuvioTheme.colors.TextSecondary.copy(alpha = 0.6f)
        )
    }
}

/** Rollback stays reachable after result invalidation and when reopening settings. */
private fun LazyListScope.assessmentMutationItems(
    state: DeviceAssessmentState, onApply: () -> Unit, onRevert: () -> Unit
) {
    item(key = "assessment_apply") {
        val context = LocalContext.current
        val dataStore = remember { assessmentDataStore(context) }
        val snapshotJson by dataStore.assessmentRevertSnapshot.collectAsStateWithLifecycle(
            initialValue = null
        )
        val res = state.result
        if (res != null || snapshotJson != null || state.appliedCount != null) {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                if (res == null) state.appliedCount?.let {
                    Text(text = stringResource(R.string.assessment_apply_done_sub, it), style = MaterialTheme.typography.bodySmall)
                }
                if (res != null) {
                    val previewCount = res.applyPlan.touchedCount +
                        (if (state.selectedProfile != null) 3 else 0)
                    SettingsActionRow(
                        title = stringResource(
                            if (state.applying) R.string.assessment_apply_applying
                            else R.string.assessment_apply_title
                        ),
                        subtitle = when {
                            state.appliedCount != null -> stringResource(
                                R.string.assessment_apply_done_sub, state.appliedCount ?: 0
                            )
                            state.applyArmed -> stringResource(
                                R.string.assessment_apply_confirm_sub, previewCount
                            )
                            else -> stringResource(R.string.assessment_apply_arm_sub)
                        },
                        enabled = !state.applying && previewCount > 0,
                        onClick = {
                            if (state.applyArmed) onApply() else state.applyArmed = true
                        }
                    )
                }
                if (snapshotJson != null) {
                    SettingsActionRow(
                        title = stringResource(R.string.assessment_revert_title),
                        subtitle = stringResource(R.string.assessment_revert_sub),
                        enabled = !state.applying,
                        onClick = onRevert
                    )
                }
            }
        }
    }
}

/** Focusable read-only recommendation row: title + recommended value, grounds, current. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun AssessmentItemRow(item: AssessmentItem) {
    var isFocused by remember { mutableStateOf(false) }
    val zen = isFlatSettingsStyle()
    val valueColor = when (item.memoryStatus) {
        com.nuvio.tv.ui.screens.settings.MemoryUsageStatus.DANGER -> NuvioTheme.colors.Error
        com.nuvio.tv.ui.screens.settings.MemoryUsageStatus.WARNING ->
            NuvioTheme.colors.Error.copy(alpha = 0.75f)
        else -> NuvioTheme.colors.TextPrimary
    }
    Card(
        onClick = {},
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = if (zen) androidx.compose.ui.graphics.Color.Transparent
            else NuvioTheme.colors.Background,
            focusedContainerColor = if (zen) settingsFocusFillColor()
            else NuvioTheme.colors.Background
        ),
        border = if (zen) {
            CardDefaults.border(border = Border.None, focusedBorder = Border.None)
        } else {
            CardDefaults.border(
                focusedBorder = Border(
                    border = BorderStroke(NuvioTheme.spacing.xxs, Color.White),
                    shape = assessmentCardShape()
                )
            )
        },
        shape = CardDefaults.shape(assessmentCardShape()),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = NuvioTheme.spacing.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
                Text(
                    text = item.recommendedValue,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = valueColor
                )
            }
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))
            Text(
                text = item.grounds,
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )
            if (item.changeNeeded && !item.currentValue.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))
                Text(
                    text = stringResource(R.string.assessment_currently, item.currentValue),
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.TextTertiary
                )
            } else if (!item.changeNeeded && !item.currentValue.isNullOrBlank() &&
                item.recommendedValue != stringResource(R.string.assessment_value_your_call) &&
                item.recommendedValue != stringResource(R.string.assessment_value_leave_as_is)
            ) {
                // Endorsement marker: the row asserts a concrete value and the
                // device already holds it. "No change" stays reserved for rows
                // where the assessment declines to recommend at all.
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))
                Text(
                    text = stringResource(R.string.assessment_already_set),
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.Success
                )
            }
        }
    }
}

@Composable
private fun AssessmentFocusCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val zen = isFlatSettingsStyle()
    Card(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.colors(
            containerColor = if (zen) androidx.compose.ui.graphics.Color.Transparent
            else NuvioTheme.colors.Background,
            focusedContainerColor = if (zen) settingsFocusFillColor()
            else NuvioTheme.colors.Background
        ),
        border = if (zen) {
            CardDefaults.border(border = Border.None, focusedBorder = Border.None)
        } else {
            CardDefaults.border(
                focusedBorder = Border(
                    border = BorderStroke(NuvioTheme.spacing.xxs, Color.White),
                    shape = assessmentCardShape()
                )
            )
        },
        shape = CardDefaults.shape(assessmentCardShape()),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = NuvioTheme.spacing.sm),
            content = content
        )
    }
}

@Composable
private fun AssessmentFactRow(label: String, value: String, valueMaxLines: Int = 1) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary
        )
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = NuvioTheme.colors.TextPrimary,
            maxLines = valueMaxLines,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun AssessmentSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = NuvioTheme.colors.TextTertiary,
        modifier = Modifier.padding(top = NuvioTheme.spacing.xs)
    )
}

@Composable
private fun AssessmentPassRow(
    label: String,
    speed: Double?,
    note: StreamSweepEngine.PassNote? = null,
    isRunning: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary
            )
            note?.detail?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextTertiary
                )
            }
        }
        Text(
            text = when {
                isRunning -> stringResource(R.string.stream_test_btn_running)
                speed != null -> "%.1f Mbps".format(speed)
                note != null -> note.state
                else -> "---"
            },
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = if (speed != null && !isRunning) {
                NuvioTheme.colors.TextPrimary
            } else {
                NuvioTheme.colors.TextTertiary
            }
        )
    }
}

internal fun assessmentFactsCurrent(context: Context, settings: PlayerSettings, assessed: AssessmentDeviceFacts): Boolean =
    runCatching { assessed.sameAs(AssessmentDeviceFacts.capture(context, context.findActivity(), settings)) }.getOrDefault(false)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
