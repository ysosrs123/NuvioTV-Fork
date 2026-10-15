package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.calendar.CALENDAR_WINDOW_OPTIONS
import com.nuvio.tv.core.calendar.CalendarSettings
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.calendar.CalendarDialogAction
import com.nuvio.tv.ui.screens.calendar.CalendarScreenData
import com.nuvio.tv.ui.screens.calendar.androidExemplarCity
import com.nuvio.tv.ui.screens.calendar.calendarZoneName
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CalendarZoneOption(val id: String, val city: String, val region: String, val offset: String)

private val ZONE_REGIONS = setOf("Africa", "America", "Antarctica", "Asia", "Atlantic", "Australia", "Europe", "Indian", "Pacific")

/** Every named place, west to east, then by name. [regionName] gives "Europe", "Pacific" and so on in the app's language. */
fun calendarZoneOptions(
    now: Instant,
    cityName: (String) -> String? = { null },
    regionName: (String) -> String = { it }
): List<CalendarZoneOption> =
    ZoneId.getAvailableZoneIds()
        .filter { it.substringBefore('/') in ZONE_REGIONS && '/' in it }
        .map { id ->
            val offset = ZoneId.of(id).rules.getOffset(now)
            Triple(id, offset, cityName(id)?.takeIf(String::isNotBlank) ?: id.substringAfterLast('/').replace('_', ' '))
        }
        .sortedWith(compareBy<Triple<String, ZoneOffset, String>> { it.second.totalSeconds }.thenBy { it.third })
        .map { (id, offset, city) ->
            CalendarZoneOption(
                id = id,
                city = city,
                region = id.substringBeforeLast('/').split('/').mapIndexed { index, part ->
                    if (index == 0) regionName(part) else part.replace('_', ' ')
                }.joinToString(" / "),
                offset = if (offset == ZoneOffset.UTC) "GMT" else "GMT${offset.id}"
            )
        }

@HiltViewModel
class CalendarSettingsViewModel @Inject constructor(
    private val data: CalendarScreenData
) : ViewModel() {
    val settings: StateFlow<CalendarSettings?> = data.settings

    fun now(): Instant = data.now()

    fun setMenuVisible(visible: Boolean) = viewModelScope.launch { data.setMenuVisible(visible) }
    fun setWindowDays(days: Int) = viewModelScope.launch { data.setWindowDays(days) }
    fun setZone(zone: String?) = viewModelScope.launch { data.setZoneOverride(zone) }
    fun setCornerMessage(enabled: Boolean) = viewModelScope.launch { data.setCornerMessage(enabled) }
    fun setIncludePersonalLists(include: Boolean) = viewModelScope.launch { data.setIncludePersonalLists(include) }
    fun setComingUpRow(enabled: Boolean) = viewModelScope.launch { data.setComingUpRow(enabled) }
    fun unhide(titleKey: String) = viewModelScope.launch { data.setHidden(titleKey, hidden = false, name = null) }

    suspend fun zoneOptions(locale: Locale, regionNames: Map<String, String>): List<CalendarZoneOption> =
        withContext(Dispatchers.Default) {
            calendarZoneOptions(data.now(), androidExemplarCity(locale)) { regionNames[it] ?: it }
        }
}

/** Lives in Layout > Home content with the other Home rows. */
@Composable
internal fun CalendarComingUpRowSetting(viewModel: CalendarSettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsState()
    val current = settings ?: return
    SettingsToggleRow(
        title = stringResource(R.string.calendar_setting_coming_up),
        subtitle = stringResource(R.string.calendar_setting_coming_up_sub),
        checked = current.comingUpRow,
        onToggle = { viewModel.setComingUpRow(!current.comingUpRow) }
    )
}

