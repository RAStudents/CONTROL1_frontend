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
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.events_date_apply
import studio.acmenra.control.core.ui.generated.resources.events_date_cancel
import studio.acmenra.control.core.ui.generated.resources.events_date_custom_interval
import studio.acmenra.control.core.ui.generated.resources.events_date_picker_title
import studio.acmenra.control.core.ui.generated.resources.events_date_reset
import studio.acmenra.control.core.ui.generated.resources.events_filter_date_last_7_days
import studio.acmenra.control.core.ui.generated.resources.events_filter_date_today
import studio.acmenra.control.core.ui.generated.resources.events_filter_date_yesterday
import studio.acmenra.control.core.ui.generated.resources.events_timezone_msk_prefix
import studio.acmenra.control.core.ui.theme.AmberCorners
import studio.acmenra.control.core.ui.theme.AmberDimens
import studio.acmenra.control.feature.events.domain.model.DateSelection
import studio.acmenra.control.feature.events.presentation.util.EventDateDisplayFormatter
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsDatePickerDialog(
    initialSelection: DateSelection,
    onDismissRequest: () -> Unit,
    onApply: (DateSelection) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    isCustomInterval: Boolean = false,
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
    val mskPrefix = stringResource(Res.string.events_timezone_msk_prefix)
    val mskLabel =
        remember(timeZone, now, mskPrefix) {
            timeZone?.let {
                EventDateDisplayFormatter.formatMskOffset(
                    at = now,
                    businessTimeZone = it,
                    mskPrefix = mskPrefix,
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

                if (isCustomInterval) {
                    Spacer(modifier = Modifier.height(AmberDimens.GapSmall))
                    Surface(
                        shape = RoundedCornerShape(AmberCorners.Small),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(Res.string.events_date_custom_interval),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(AmberDimens.GapSmall),
                        )
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

                    val todayStartMillis =
                        remember(today) {
                            today.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
                        }
                    val yesterdayStartMillis =
                        remember(yesterday) {
                            yesterday.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
                        }
                    val last7StartMillis =
                        remember(last7DaysStart) {
                            last7DaysStart.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
                        }

                    val currentStart = dateRangePickerState.selectedStartDateMillis
                    val currentEnd = dateRangePickerState.selectedEndDateMillis

                    val isTodaySelected =
                        currentStart == todayStartMillis &&
                            (currentEnd == null || currentEnd == todayStartMillis)
                    val isYesterdaySelected =
                        currentStart == yesterdayStartMillis &&
                            (currentEnd == null || currentEnd == yesterdayStartMillis)
                    val isLast7DaysSelected =
                        currentStart == last7StartMillis &&
                            currentEnd == todayStartMillis

                    FilterChip(
                        selected = isTodaySelected,
                        onClick = {
                            dateRangePickerState.setSelection(todayStartMillis, todayStartMillis)
                        },
                        enabled = timeZone != null,
                        label = { Text(stringResource(Res.string.events_filter_date_today)) },
                    )
                    FilterChip(
                        selected = isYesterdaySelected,
                        onClick = {
                            dateRangePickerState.setSelection(yesterdayStartMillis, yesterdayStartMillis)
                        },
                        enabled = timeZone != null,
                        label = { Text(stringResource(Res.string.events_filter_date_yesterday)) },
                    )
                    FilterChip(
                        selected = isLast7DaysSelected,
                        onClick = {
                            dateRangePickerState.setSelection(last7StartMillis, todayStartMillis)
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

                        val startMillis = dateRangePickerState.selectedStartDateMillis
                        val endMillis = dateRangePickerState.selectedEndDateMillis
                        val canApply = !isCustomInterval || startMillis != null || endMillis != null

                        TextButton(
                            onClick = {
                                val result =
                                    when {
                                        startMillis != null && endMillis != null -> {
                                            val startDate =
                                                Instant
                                                    .fromEpochMilliseconds(startMillis)
                                                    .toLocalDateTime(TimeZone.UTC)
                                                    .date
                                            val endDateInclusive =
                                                Instant
                                                    .fromEpochMilliseconds(endMillis)
                                                    .toLocalDateTime(TimeZone.UTC)
                                                    .date
                                            if (startDate == endDateInclusive) {
                                                DateSelection.Single(startDate)
                                            } else {
                                                DateSelection.Range(startDate, endDateInclusive)
                                            }
                                        }
                                        startMillis != null && endMillis == null -> {
                                            val startDate =
                                                Instant
                                                    .fromEpochMilliseconds(startMillis)
                                                    .toLocalDateTime(TimeZone.UTC)
                                                    .date
                                            if (initialSelection is DateSelection.From &&
                                                startMillis == initialStartMillis
                                            ) {
                                                DateSelection.From(startDate)
                                            } else {
                                                DateSelection.Single(startDate)
                                            }
                                        }
                                        startMillis == null && endMillis != null -> {
                                            val endDateInclusive =
                                                Instant
                                                    .fromEpochMilliseconds(endMillis)
                                                    .toLocalDateTime(TimeZone.UTC)
                                                    .date
                                            DateSelection.Until(endDateInclusive)
                                        }
                                        else -> DateSelection.All
                                    }
                                onApply(result)
                                onDismissRequest()
                            },
                            enabled = canApply,
                        ) {
                            Text(stringResource(Res.string.events_date_apply))
                        }
                    }
                }
            }
        }
    }
}
