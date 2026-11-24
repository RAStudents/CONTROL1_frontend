package studio.acmenra.control.feature.events.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.coerceAtLeast
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.events_action_acknowledge
import studio.acmenra.control.core.ui.generated.resources.ic_check_circle
import studio.acmenra.control.core.ui.generated.resources.ic_chevron_right
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
import studio.acmenra.control.feature.events.presentation.util.EventDateDisplayFormatter

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EventCard(
    event: Event,
    isVisuallySelected: Boolean,
    isPendingAck: Boolean,
    businessTimeZone: TimeZone?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    canAcknowledge: Boolean = false,
    onAcknowledge: (() -> Unit)? = null,
    hasGluedAbove: Boolean = false,
    hasGluedBelow: Boolean = false,
    shape: Shape? = null,
) {
    val motion = MaterialTheme.motionScheme
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    // 1. Color Priority (§1.3):
    // Higher priority: isErroneous == true -> primaryContainer, icon circle primary, icon onPrimary
    // Second: !isErroneous && !event.isVisuallyRead(pendingAckIds) -> secondaryContainer, circle surface, icon onSurface
    // Third: !isErroneous && event.isVisuallyRead(pendingAckIds) -> surfaceContainer, circle secondaryContainer, icon onSecondaryContainer
    val isRead = event.isVisuallyRead(if (isPendingAck) setOf(event.id) else emptySet())
    val targetContainerColor: Color
    val targetCircleColor: Color
    val targetIconTint: Color
    val targetContentColor: Color
    val targetSupportingColor: Color

    when {
        event.isErroneous -> {
            targetContainerColor = MaterialTheme.colorScheme.primaryContainer
            targetCircleColor = MaterialTheme.colorScheme.primary
            targetIconTint = MaterialTheme.colorScheme.onPrimary
            targetContentColor = MaterialTheme.colorScheme.onPrimaryContainer
            targetSupportingColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
        }
        !isRead -> {
            targetContainerColor = MaterialTheme.colorScheme.secondaryContainer
            targetCircleColor = MaterialTheme.colorScheme.surface
            targetIconTint = MaterialTheme.colorScheme.onSurface
            targetContentColor = MaterialTheme.colorScheme.onSecondaryContainer
            targetSupportingColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
        }
        else -> {
            targetContainerColor = MaterialTheme.colorScheme.surfaceContainer
            targetCircleColor = MaterialTheme.colorScheme.secondaryContainer
            targetIconTint = MaterialTheme.colorScheme.onSecondaryContainer
            targetContentColor = MaterialTheme.colorScheme.onSurface
            targetSupportingColor = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }

    val animatedContainerColor by animateColorAsState(
        targetValue = targetContainerColor,
        animationSpec = motion.fastEffectsSpec(),
        label = "event-card-container-color",
    )
    val animatedCircleColor by animateColorAsState(
        targetValue = targetCircleColor,
        animationSpec = motion.fastEffectsSpec(),
        label = "event-card-circle-color",
    )
    val animatedIconTint by animateColorAsState(
        targetValue = targetIconTint,
        animationSpec = motion.fastEffectsSpec(),
        label = "event-card-icon-tint",
    )
    val animatedContentColor by animateColorAsState(
        targetValue = targetContentColor,
        animationSpec = motion.fastEffectsSpec(),
        label = "event-card-content-color",
    )
    val animatedSupportingColor by animateColorAsState(
        targetValue = targetSupportingColor,
        animationSpec = motion.fastEffectsSpec(),
        label = "event-card-supporting-color",
    )

    // 2. Connected geometry inside the day (§1.4, §6.1)
    val cardShape: Shape =
        if (shape != null) {
            shape
        } else {
            val targetTopCorner =
                if (hasGluedAbove) AmberCorners.Small else AmberCorners.ExtraLarge
            val targetBottomCorner =
                if (hasGluedBelow) AmberCorners.Small else AmberCorners.ExtraLarge

            val animatedTopCorner by animateDpAsState(
                targetValue = targetTopCorner,
                animationSpec = motion.fastSpatialSpec(),
                label = "event-card-corner-top",
            )
            val animatedBottomCorner by animateDpAsState(
                targetValue = targetBottomCorner,
                animationSpec = motion.fastSpatialSpec(),
                label = "event-card-corner-bottom",
            )

            val safeTop = animatedTopCorner.coerceAtLeast(AmberCorners.None)
            val safeBottom = animatedBottomCorner.coerceAtLeast(AmberCorners.None)

            RoundedCornerShape(
                topStart = safeTop,
                topEnd = safeTop,
                bottomStart = safeBottom,
                bottomEnd = safeBottom,
            )
        }

    // 3. Selection border: 1.5.dp MaterialTheme.colorScheme.primary (§1.4)
    val border: BorderStroke? =
        if (isVisuallySelected) {
            BorderStroke(AmberDimens.EventsSelectedBorderWidth, MaterialTheme.colorScheme.primary)
        } else {
            null
        }

    // 4. Chevron animations (§1.4): scale when selected, shift when hovered
    val chevronScale by animateFloatAsState(
        targetValue = if (isVisuallySelected) 1.25f else 1.0f,
        animationSpec = motion.fastSpatialSpec(),
        label = "event-card-chevron-scale",
    )
    val chevronShift by animateDpAsState(
        targetValue = if (isHovered) AmberDimens.EventsHoverChevronShift else AmberCorners.None,
        animationSpec = motion.fastSpatialSpec(),
        label = "event-card-chevron-shift",
    )

    // Event type icon with fallback to GENERIC_ALERT (§11)
    val iconRes =
        when (event.iconKey) {
            EventIconKey.HELMET -> Res.drawable.ic_event_helmet
            EventIconKey.PHONE -> Res.drawable.ic_event_phone
            EventIconKey.SLEEP -> Res.drawable.ic_event_sleep
            EventIconKey.DEFECT -> Res.drawable.ic_event_defect
            EventIconKey.PERSON -> Res.drawable.ic_event_person
            EventIconKey.GENERIC_ALERT -> Res.drawable.ic_event_alert
        }

    // Supporting text format (§1.2): "HH:mm • locationName, deviceName"
    val effectiveTz = EventDateDisplayFormatter.resolveEffectiveTimeZone(businessTimeZone)
    val supportingText =
        EventDateDisplayFormatter.formatEventCardSupportingText(
            occurredAt = event.occurredAt,
            businessTimeZone = effectiveTz,
            locationName = event.locationName,
            deviceName = event.deviceName,
        )

    val hoverOverlayColor by animateColorAsState(
        targetValue =
            if (isHovered) {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
            } else {
                Color.Transparent
            },
        animationSpec = motion.fastEffectsSpec(),
        label = "event-card-hover-overlay",
    )

    val cardSemanticDescription =
        buildString {
            if (event.isErroneous) {
                append("Erroneous event. ")
            }
            if (event.status == EventAcknowledgementStatus.ACKNOWLEDGED) {
                append("Acknowledged. ")
            }
        }

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics {
                    if (cardSemanticDescription.isNotEmpty()) {
                        stateDescription = cardSemanticDescription.trim()
                    }
                }.clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = animatedContainerColor),
        border = border,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(hoverOverlayColor),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = AmberDimens.ListItemHeight)
                        .padding(horizontal = AmberDimens.GapMedium, vertical = AmberDimens.GapSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left: Icon Circle
                Box(
                    modifier =
                        Modifier
                            .size(AmberDimens.ListIconCircle)
                            .clip(CircleShape)
                            .background(animatedCircleColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        modifier = Modifier.size(AmberDimens.ListIcon),
                        tint = animatedIconTint,
                    )
                }

                Spacer(modifier = Modifier.width(AmberDimens.GapMedium))

                // Middle: Two text lines (§1.2: both maxLines = 1, Ellipsis)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = event.eventTypeName,
                        style = MaterialTheme.typography.titleMedium,
                        color = animatedContentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    if (supportingText.isNotEmpty()) {
                        Text(
                            text = supportingText,
                            style = MaterialTheme.typography.bodySmall,
                            color = animatedSupportingColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(modifier = Modifier.width(AmberDimens.GapSmall))

                // Acknowledge Action Button
                val canAckThisEvent =
                    canAcknowledge &&
                        !event.isErroneous &&
                        event.status != EventAcknowledgementStatus.ACKNOWLEDGED
                if (canAckThisEvent) {
                    if (isPendingAck) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(AmberDimens.ListIcon),
                            strokeWidth = AmberDimens.ListGap,
                        )
                    } else if (onAcknowledge != null) {
                        IconButton(
                            onClick = onAcknowledge,
                            modifier = Modifier.size(AmberDimens.ListIconCircle),
                        ) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_check_circle),
                                contentDescription = stringResource(Res.string.events_action_acknowledge),
                                modifier = Modifier.size(AmberDimens.ListIcon),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(AmberDimens.GapSmall))
                }

                // Right: Chevron icon that scales when selected and shifts on hover
                Icon(
                    painter = painterResource(Res.drawable.ic_chevron_right),
                    contentDescription = null,
                    modifier =
                        Modifier
                            .size(AmberDimens.ListIcon)
                            .graphicsLayer {
                                scaleX = chevronScale
                                scaleY = chevronScale
                                translationX = chevronShift.toPx()
                            },
                    tint = animatedContentColor,
                )
            }
        }
    }
}
