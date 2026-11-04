@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.network.ArcDns
import com.nuvio.tv.core.network.DnsChoice
import com.nuvio.tv.core.network.DnsService
import com.nuvio.tv.core.network.isUnreachable
import com.nuvio.tv.core.network.parseDohUrl
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.UnknownHostException

private val DNS_TEST_HOSTS = listOf("TMDB" to "api.themoviedb.org", "Cinemeta" to "v3-cinemeta.strem.io")
private const val DNS_CHECK_HOST = "api.themoviedb.org"

@Composable
private fun dnsServiceLabel(service: DnsService): String = when (service) {
    DnsService.SYSTEM -> stringResource(R.string.advanced_dns_system)
    DnsService.CLOUDFLARE -> "Cloudflare"
    DnsService.GOOGLE -> "Google"
    DnsService.QUAD9 -> "Quad9"
    DnsService.ADGUARD -> "AdGuard"
    DnsService.CUSTOM -> stringResource(R.string.advanced_dns_custom)
}

@Composable
internal fun DnsSettingsGroup(modifier: Modifier = Modifier) {
    val choice by ArcDns.choice.collectAsStateWithLifecycle()
    val reachable by ArcDns.reachable.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }
    var showCustom by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember(choice) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val label = dnsServiceLabel(choice.service)

    SettingsGroupCard(
        modifier = modifier.fillMaxWidth(),
        title = stringResource(R.string.advanced_section_network)
    ) {
        SettingsActionRow(
            title = stringResource(R.string.advanced_dns),
            subtitle = stringResource(R.string.advanced_dns_subtitle),
            value = label,
            onClick = { showPicker = true }
        )
        if (choice.service != DnsService.SYSTEM) {
            if (reachable) {
                SettingsNote(text = stringResource(R.string.advanced_dns_note))
            } else {
                SettingsNote(
                    text = stringResource(R.string.advanced_dns_unreachable, label),
                    tone = SettingsNoteTone.Warning
                )
            }
            SettingsActionRow(
                title = stringResource(R.string.advanced_dns_test),
                subtitle = stringResource(R.string.advanced_dns_test_subtitle),
                value = if (testing) stringResource(R.string.servers_second_address_checking) else testResult,
                trailingIcon = null,
                onClick = {
                    if (!testing) {
                        testing = true
                        scope.launch {
                            testResult = withContext(Dispatchers.IO) { runDnsTest(context) }
                            testing = false
                        }
                    }
                }
            )
        }
    }

    if (showPicker) {
        SettingsSingleChoiceDialog(
            title = stringResource(R.string.advanced_dns),
            subtitle = stringResource(R.string.advanced_dns_subtitle),
            options = DnsService.entries.map { service ->
                SettingsPickerOption(
                    value = service,
                    title = dnsServiceLabel(service),
                    description = when (service) {
                        DnsService.SYSTEM -> stringResource(R.string.advanced_dns_system_description)
                        DnsService.QUAD9 -> service.bootstrap.joinToString() + ". " +
                            stringResource(R.string.advanced_dns_quad9_description)
                        DnsService.ADGUARD -> service.bootstrap.joinToString() + ". " +
                            stringResource(R.string.advanced_dns_adguard_description)
                        DnsService.CUSTOM -> choice.customUrl.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.advanced_dns_custom_description)
                        else -> service.bootstrap.joinToString()
                    },
                    warning = if (service == DnsService.ADGUARD) {
                        stringResource(R.string.advanced_dns_adguard_warning)
                    } else {
                        null
                    }
                )
            },
            selectedValue = choice.service,
            onOptionSelected = { service ->
                showPicker = false
                if (service == DnsService.CUSTOM) {
                    showCustom = true
                } else {
                    ArcDns.set(DnsChoice(service, choice.customUrl))
                }
            },
            onDismiss = { showPicker = false },
            width = 560.dp,
            maxHeight = 460.dp
        )
    }

    if (showCustom) {
        CustomDnsDialog(
            current = choice.customUrl,
            onDismiss = { showCustom = false }
        )
    }
}

private fun runDnsTest(context: android.content.Context): String {
    val parts = mutableListOf<String>()
    for ((name, host) in DNS_TEST_HOSTS) {
        try {
            val ms = ArcDns.probeCurrent(host) ?: return ""
            parts += context.getString(R.string.advanced_dns_test_time, name, ms.toInt())
        } catch (e: UnknownHostException) {
            if (e.isUnreachable()) return context.getString(R.string.advanced_dns_test_unreachable)
            parts += context.getString(R.string.advanced_dns_test_no_answer, name)
        } catch (e: Exception) {
            return context.getString(R.string.advanced_dns_test_unreachable)
        }
    }
    return parts.joinToString(", ")
}

@Composable
private fun CustomDnsDialog(current: String, onDismiss: () -> Unit) {
    var address by remember { mutableStateOf(current) }
    var checking by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val save = {
        val url = parseDohUrl(address)
        if (url == null) {
            problem = R.string.advanced_dns_custom_invalid
        } else if (!checking) {
            checking = true
            problem = null
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { ArcDns.probe(url, DNS_CHECK_HOST) }.isSuccess
                }
                checking = false
                if (ok) {
                    ArcDns.set(DnsChoice(DnsService.CUSTOM, url.toString()))
                    onDismiss()
                } else {
                    problem = R.string.advanced_dns_custom_failed
                }
            }
        }
    }

    NuvioDialog(
        onDismiss = { if (!checking) onDismiss() },
        title = stringResource(R.string.advanced_dns_custom),
        subtitle = stringResource(R.string.advanced_dns_custom_description),
        width = 640.dp
    ) {
        InputField(
            value = address,
            onValueChange = {
                address = it
                problem = null
            },
            placeholder = stringResource(R.string.advanced_dns_custom_hint),
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done,
            onImeAction = { if (address.isNotBlank()) save() },
            modifier = Modifier.fillMaxWidth()
        )
        problem?.let { res ->
            Text(
                text = stringResource(res),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.Error
            )
        }
        SettingsDialogActionRow {
            SettingsDialogActionButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                enabled = !checking
            )
            SettingsDialogActionButton(
                text = stringResource(
                    if (checking) R.string.servers_second_address_checking else R.string.servers_second_address_save
                ),
                onClick = { save() },
                primary = true,
                enabled = !checking && address.isNotBlank()
            )
        }
    }
}
