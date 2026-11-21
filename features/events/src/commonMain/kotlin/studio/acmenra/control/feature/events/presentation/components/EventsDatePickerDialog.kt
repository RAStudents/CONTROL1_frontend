package studio.acmenra.control.feature.events.presentation.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.events_date_apply
import studio.acmenra.control.core.ui.generated.resources.events_date_cancel
import studio.acmenra.control.core.ui.generated.resources.events_date_picker_title
import studio.acmenra.control.core.ui.generated.resources.events_date_reset
import studio.acmenra.control.core.ui.generated.resources.events_filter_date_last_7_days
import studio.acmenra.control.core.ui.generated.resources.events_filter_date_today
import studio.acmenra.control.core.ui.generated.resources.events_filter_date_yesterday
import studio.acmenra.control.core.ui.theme.AmberCorners
import studio.acmenra.control.core.ui.theme.AmberDimens
import studio.acmenra.control.feature.events.domain.model.DateFilterUtils
import studio.acmenra.control.feature.events.domain.model.DateSelection
import kotlin.time.Clock

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsDatePickerDialog(
    initialSelection: DateSelection,
    onDismissRequest: () -> Unit,
    onApply: (DateSelection) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    timeZone: TimeZone? = null,
) {
    val initialStartMillis =
        remember(initialSelection) {
            when (initialSelection) {
                is DateSelection.Single ->
                    initialSelection.date
                        .atStartOfDayIn(TimeZone.UTC)
                        .toEpochMilliseconds()
                is DateSelection.Range ->
                    initialSelection.startDate
                        .atStartOfDayIn(TimeZone.UTC)
                        .toEpochMilliseconds()
                is DateSelection.From ->
                    initialSelection.startDate
                        .atStartOfDayIn(TimeZone.UTC)
                        .toEpochMilliseconds()
                is DateSelection.Until -> null
                is DateSelection.All -> null
            }
        }

    val initialEndMillis =
        remember(initialSelection) {
            when (initialSelection) {
                is DateSelection.Single ->
                    initialSelection.date
                        .atStartOfDayIn(TimeZone.UTC)
                        .toEpochMilliseconds()
                is DateSelection.Range ->
                    initialSelection.endDateInclusive
                        .atStartOfDayIn(TimeZone.UTC)
                        .toEpochMilliseconds()
                is DateSelection.Until ->
                    initialSelection.endDateInclusive
                        .atStartOfDayIn(TimeZone.UTC)
                        .toEpochMilliseconds()
                is DateSelection.From -> null
                is DateSelection.All -> null
            }
        }

    val dateRangePickerState =
        rememberDateRangePickerState(
            initialSelectedStartDateMillis = initialStartMillis,
            initialSelectedEndDateMillis = initialEndMillis,
        )

    val tz = timeZone ?: TimeZone.UTC
    val now = remember { Clock.System.now() }
    val mskLabel =
        remember(timeZone, now) {
            timeZone?.let {
                DateFilterUtils.formatMskOffset(
                    at = now,
                    businessTimeZone = it,
                    mskPrefix = "MSK",
                )
            }
        }

    Dialog(onDismissRequest = onDismissRequest) {
        Card(
            modifier =
                modifier
                    .fillMaxWidth()
                    .heightIn(max = 680.dp),
            shape = RoundedCornerShape(AmberCorners.ExtraLarge),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
        ) {
            Column(
                modifier = Modifier.padding(AmberDimens.CardPadding),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(Res.string.events_date_picker_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (mskLabel != null) {
                        Surface(
                            shape = RoundedCornerShape(AmberCorners.Small),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = mskLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier =
                                    Modifier.padding(
                                        horizontal = AmberDimens.StatusBadgePaddingHorizontal,
                                        vertical = AmberDimens.StatusBadgePaddingVertical,
                                    ),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(AmberDimens.GapSmall))

                // Presets row with horizontal scroll to avoid overflow
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(AmberDimens.GapSmall),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val today = remember(tz, now) { now.toLocalDateTime(tz).date }
                    val yesterday = remember(today) { today.minus(1, DateTimeUnit.DAY) }
                    val last7DaysStart = remember(today) { today.minus(6, DateTimeUnit.DAY) }

                    FilterChip(
                        selected = false,
                        onClick = {
                            val startMillis = today.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
                            dateRangePickerState.setSelection(startMillis, startMillis)
                        },
                        enabled = timeZone != null,
                        label = { Text(stringResource(Res.string.events_filter_date_today)) },
                    )
                    FilterChip(
                        selected = false,
                        onClick = {
                            val startMillis = yesterday.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
                            dateRangePickerState.setSelection(startMillis, startMillis)
                        },
                        enabled = timeZone != null,
                        label = { Text(stringResource(Res.string.events_filter_date_yesterday)) },
                    )
                    FilterChip(
                        selected = false,
                        onClick = {
                            val startMillis = last7DaysStart.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
                            val endMillis = today.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
                            dateRangePickerState.setSelection(startMillis, endMillis)
                        },
                        enabled = timeZone != null,
                        label = { Text(stringResource(Res.string.events_filter_date_last_7_days)) },
                    )
                }

                Spacer(modifier = Modifier.height(AmberDimens.GapSmall))

                // DateRangePicker from M3
                DateRangePicker(
                    state = dateRangePickerState,
                    title = null,
                    headline = null,
                    showModeToggle = false,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                )

                Spacer(modifier = Modifier.height(AmberDimens.GapMedium))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = {
                        onReset()
                        onDismissRequest()
                    }) {
                        Text(stringResource(Res.string.events_date_reset))
                    }

                    Row {
                        TextButton(onClick = onDismissRequest) {
                            Text(stringResource(Res.string.events_date_cancel))
                        }
                        Spacer(modifier = Modifier.width(AmberDimens.GapSmall))
                        TextButton(
                            onClick = {
                                val startMillis = dateRangePickerState.selectedStartDateMillis
                                val endMillis = dateRangePickerState.selectedEndDateMillis
                                val result =
                                    if (startMillis == null) {
                                        DateSelection.All
                                    } else {
                                        val startDate =
                                            Instant
                                                .fromEpochMilliseconds(startMillis)
                                                .toLocalDateTime(TimeZone.UTC)
                                                .date
                                        val endDateInclusive =
                                            if (endMillis != null) {
                                                Instant
                                                    .fromEpochMilliseconds(endMillis)
                                                    .toLocalDateTime(TimeZone.UTC)
                                                    .date
                                            } else {
                                                startDate
                                            }
                                        if (startDate == endDateInclusive) {
                                            DateSelection.Single(startDate)
                                        } else {
                                            DateSelection.Range(startDate, endDateInclusive)
                                        }
                                    }
                                onApply(result)
                                onDismissRequest()
                            },
                        ) {
                            Text(stringResource(Res.string.events_date_apply))
                        }
                    }
                }
            }
        }
    }
}
