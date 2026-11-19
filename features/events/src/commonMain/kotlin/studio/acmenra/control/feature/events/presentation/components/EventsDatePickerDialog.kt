package studio.acmenra.control.feature.events.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
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
import studio.acmenra.control.feature.events.domain.model.DateSelection
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EventsDatePickerDialog(
    initialSelection: DateSelection,
    onDismissRequest: () -> Unit,
    onApply: (DateSelection) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {
    var selectedPreset by remember { mutableStateOf<DateSelection?>(null) }
    val datePickerState = rememberDatePickerState()

    Dialog(onDismissRequest = onDismissRequest) {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(AmberCorners.ExtraLarge),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
        ) {
            Column(
                modifier = Modifier.padding(AmberDimens.CardPadding),
            ) {
                Text(
                    text = stringResource(Res.string.events_date_picker_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(modifier = Modifier.height(AmberDimens.GapMedium))

                // Quick presets
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(AmberDimens.GapSmall),
                    verticalArrangement = Arrangement.spacedBy(AmberDimens.GapSmall),
                ) {
                    FilterChip(
                        selected =
                            selectedPreset is DateSelection.Single &&
                                isToday((selectedPreset as DateSelection.Single).date, timeZone),
                        onClick = {
                            val today = currentLocalDate(timeZone)
                            selectedPreset = DateSelection.Single(today)
                        },
                        label = { Text(stringResource(Res.string.events_filter_date_today)) },
                    )
                    FilterChip(
                        selected =
                            selectedPreset is DateSelection.Single &&
                                isYesterday((selectedPreset as DateSelection.Single).date, timeZone),
                        onClick = {
                            val yesterday = currentLocalDate(timeZone).minusDays(1)
                            selectedPreset = DateSelection.Single(yesterday)
                        },
                        label = { Text(stringResource(Res.string.events_filter_date_yesterday)) },
                    )
                    FilterChip(
                        selected = selectedPreset is DateSelection.Range,
                        onClick = {
                            val today = currentLocalDate(timeZone)
                            val from = today.minusDays(6)
                            selectedPreset = DateSelection.Range(from, today)
                        },
                        label = { Text(stringResource(Res.string.events_filter_date_last_7_days)) },
                    )
                }

                Spacer(modifier = Modifier.height(AmberDimens.GapMedium))

                DatePicker(
                    state = datePickerState,
                    modifier = Modifier.fillMaxWidth(),
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
                                val result =
                                    selectedPreset ?: datePickerState.selectedDateMillis?.let { millis ->
                                        val localDate =
                                            Instant
                                                .fromEpochMilliseconds(
                                                    millis,
                                                ).toLocalDateTime(timeZone)
                                                .date
                                        DateSelection.Single(localDate)
                                    } ?: DateSelection.All
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

private fun currentLocalDate(timeZone: TimeZone): LocalDate =
    Clock.System
        .now()
        .toLocalDateTime(timeZone)
        .date

private fun isToday(
    date: LocalDate,
    timeZone: TimeZone,
): Boolean = date == currentLocalDate(timeZone)

private fun isYesterday(
    date: LocalDate,
    timeZone: TimeZone,
): Boolean = date == currentLocalDate(timeZone).minusDays(1)

private fun LocalDate.minusDays(days: Int): LocalDate {
    val epochDays = toEpochDays()
    return LocalDate.fromEpochDays(epochDays - days)
}
