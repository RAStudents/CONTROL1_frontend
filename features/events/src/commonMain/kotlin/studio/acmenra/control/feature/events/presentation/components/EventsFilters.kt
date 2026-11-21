package studio.acmenra.control.feature.events.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.events_filter_date_all
import studio.acmenra.control.core.ui.generated.resources.events_filter_date_label
import studio.acmenra.control.core.ui.generated.resources.events_filter_locations_label
import studio.acmenra.control.core.ui.generated.resources.events_filter_locations_selected
import studio.acmenra.control.core.ui.generated.resources.events_filter_types_label
import studio.acmenra.control.core.ui.generated.resources.events_filter_types_selected
import studio.acmenra.control.core.ui.generated.resources.events_filters_reset
import studio.acmenra.control.core.ui.generated.resources.events_search_id_label
import studio.acmenra.control.core.ui.generated.resources.events_search_id_placeholder
import studio.acmenra.control.core.ui.generated.resources.events_validity_all
import studio.acmenra.control.core.ui.generated.resources.events_validity_erroneous_only
import studio.acmenra.control.core.ui.generated.resources.events_validity_valid_only
import studio.acmenra.control.core.ui.generated.resources.ic_calendar
import studio.acmenra.control.core.ui.generated.resources.ic_close
import studio.acmenra.control.core.ui.generated.resources.ic_search
import studio.acmenra.control.core.ui.theme.AmberCorners
import studio.acmenra.control.core.ui.theme.AmberDimens
import studio.acmenra.control.feature.events.domain.model.DateSelection
import studio.acmenra.control.feature.events.domain.model.EventValidity
import studio.acmenra.control.feature.events.domain.model.EventsFilter
import studio.acmenra.control.feature.events.domain.model.EventsFilterOptions
import studio.acmenra.control.feature.events.domain.model.LocationFilterGroup

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventsFilters(
    filter: EventsFilter,
    filterOptions: EventsFilterOptions?,
    onSearchIdChanged: (String) -> Unit,
    onOpenDatePicker: () -> Unit,
    onEventTypeToggled: (String) -> Unit,
    onDeviceToggled: (String) -> Unit,
    onLocationToggled: (LocationFilterGroup) -> Unit,
    onValidityChanged: (EventValidity) -> Unit,
    onResetFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var searchInput by remember(filter.searchId) { mutableStateOf(filter.searchId ?: "") }
    var isTypeMenuExpanded by remember { mutableStateOf(false) }
    var isLocationMenuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AmberDimens.GapMedium),
    ) {
        // Search by ID
        OutlinedTextField(
            value = searchInput,
            onValueChange = {
                searchInput = it
                onSearchIdChanged(it)
            },
            modifier = Modifier.fillMaxWidth().height(AmberDimens.TextFieldHeight),
            shape = RoundedCornerShape(AmberCorners.Medium),
            label = { Text(stringResource(Res.string.events_search_id_label)) },
            placeholder = { Text(stringResource(Res.string.events_search_id_placeholder)) },
            leadingIcon = {
                Icon(
                    painter = painterResource(Res.drawable.ic_search),
                    contentDescription = null,
                    modifier = Modifier.size(AmberDimens.ListIcon),
                )
            },
            trailingIcon = {
                if (searchInput.isNotEmpty()) {
                    IconButton(onClick = {
                        searchInput = ""
                        onSearchIdChanged("")
                    }) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_close),
                            contentDescription = null,
                            modifier = Modifier.size(AmberDimens.ListIcon),
                        )
                    }
                }
            },
            singleLine = true,
        )

        // Filter chips row
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AmberDimens.GapSmall),
            verticalArrangement = Arrangement.spacedBy(AmberDimens.GapSmall),
        ) {
            // Date filter chip
            val isDateFiltered = filter.dateSelection !is DateSelection.All
            FilterChip(
                selected = isDateFiltered,
                onClick = onOpenDatePicker,
                leadingIcon = {
                    Icon(
                        painter = painterResource(Res.drawable.ic_calendar),
                        contentDescription = null,
                        modifier = Modifier.size(AmberDimens.ListIcon),
                    )
                },
                label = {
                    Text(
                        if (isDateFiltered) {
                            stringResource(Res.string.events_filter_date_label)
                        } else {
                            stringResource(Res.string.events_filter_date_all)
                        },
                    )
                },
            )

            // Event Types menu chip
            val typesSelected = filter.eventTypeIds.size
            FilterChip(
                selected = typesSelected > 0,
                onClick = { isTypeMenuExpanded = true },
                label = {
                    Text(
                        if (typesSelected > 0) {
                            stringResource(Res.string.events_filter_types_selected, typesSelected)
                        } else {
                            stringResource(Res.string.events_filter_types_label)
                        },
                    )
                },
            )
            DropdownMenu(
                expanded = isTypeMenuExpanded,
                onDismissRequest = { isTypeMenuExpanded = false },
            ) {
                filterOptions?.eventTypes?.forEach { typeOption ->
                    val isSelected = typeOption.id in filter.eventTypeIds
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (isSelected) "✓ ${typeOption.name}" else typeOption.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        },
                        onClick = { onEventTypeToggled(typeOption.id) },
                    )
                }
            }

            // Locations / Cameras chip
            val devicesSelected = filter.deviceIds.size
            FilterChip(
                selected = devicesSelected > 0,
                onClick = { isLocationMenuExpanded = true },
                label = {
                    Text(
                        if (devicesSelected > 0) {
                            stringResource(Res.string.events_filter_locations_selected, devicesSelected)
                        } else {
                            stringResource(Res.string.events_filter_locations_label)
                        },
                    )
                },
            )
            DropdownMenu(
                expanded = isLocationMenuExpanded,
                onDismissRequest = { isLocationMenuExpanded = false },
            ) {
                filterOptions?.locations?.forEach { loc ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "📍 ${loc.name}",
                                style = MaterialTheme.typography.titleSmall,
                            )
                        },
                        onClick = { onLocationToggled(loc) },
                    )
                    loc.devices.forEach { dev ->
                        val isDevSelected = dev.id in filter.deviceIds
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = if (isDevSelected) "   ✓ ${dev.name}" else "     ${dev.name}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            },
                            onClick = { onDeviceToggled(dev.id) },
                        )
                    }
                }
            }

            // Validity chips
            FilterChip(
                selected = filter.validity == EventValidity.ALL,
                onClick = { onValidityChanged(EventValidity.ALL) },
                label = { Text(stringResource(Res.string.events_validity_all)) },
            )
            FilterChip(
                selected = filter.validity == EventValidity.VALID,
                onClick = { onValidityChanged(EventValidity.VALID) },
                label = { Text(stringResource(Res.string.events_validity_valid_only)) },
            )
            FilterChip(
                selected = filter.validity == EventValidity.ERRONEOUS,
                onClick = { onValidityChanged(EventValidity.ERRONEOUS) },
                label = { Text(stringResource(Res.string.events_validity_erroneous_only)) },
            )

            // Reset filters button
            if (filter.hasActiveFilters) {
                TextButton(onClick = onResetFilters) {
                    Text(stringResource(Res.string.events_filters_reset))
                }
            }
        }
    }
}
