package studio.acmenra.control.feature.events.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.events_status_acknowledged
import studio.acmenra.control.core.ui.generated.resources.events_status_erroneous
import studio.acmenra.control.core.ui.generated.resources.events_video_open
import studio.acmenra.control.core.ui.generated.resources.events_viewer_category
import studio.acmenra.control.core.ui.generated.resources.events_viewer_close
import studio.acmenra.control.core.ui.generated.resources.events_viewer_copy_id
import studio.acmenra.control.core.ui.generated.resources.events_viewer_details_title
import studio.acmenra.control.core.ui.generated.resources.events_viewer_device
import studio.acmenra.control.core.ui.generated.resources.events_viewer_id_label
import studio.acmenra.control.core.ui.generated.resources.events_viewer_location
import studio.acmenra.control.core.ui.generated.resources.events_viewer_mark_erroneous
import studio.acmenra.control.core.ui.generated.resources.events_viewer_mark_erroneous_cancel
import studio.acmenra.control.core.ui.generated.resources.events_viewer_mark_erroneous_confirm
import studio.acmenra.control.core.ui.generated.resources.events_viewer_mark_erroneous_title
import studio.acmenra.control.core.ui.generated.resources.events_viewer_mark_erroneous_warning
import studio.acmenra.control.core.ui.generated.resources.events_viewer_occurred_at
import studio.acmenra.control.core.ui.generated.resources.events_viewer_placeholder
import studio.acmenra.control.core.ui.generated.resources.ic_close
import studio.acmenra.control.core.ui.generated.resources.ic_content_copy
import studio.acmenra.control.core.ui.generated.resources.ic_download
import studio.acmenra.control.core.ui.generated.resources.ic_error
import studio.acmenra.control.core.ui.theme.AmberCorners
import studio.acmenra.control.core.ui.theme.AmberDimens
import studio.acmenra.control.feature.events.domain.model.Event
import studio.acmenra.control.feature.events.domain.model.EventAcknowledgementStatus
import studio.acmenra.control.feature.events.presentation.ErroneousConfirmationTarget

