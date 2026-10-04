@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.mediaserver.AlternateAddressResult
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun AlternateAddressDialog(
    current: String,
    onSave: (String, (AlternateAddressResult) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    var address by remember { mutableStateOf(current) }
    var checking by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<AlternateAddressResult?>(null) }
    val save = { value: String ->
        if (!checking) {
            checking = true
            problem = null
            onSave(value) { result ->
                checking = false
                when (result) {
                    AlternateAddressResult.SAVED, AlternateAddressResult.CLEARED -> onDismiss()
                    else -> problem = result
                }
            }
        }
    }

    NuvioDialog(
        onDismiss = { if (!checking) onDismiss() },
        title = stringResource(R.string.servers_second_address),
        subtitle = stringResource(R.string.servers_second_address_description),
        width = 640.dp
    ) {
        InputField(
            value = address,
            onValueChange = {
                address = it
                problem = null
            },
            placeholder = stringResource(R.string.servers_second_address_hint),
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done,
            onImeAction = { if (address.isNotBlank()) save(address) },
            modifier = Modifier.fillMaxWidth()
        )
        problem?.let { result ->
            Text(
                text = stringResource(
                    when (result) {
                        AlternateAddressResult.OTHER_SERVER -> R.string.servers_second_address_other_server
                        AlternateAddressResult.SAME_ADDRESS -> R.string.servers_second_address_same
                        AlternateAddressResult.INVALID -> R.string.servers_error_address
                        else -> R.string.servers_second_address_unreachable
                    }
                ),
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
            if (current.isNotEmpty()) {
                SettingsDialogActionButton(
                    text = stringResource(R.string.servers_second_address_clear),
                    onClick = { save("") },
                    enabled = !checking
                )
            }
            SettingsDialogActionButton(
                text = stringResource(
                    if (checking) R.string.servers_second_address_checking else R.string.servers_second_address_save
                ),
                onClick = { save(address) },
                primary = true,
                enabled = !checking && address.isNotBlank()
            )
        }
    }
}
