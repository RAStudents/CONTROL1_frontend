package studio.acmenra.control.feature.events.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.events_status_acknowledged
import studio.acmenra.control.core.ui.generated.resources.events_status_erroneous
import studio.acmenra.control.core.ui.generated.resources.ic_check_circle
import studio.acmenra.control.core.ui.generated.resources.ic_event_alert
import studio.acmenra.control.core.ui.generated.resources.ic_event_defect
import studio.acmenra.control.core.ui.generated.resources.ic_event_helmet
import studio.acmenra.control.core.ui.generated.resources.ic_event_person
import studio.acmenra.control.core.ui.generated.resources.ic_event_phone
import studio.acmenra.control.core.ui.generated.resources.ic_event_sleep
import studio.acmenra.control.core.ui.theme.AmberCorners
import studio.acmenra.control.core.ui.theme.AmberDimens
import studio.acmenra.control.feature.events.domain.model.Event
import studio.acmenra.control.feature.events.domain.model.EventAcknowledgementStatus
import studio.acmenra.control.feature.events.domain.model.EventIconKey

@Composable
fun EventCard(
    event: Event,
    isSelected: Boolean,
    isPendingAck: Boolean,
    canAcknowledge: Boolean,
    onClick: () -> Unit,
    onAcknowledge: () -> Unit,
    modifier: Modifier = Modifier,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {
    val containerColor =
        if (isSelected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else if (event.isErroneous) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        }

    val border =
        if (isSelected) {
            BorderStroke(AmberDimens.ListGap, MaterialTheme.colorScheme.primary)
        } else {
            null
        }

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        shape = RoundedCornerShape(AmberCorners.Large),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = border,
    ) {
        Row(
            modifier = Modifier.padding(AmberDimens.CardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Icon
            val iconRes =
                when (event.iconKey) {
                    EventIconKey.HELMET -> Res.drawable.ic_event_helmet
                    EventIconKey.PHONE -> Res.drawable.ic_event_phone
                    EventIconKey.SLEEP -> Res.drawable.ic_event_sleep
                    EventIconKey.DEFECT -> Res.drawable.ic_event_defect
                    EventIconKey.PERSON -> Res.drawable.ic_event_person
                    EventIconKey.GENERIC_ALERT -> Res.drawable.ic_event_alert
                }

            Box(
                modifier =
                    Modifier
                        .size(AmberDimens.ListIconCircle)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(AmberDimens.ListIcon),
                    tint =
                        if (event.isErroneous) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                )
            }

            Spacer(modifier = Modifier.width(AmberDimens.GapMedium))

            // Body
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = event.eventTypeName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    // Time
                    val local = event.occurredAt.toLocalDateTime(timeZone)
                    val timeStr =
                        "${local.hour.toString().padStart(2, '0')}:${
                            local.minute.toString().padStart(2, '0')
                        }:${local.second.toString().padStart(2, '0')}"
                    Text(
                        text = timeStr,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.height(AmberDimens.ListGap))

                // Location • Camera
                Text(
                    text = "${event.locationName} • ${event.deviceName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(AmberDimens.GapSmall))

                // Status Badges
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AmberDimens.GapSmall),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (event.isErroneous) {
                        Text(
                            text = stringResource(Res.string.events_status_erroneous),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (event.status == EventAcknowledgementStatus.ACKNOWLEDGED) {
                        Text(
                            text = stringResource(Res.string.events_status_acknowledged),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    if (!event.isVisuallyRead(setOf(event.id).takeIf { isPendingAck } ?: emptySet())) {
                        Box(
                            modifier =
                                Modifier
                                    .size(AmberDimens.GapSmall)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            }

            // Acknowledge Action Button
            val canAckThisEvent =
                canAcknowledge &&
                    (
                        event.status == EventAcknowledgementStatus.NEW ||
                            event.status == EventAcknowledgementStatus.LEGACY_UNKNOWN
                    )
            if (canAckThisEvent) {
                Spacer(modifier = Modifier.width(AmberDimens.GapSmall))
                if (isPendingAck) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(AmberDimens.ListIcon),
                        strokeWidth = AmberDimens.ListGap,
                    )
                } else {
                    IconButton(
                        onClick = onAcknowledge,
                        modifier = Modifier.size(AmberDimens.ListIconCircle),
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_check_circle),
                            contentDescription = stringResource(Res.string.events_status_acknowledged),
                            modifier = Modifier.size(AmberDimens.ListIcon),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}