@Composable
fun EventViewer(
    event: Event?,
    canAcknowledge: Boolean,
    isMarkingErroneous: Boolean,
    isConfirmationOpen: Boolean,
    onCopyId: (String) -> Unit,
    onRequestMarkErroneous: (String) -> Unit,
    onConfirmMarkErroneous: (String, Long) -> Unit,
    onCancelMarkErroneous: () -> Unit,
    onCloseCompact: (() -> Unit)? = null,
    onOpenVideo: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    timeZone: TimeZone? = null,
    confirmationTarget: ErroneousConfirmationTarget? = null,
) {
    if (event == null) {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(AmberCorners.Large),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(AmberDimens.CardPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(Res.string.events_viewer_placeholder),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    val scrollState = rememberScrollState()

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AmberCorners.Large),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .padding(AmberDimens.CardPadding)
                    .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(AmberDimens.GapMedium),
        ) {
            // Header with close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = event.eventTypeName,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (onCloseCompact != null) {
                    IconButton(onClick = onCloseCompact) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_close),
                            contentDescription = stringResource(Res.string.events_viewer_close),
                            modifier = Modifier.size(AmberDimens.ListIcon),
                        )
                    }
                }
            }

            // Media Player
            EventMediaPlayer(
                videoUrl = event.videoUrl,
                previewUrl = event.previewUrl,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(AmberDimens.RailExpanded),
            )

            // Video Download Button (if video available)
            if (!event.videoUrl.isNullOrBlank()) {
                OutlinedButton(
                    onClick = { onOpenVideo?.invoke(event.videoUrl) },
                    modifier = Modifier.fillMaxWidth().height(AmberDimens.ButtonHeight),
                    shape = RoundedCornerShape(AmberCorners.Medium),
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_download),
                        contentDescription = null,
                        modifier = Modifier.size(AmberDimens.ListIcon),
                    )
                    Spacer(modifier = Modifier.width(AmberDimens.GapSmall))
                    Text(text = stringResource(Res.string.events_video_open))
                }
            }

            HorizontalDivider()

            // Details section
            Text(
                text = stringResource(Res.string.events_viewer_details_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // ID with copy button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = stringResource(Res.string.events_viewer_id_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = event.id,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                IconButton(onClick = { onCopyId(event.id) }) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_content_copy),
                        contentDescription = stringResource(Res.string.events_viewer_copy_id),
                        modifier = Modifier.size(AmberDimens.ListIcon),
                    )
                }
            }

            // Occurred at
            val occurredStr =
                if (timeZone != null) {
                    val localOccurred = event.occurredAt.toLocalDateTime(timeZone)
                    val monthNum = (localOccurred.month.ordinal + 1).toString().padStart(2, '0')
                    "${localOccurred.day.toString().padStart(2, '0')}.$monthNum.${localOccurred.year} ${
                        localOccurred.hour.toString().padStart(2, '0')
                    }:${localOccurred.minute.toString().padStart(2, '0')}:${
                        localOccurred.second.toString().padStart(2, '0')
                    }"
                } else {
                    "--.--.---- --:--:--"
                }
            DetailRow(
                label = stringResource(Res.string.events_viewer_occurred_at),
                value = occurredStr,
            )

            // Location
            DetailRow(
                label = stringResource(Res.string.events_viewer_location),
                value = event.locationName,
            )

            // Device
            DetailRow(
                label = stringResource(Res.string.events_viewer_device),
                value = event.deviceName,
            )

            // Category
            DetailRow(
                label = stringResource(Res.string.events_viewer_category),
                value = event.categoryCode,
            )

            // Status
            if (event.isErroneous) {
                DetailRow(
                    label = stringResource(Res.string.events_status_erroneous),
                    value = stringResource(Res.string.events_status_erroneous),
                    isError = true,
                )
            } else if (event.status == EventAcknowledgementStatus.ACKNOWLEDGED) {
                DetailRow(
                    label = stringResource(Res.string.events_status_acknowledged),
                    value = event.acknowledgedBy ?: stringResource(Res.string.events_status_acknowledged),
                )
            }

            // Mark Erroneous Action
            if (canAcknowledge && !event.isErroneous) {
                Spacer(modifier = Modifier.height(AmberDimens.GapSmall))
                Button(
                    onClick = { onRequestMarkErroneous(event.id) },
                    modifier = Modifier.fillMaxWidth().height(AmberDimens.ButtonHeight),
                    shape = RoundedCornerShape(AmberCorners.Medium),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    enabled = !isMarkingErroneous,
                ) {
                    if (isMarkingErroneous) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(AmberDimens.ListIcon),
                            color = MaterialTheme.colorScheme.onError,
                        )
                    } else {
                        Icon(
                            painter = painterResource(Res.drawable.ic_error),
                            contentDescription = null,
                            modifier = Modifier.size(AmberDimens.ListIcon),
                        )
                        Spacer(modifier = Modifier.width(AmberDimens.GapSmall))
                        Text(text = stringResource(Res.string.events_viewer_mark_erroneous))
                    }
                }
            }
        }
    }

    // Mark Erroneous Confirmation Dialog
    if (isConfirmationOpen) {
        val targetId = confirmationTarget?.eventId ?: event.id
        val targetVersion = confirmationTarget?.expectedClassificationVersion ?: event.classificationVersion
        AlertDialog(
            onDismissRequest = onCancelMarkErroneous,
            shape = RoundedCornerShape(AmberCorners.ExtraLarge),
            title = {
                Text(
                    text = stringResource(Res.string.events_viewer_mark_erroneous_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            },
            text = {
                Text(
                    text = stringResource(Res.string.events_viewer_mark_erroneous_warning),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onConfirmMarkErroneous(targetId, targetVersion)
                    },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                ) {
                    Text(text = stringResource(Res.string.events_viewer_mark_erroneous_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelMarkErroneous) {
                    Text(text = stringResource(Res.string.events_viewer_mark_erroneous_cancel))
                }
            },
        )
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}
