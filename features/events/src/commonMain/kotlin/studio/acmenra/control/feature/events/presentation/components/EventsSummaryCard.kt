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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.events_summary_devices_error
import studio.acmenra.control.core.ui.generated.resources.events_summary_devices_ok
import studio.acmenra.control.core.ui.generated.resources.events_summary_devices_title
import studio.acmenra.control.core.ui.generated.resources.events_summary_devices_total
import studio.acmenra.control.core.ui.generated.resources.events_summary_devices_unknown
import studio.acmenra.control.core.ui.generated.resources.events_summary_empty
import studio.acmenra.control.core.ui.generated.resources.events_summary_today_label
import studio.acmenra.control.core.ui.generated.resources.events_trend_improving
import studio.acmenra.control.core.ui.generated.resources.events_trend_stable
import studio.acmenra.control.core.ui.generated.resources.events_trend_unknown
import studio.acmenra.control.core.ui.generated.resources.events_trend_worsening
import studio.acmenra.control.core.ui.generated.resources.ic_trending_down
import studio.acmenra.control.core.ui.generated.resources.ic_trending_flat
import studio.acmenra.control.core.ui.generated.resources.ic_trending_up
import studio.acmenra.control.core.ui.theme.AmberCorners
import studio.acmenra.control.core.ui.theme.AmberDimens
import studio.acmenra.control.feature.events.domain.model.EventsSummary
import studio.acmenra.control.feature.events.domain.model.ScoreTrend

@Composable
fun EventsSummaryCard(
    summary: EventsSummary?,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AmberCorners.Large),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
    ) {
        Column(
            modifier = Modifier.padding(AmberDimens.CardPadding),
        ) {
            if (summary == null) {
                Text(
                    text = stringResource(Res.string.events_summary_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            // Today's count
            Text(
                text = stringResource(Res.string.events_summary_today_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(AmberDimens.GapSmall))
            Text(
                text = summary.todayEventsCount.toString(),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.height(AmberDimens.GapMedium))

            // Devices status row
            val devices = summary.devices
            Text(
                text = stringResource(Res.string.events_summary_devices_title),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(AmberDimens.GapSmall))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                DeviceMetricItem(
                    label = stringResource(Res.string.events_summary_devices_total),
                    count = devices.totalCount,
                )
                DeviceMetricItem(
                    label = stringResource(Res.string.events_summary_devices_ok),
                    count = devices.onlineOkCount,
                )
                DeviceMetricItem(
                    label = stringResource(Res.string.events_summary_devices_unknown),
                    count = devices.unknownCount,
                )
                DeviceMetricItem(
                    label = stringResource(Res.string.events_summary_devices_error),
                    count = devices.errorCount,
                )
            }

            // Category scores
            if (summary.categories.isNotEmpty()) {
                Spacer(modifier = Modifier.height(AmberDimens.GapMedium))
                Column(verticalArrangement = Arrangement.spacedBy(AmberDimens.GapSmall)) {
                    summary.categories.forEach { cat ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = cat.name,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "${cat.score}%",
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Spacer(modifier = Modifier.width(AmberDimens.GapSmall))
                                val (icon, desc) =
                                    when (cat.trend) {
                                        ScoreTrend.IMPROVING ->
                                            Res.drawable.ic_trending_up to
                                                Res.string.events_trend_improving
                                        ScoreTrend.WORSENING ->
                                            Res.drawable.ic_trending_down to
                                                Res.string.events_trend_worsening
                                        ScoreTrend.STABLE ->
                                            Res.drawable.ic_trending_flat to
                                                Res.string.events_trend_stable
                                        ScoreTrend.UNKNOWN ->
                                            Res.drawable.ic_trending_flat to
                                                Res.string.events_trend_unknown
                                    }
                                Icon(
                                    painter = painterResource(icon),
                                    contentDescription = stringResource(desc),
                                    modifier = Modifier.size(AmberDimens.ListIcon),
                                    tint =
                                        when (cat.trend) {
                                            ScoreTrend.IMPROVING -> MaterialTheme.colorScheme.primary
                                            ScoreTrend.WORSENING -> MaterialTheme.colorScheme.error
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceMetricItem(
    label: String,
    count: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
