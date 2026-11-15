package studio.acmenra.control.navigation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import studio.acmenra.control.core.access.domain.model.SupportGrantInfo
import studio.acmenra.control.core.access.domain.model.SupportMode
import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.ic_timer
import studio.acmenra.control.core.ui.generated.resources.support_banner_title
import studio.acmenra.control.core.ui.generated.resources.support_grant_expired
import studio.acmenra.control.core.ui.generated.resources.support_mode_engineering
import studio.acmenra.control.core.ui.generated.resources.support_mode_readonly
import studio.acmenra.control.core.ui.generated.resources.support_mode_unknown
import studio.acmenra.control.core.ui.generated.resources.support_remaining_time
import studio.acmenra.control.core.ui.generated.resources.support_ticket_label
import studio.acmenra.control.core.ui.theme.AmberCorners
import studio.acmenra.control.core.ui.theme.AmberDimens
import kotlin.time.Duration

@Composable
fun SupportOverrideBanner(
    grant: SupportGrantInfo,
    modifier: Modifier = Modifier,
) {
    var remaining by remember(grant) { mutableStateOf(grant.remainingDurationNow()) }

    LaunchedEffect(grant) {
        while (isActive) {
            val current = grant.remainingDurationNow()
            remaining = current
            if (current <= Duration.ZERO) break
            delay(1000)
        }
    }

    val modeText =
        when (grant.mode) {
            SupportMode.READ_ONLY_SUPPORT -> stringResource(Res.string.support_mode_readonly)
            SupportMode.ENGINEERING_SUPPORT -> stringResource(Res.string.support_mode_engineering)
            SupportMode.UNKNOWN -> stringResource(Res.string.support_mode_unknown)
        }

    val ticketText =
        grant.ticketReference?.let { ticket ->
            stringResource(Res.string.support_ticket_label, ticket)
        }

    val timeFormatted = formatRemainingDuration(remaining)

    val timeText =
        if (remaining > Duration.ZERO) {
            stringResource(Res.string.support_remaining_time, timeFormatted)
        } else {
            stringResource(Res.string.support_grant_expired)
        }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = AmberDimens.ScreenMargin, vertical = AmberDimens.GapSmall)
                .background(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(AmberCorners.Medium),
                ).padding(horizontal = AmberDimens.ScreenMargin, vertical = AmberDimens.GapSmall)
                .defaultMinSize(minHeight = AmberDimens.SupportBannerMinHeight),
        contentAlignment = Alignment.CenterStart,
    ) {
        val detailsText =
            if (ticketText != null) {
                "$modeText • $ticketText"
            } else {
                modeText
            }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AmberDimens.GapSmall),
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(end = AmberDimens.GapSmall),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_timer),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(AmberDimens.ListIcon),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.support_banner_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = detailsText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Box(
                modifier =
                    Modifier
                        .defaultMinSize(minWidth = AmberDimens.SupportBannerCountdownMinWidth)
                        .background(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(AmberCorners.Small),
                        ).padding(
                            horizontal = AmberDimens.StatusBadgePaddingHorizontal,
                            vertical = AmberDimens.StatusBadgePaddingVertical,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

internal fun formatRemainingDuration(remaining: Duration): String {
    val totalSec = remaining.inWholeSeconds.coerceAtLeast(0)
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return if (hours > 0) {
        "${hours.padZero()}:${minutes.padZero()}:${seconds.padZero()}"
    } else {
        "${minutes.padZero()}:${seconds.padZero()}"
    }
}

internal fun Long.padZero(): String = if (this < 10) "0$this" else "$this"