@Composable
internal fun LayoutCalendarSection(viewModel: CalendarSettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsState()
    val current = settings ?: return
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    var dialog by remember { mutableStateOf<String?>(null) }

    SettingsToggleRow(
        title = stringResource(R.string.calendar_setting_menu),
        subtitle = stringResource(R.string.calendar_setting_menu_sub),
        checked = current.menuVisible,
        onToggle = { viewModel.setMenuVisible(!current.menuVisible) }
    )
    SettingsActionRow(
        title = stringResource(R.string.calendar_setting_days),
        subtitle = stringResource(R.string.calendar_setting_days_sub),
        value = pluralStringResource(R.plurals.calendar_setting_days_value, current.windowDays, current.windowDays),
        onClick = { dialog = "days" }
    )
    val boxZone = ZoneId.systemDefault()
    val shownZone = current.zoneOverride?.let(ZoneId::of) ?: boxZone
    val zoneName = remember(shownZone, locale) { calendarZoneName(shownZone, viewModel.now(), locale, androidExemplarCity(locale)) }
    SettingsActionRow(
        title = stringResource(R.string.calendar_setting_zone),
        subtitle = stringResource(
            if (current.zoneOverride == null) R.string.calendar_setting_zone_sub_box else R.string.calendar_setting_zone_sub_set,
            zoneName.short
        ),
        value = zoneName.city ?: zoneName.short,
        onClick = { dialog = "zone" }
    )
    SettingsToggleRow(
        title = stringResource(R.string.calendar_setting_corner),
        subtitle = stringResource(R.string.calendar_setting_corner_sub),
        checked = current.cornerMessage,
        onToggle = { viewModel.setCornerMessage(!current.cornerMessage) }
    )
    SettingsToggleRow(
        title = stringResource(R.string.calendar_setting_lists),
        subtitle = stringResource(R.string.calendar_setting_lists_sub),
        checked = current.includePersonalLists,
        onToggle = { viewModel.setIncludePersonalLists(!current.includePersonalLists) }
    )
    val hiddenCount = current.hiddenTitleKeys.size
    SettingsActionRow(
        title = stringResource(R.string.calendar_setting_hidden),
        subtitle = stringResource(R.string.calendar_setting_hidden_sub),
        value = if (hiddenCount == 0) stringResource(R.string.action_none)
        else pluralStringResource(R.plurals.calendar_setting_hidden_value, hiddenCount, hiddenCount),
        onClick = { dialog = "hidden" }
    )

    when (dialog) {
        "days" -> SettingsSingleChoiceDialog(
            title = stringResource(R.string.calendar_setting_days),
            options = CALENDAR_WINDOW_OPTIONS.map { days ->
                SettingsPickerOption(days, pluralStringResource(R.plurals.calendar_setting_days_value, days, days))
            },
            selectedValue = current.windowDays,
            onOptionSelected = { viewModel.setWindowDays(it); dialog = null },
            onDismiss = { dialog = null }
        )
        "zone" -> CalendarZoneDialog(
            viewModel = viewModel,
            selected = current.zoneOverride,
            boxZoneLabel = zoneLabel(boxZone, viewModel.now(), locale),
            locale = locale,
            onDismiss = { dialog = null }
        )
        "hidden" -> CalendarHiddenDialog(
            settings = current,
            onUnhide = { key ->
                viewModel.unhide(key)
                if (hiddenCount <= 1) dialog = null
            },
            onDismiss = { dialog = null }
        )
    }
}

@Composable
private fun zoneLabel(zone: ZoneId, now: Instant, locale: Locale): String {
    val name = remember(zone, locale) { calendarZoneName(zone, now, locale, androidExemplarCity(locale)) }
    return name.city?.let { stringResource(R.string.calendar_zone_with_city, it, name.short) } ?: name.short
}

@Composable
private fun CalendarZoneDialog(
    viewModel: CalendarSettingsViewModel,
    selected: String?,
    boxZoneLabel: String,
    locale: Locale,
    onDismiss: () -> Unit
) {
    var zones by remember { mutableStateOf<List<CalendarZoneOption>?>(null) }
    val regionNames = calendarZoneRegionNames()
    LaunchedEffect(locale, regionNames) { zones = viewModel.zoneOptions(locale, regionNames) }
    val loaded = zones ?: return
    val options = buildList {
        add(
            SettingsPickerOption(
                "",
                stringResource(R.string.calendar_zone_follow_box),
                stringResource(R.string.calendar_zone_follow_box_desc, boxZoneLabel)
            )
        )
        loaded.forEach { add(SettingsPickerOption(it.id, it.city, it.region, it.offset)) }
    }
    SettingsSingleChoiceDialog(
        title = stringResource(R.string.calendar_setting_zone),
        options = options,
        selectedValue = selected.orEmpty(),
        onOptionSelected = { id ->
            viewModel.setZone(id.ifEmpty { null })
            onDismiss()
        },
        onDismiss = onDismiss,
        width = 480.dp,
        maxHeight = 420.dp
    )
}

@Composable
private fun CalendarHiddenDialog(
    settings: CalendarSettings,
    onUnhide: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val first = remember { FocusRequester() }
    val keys = settings.hiddenTitleKeys.sortedBy { (settings.hiddenTitleNames[it] ?: it).lowercase() }
    LaunchedEffect(keys.isEmpty()) { if (keys.isNotEmpty()) first.requestFocusAfterFrames() }
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.calendar_hidden_dialog_title),
        subtitle = stringResource(R.string.calendar_hidden_dialog_subtitle)
    ) {
        if (keys.isEmpty()) {
            Text(
                text = stringResource(R.string.calendar_hidden_none),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
            ) {
                itemsIndexed(keys, key = { _, key -> key }) { index, key ->
                    CalendarDialogAction(
                        label = settings.hiddenTitleNames[key] ?: key,
                        onClick = { onUnhide(key) },
                        focusRequester = if (index == 0) first else null
                    )
                }
            }
        }
    }
}

@Composable
private fun calendarZoneRegionNames(): Map<String, String> = mapOf(
    "Africa" to stringResource(R.string.calendar_zone_region_africa),
    "America" to stringResource(R.string.calendar_zone_region_america),
    "Antarctica" to stringResource(R.string.calendar_zone_region_antarctica),
    "Asia" to stringResource(R.string.calendar_zone_region_asia),
    "Atlantic" to stringResource(R.string.calendar_zone_region_atlantic),
    "Australia" to stringResource(R.string.calendar_zone_region_australia),
    "Europe" to stringResource(R.string.calendar_zone_region_europe),
    "Indian" to stringResource(R.string.calendar_zone_region_indian),
    "Pacific" to stringResource(R.string.calendar_zone_region_pacific)
)
