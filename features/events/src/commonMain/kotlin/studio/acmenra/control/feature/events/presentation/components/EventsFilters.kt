package studio.acmenra.control.feature.events.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.stringResource
import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.events_date_apply
import studio.acmenra.control.core.ui.generated.resources.events_date_custom_interval
import studio.acmenra.control.core.ui.generated.resources.events_date_from_prefix
import studio.acmenra.control.core.ui.generated.resources.events_date_to_prefix
import studio.acmenra.control.core.ui.generated.resources.events_filter_clear_search_tooltip
import studio.acmenra.control.core.ui.generated.resources.events_filter_date_all
import studio.acmenra.control.core.ui.generated.resources.events_filter_date_label
import studio.acmenra.control.core.ui.generated.resources.events_filter_locations_all
import studio.acmenra.control.core.ui.generated.resources.events_filter_locations_label
import studio.acmenra.control.core.ui.generated.resources.events_filter_nothing_found
import studio.acmenra.control.core.ui.generated.resources.events_filter_options_loading
import studio.acmenra.control.core.ui.generated.resources.events_filter_reset_tooltip
import studio.acmenra.control.core.ui.generated.resources.events_filter_search_placeholder
import studio.acmenra.control.core.ui.generated.resources.events_filter_types_all
import studio.acmenra.control.core.ui.generated.resources.events_filter_types_label
import studio.acmenra.control.core.ui.generated.resources.events_filters_reset
import studio.acmenra.control.core.ui.generated.resources.events_search_id_label
import studio.acmenra.control.core.ui.generated.resources.events_search_id_placeholder
import studio.acmenra.control.core.ui.theme.AmberDimens
import studio.acmenra.control.feature.events.domain.model.DeviceFilterOption
import studio.acmenra.control.feature.events.domain.model.EventsFilter
import studio.acmenra.control.feature.events.domain.model.EventsFilterOptions
import studio.acmenra.control.feature.events.domain.model.LocationFilterGroup
import studio.acmenra.control.feature.events.presentation.util.EventDateDisplayFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsFilters(
    filter: EventsFilter,
    filterOptions: EventsFilterOptions?,
    onSearchIdChanged: (String) -> Unit,
    onSearchIdCommitted: (String) -> Unit,
    onOpenDatePicker: () -> Unit,
    onResetDate: () -> Unit,
    onEventTypesApplied: (Set<String>) -> Unit,
    onDevicesApplied: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
    onResetFilters: (() -> Unit)? = null,
    businessTimeZone: TimeZone? = null,
    isFilterOptionsLoading: Boolean = false,
    isCompact: Boolean = false,
) {
    var searchIdInput by remember(filter.searchId) { mutableStateOf(filter.searchId ?: "") }

    val customIntervalLabel = stringResource(Res.string.events_date_custom_interval)
    val fromPrefix = stringResource(Res.string.events_date_from_prefix)
    val toPrefix = stringResource(Res.string.events_date_to_prefix)
    val formattedDate =
        remember(filter.dateFrom, filter.dateTo, businessTimeZone, customIntervalLabel, fromPrefix, toPrefix) {
            EventDateDisplayFormatter.formatFilterDisplayDate(
                dateFrom = filter.dateFrom,
                dateTo = filter.dateTo,
                businessTimeZone = businessTimeZone,
                fromPrefix = fromPrefix,
                toPrefix = toPrefix,
                customIntervalPrefix = customIntervalLabel,
            )
        }

    val eventTypes = filterOptions?.eventTypes ?: emptyList()
    val selectedTypesSummary =
        remember(filter.eventTypeIds, eventTypes) {
            if (filter.eventTypeIds.isEmpty()) {
                ""
            } else {
                val firstMatch = eventTypes.firstOrNull { it.id in filter.eventTypeIds }
                if (firstMatch != null) {
                    val remaining = filter.eventTypeIds.size - 1
                    if (remaining > 0) {
                        "${firstMatch.name} (+$remaining)"
                    } else {
                        firstMatch.name
                    }
                } else {
                    "${filter.eventTypeIds.size}"
                }
            }
        }

    val locations = filterOptions?.locations ?: emptyList()
    val allDevices = remember(locations) { locations.flatMap { it.devices } }
    val selectedLocationsSummary =
        remember(filter.deviceIds, allDevices) {
            if (filter.deviceIds.isEmpty()) {
                ""
            } else {
                val firstMatch = allDevices.firstOrNull { it.id in filter.deviceIds }
                if (firstMatch != null) {
                    val remaining = filter.deviceIds.size - 1
                    if (remaining > 0) {
                        "${firstMatch.name} (+$remaining)"
                    } else {
                        firstMatch.name
                    }
                } else {
                    "${filter.deviceIds.size}"
                }
            }
        }

    val idField = @Composable { fieldModifier: Modifier ->
        EventIdOutlinedField(
            value = searchIdInput,
            label = stringResource(Res.string.events_search_id_label),
            placeholder = stringResource(Res.string.events_search_id_placeholder),
            clearTooltip = stringResource(Res.string.events_filter_clear_search_tooltip),
            onValueChange = { newText ->
                searchIdInput = newText
                onSearchIdChanged(newText)
            },
            onCommit = { committedText ->
                onSearchIdCommitted(committedText.trim())
            },
            onClear = {
                searchIdInput = ""
                onSearchIdCommitted("")
            },
            modifier = fieldModifier,
        )
    }

    val dateField = @Composable { fieldModifier: Modifier ->
        EventDateOutlinedField(
            value = formattedDate,
            label = stringResource(Res.string.events_filter_date_label),
            allDatesPlaceholder = stringResource(Res.string.events_filter_date_all),
            resetTooltip = stringResource(Res.string.events_filter_reset_tooltip),
            enabled = businessTimeZone != null,
            onOpen = onOpenDatePicker,
            onReset = onResetDate,
            modifier = fieldModifier,
        )
    }

    val typesCombobox = @Composable { fieldModifier: Modifier ->
        AnimatedExposedFilterField(
            label = stringResource(Res.string.events_filter_types_label),
            summaryText = selectedTypesSummary,
            emptyPlaceholder = stringResource(Res.string.events_filter_types_all),
            searchPlaceholder = stringResource(Res.string.events_filter_search_placeholder),
            applyLabel = stringResource(Res.string.events_date_apply),
            clearSearchTooltip = stringResource(Res.string.events_filter_clear_search_tooltip),
            resetTooltip = stringResource(Res.string.events_filter_reset_tooltip),
            appliedIds = filter.eventTypeIds,
            enabled = true,
            isLoading = isFilterOptionsLoading,
            loadingDescription = stringResource(Res.string.events_filter_options_loading),
            onApply = onEventTypesApplied,
            onResetApplied = { onEventTypesApplied(emptySet()) },
            modifier = fieldModifier,
            dropdownContent = { query, draftIds, onDraftIdsChange ->
                val visible =
                    eventTypes.filter {
                        query.isBlank() || it.name.contains(query.trim(), ignoreCase = true)
                    }
                if (visible.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.events_filter_nothing_found),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(AmberDimens.GapSmall),
                    )
                } else {
                    visible.forEach { option ->
                        val selected = option.id in draftIds
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = option.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingIcon = { Checkbox(checked = selected, onCheckedChange = null) },
                            onClick = {
                                onDraftIdsChange(
                                    if (selected) draftIds - option.id else draftIds + option.id,
                                )
                            },
                        )
                    }
                }
            },
        )
    }

    val locationsCombobox = @Composable { fieldModifier: Modifier ->
        AnimatedExposedFilterField(
            label = stringResource(Res.string.events_filter_locations_label),
            summaryText = selectedLocationsSummary,
            emptyPlaceholder = stringResource(Res.string.events_filter_locations_all),
            searchPlaceholder = stringResource(Res.string.events_filter_search_placeholder),
            applyLabel = stringResource(Res.string.events_date_apply),
            clearSearchTooltip = stringResource(Res.string.events_filter_clear_search_tooltip),
            resetTooltip = stringResource(Res.string.events_filter_reset_tooltip),
            appliedIds = filter.deviceIds,
            enabled = true,
            isLoading = isFilterOptionsLoading,
            loadingDescription = stringResource(Res.string.events_filter_options_loading),
            onApply = onDevicesApplied,
            onResetApplied = { onDevicesApplied(emptySet()) },
            modifier = fieldModifier,
            dropdownContent = { query, draftIds, onDraftIdsChange ->
                data class FilteredGroup(
                    val location: LocationFilterGroup,
                    val visibleDevices: List<DeviceFilterOption>,
                )

                val filteredGroups =
                    locations.mapNotNull { loc ->
                        val locMatches =
                            query.isBlank() || loc.name.contains(query.trim(), ignoreCase = true)
                        if (locMatches) {
                            FilteredGroup(loc, loc.devices)
                        } else {
                            val matchingDevs =
                                loc.devices.filter {
                                    it.name.contains(query.trim(), ignoreCase = true)
                                }
                            if (matchingDevs.isNotEmpty()) {
                                FilteredGroup(loc, matchingDevs)
                            } else {
                                null
                            }
                        }
                    }

                if (filteredGroups.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.events_filter_nothing_found),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(AmberDimens.GapSmall),
                    )
                } else {
                    filteredGroups.forEach { group ->
                        val loc = group.location
                        val selectableIds = loc.devices.map { it.id }.toSet()
                        val selectedCount = selectableIds.count { it in draftIds }
                        val triState =
                            when {
                                selectableIds.isEmpty() -> ToggleableState.Off
                                selectedCount == 0 -> ToggleableState.Off
                                selectedCount == selectableIds.size -> ToggleableState.On
                                else -> ToggleableState.Indeterminate
                            }

                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = loc.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingIcon = {
                                TriStateCheckbox(
                                    state = triState,
                                    onClick = null,
                                    enabled = selectableIds.isNotEmpty(),
                                )
                            },
                            enabled = selectableIds.isNotEmpty(),
                            onClick = {
                                val updated =
                                    if (triState == ToggleableState.On) {
                                        draftIds - selectableIds
                                    } else {
                                        draftIds + selectableIds
                                    }
                                onDraftIdsChange(updated)
                            },
                        )

                        group.visibleDevices.forEach { dev ->
                            val isDevSelected = dev.id in draftIds
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = dev.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                leadingIcon = {
                                    Checkbox(
                                        checked = isDevSelected,
                                        onCheckedChange = null,
                                    )
                                },
                                modifier = Modifier.padding(start = AmberDimens.EventsDropdownIndent),
                                onClick = {
                                    val updated =
                                        if (isDevSelected) {
                                            draftIds - dev.id
                                        } else {
                                            draftIds + dev.id
                                        }
                                    onDraftIdsChange(updated)
                                },
                            )
                        }
                    }
                }
            },
        )
    }

    if (isCompact) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AmberDimens.GapSmall),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val isVeryNarrow = maxWidth < 340.dp
                if (isVeryNarrow) {
                    Column(verticalArrangement = Arrangement.spacedBy(AmberDimens.GapSmall)) {
                        idField(Modifier.fillMaxWidth())
                        dateField(Modifier.fillMaxWidth())
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AmberDimens.GapSmall),
                    ) {
                        idField(Modifier.weight(1f))
                        dateField(Modifier.weight(1f))
                    }
                }
            }
            typesCombobox(Modifier.fillMaxWidth())
            locationsCombobox(Modifier.fillMaxWidth())
            if (onResetFilters != null && filter.hasActiveFilters) {
                TextButton(
                    onClick = onResetFilters,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(Res.string.events_filters_reset))
                }
            }
        }
    } else {
        // Wide and Medium: 2 rows of 2 columns
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AmberDimens.GapMedium),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AmberDimens.GapMedium),
            ) {
                idField(Modifier.weight(1f))
                typesCombobox(Modifier.weight(1f))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AmberDimens.GapMedium),
            ) {
                dateField(Modifier.weight(1f))
                locationsCombobox(Modifier.weight(1f))
            }
            if (onResetFilters != null && filter.hasActiveFilters) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onResetFilters) {
                        Text(stringResource(Res.string.events_filters_reset))
                    }
                }
            }
        }
    }
}
