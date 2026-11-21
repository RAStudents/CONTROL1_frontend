package studio.acmenra.control.feature.events.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.events_summary_devices_error
import studio.acmenra.control.core.ui.generated.resources.events_summary_devices_ok
import studio.acmenra.control.core.ui.generated.resources.events_summary_devices_title
import studio.acmenra.control.core.ui.generated.resources.events_summary_devices_total
import studio.acmenra.control.core.ui.generated.resources.events_summary_devices_total_format
import studio.acmenra.control.core.ui.generated.resources.events_summary_devices_unknown
import studio.acmenra.control.core.ui.generated.resources.events_summary_empty
import studio.acmenra.control.core.ui.generated.resources.events_summary_title
import studio.acmenra.control.core.ui.generated.resources.events_summary_today_format
import studio.acmenra.control.core.ui.generated.resources.events_summary_today_label
import studio.acmenra.control.core.ui.generated.resources.events_trend_improving
import studio.acmenra.control.core.ui.generated.resources.events_trend_stable
import studio.acmenra.control.core.ui.generated.resources.events_trend_unknown
import studio.acmenra.control.core.ui.generated.resources.events_trend_worsening
import studio.acmenra.control.core.ui.generated.resources.ic_check_circle
import studio.acmenra.control.core.ui.generated.resources.ic_info
import studio.acmenra.control.core.ui.generated.resources.ic_question_mark
import studio.acmenra.control.core.ui.generated.resources.ic_trending_down
import studio.acmenra.control.core.ui.generated.resources.ic_trending_flat
import studio.acmenra.control.core.ui.generated.resources.ic_trending_up
import studio.acmenra.control.core.ui.theme.AmberDimens
import studio.acmenra.control.feature.events.domain.model.CategoryScore
import studio.acmenra.control.feature.events.domain.model.EventsSummary
import studio.acmenra.control.feature.events.domain.model.ScoreTrend

@Composable
fun EventsSummaryCard(
    summary: EventsSummary?,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    isCompact: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(AmberDimens.GapSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left side (50%): Events Card with Centered Header + Top 2 categories
        // Inner card background removed (transparent) so outer slot background shows through
        Surface(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            color = Color.Transparent,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(AmberDimens.EventsDevicesCardPadding),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Centered Header
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(Res.string.events_summary_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val todayText =
                        summary?.todayEventsCount?.let { count ->
                            stringResource(Res.string.events_summary_today_format, count)
                        } ?: stringResource(Res.string.events_summary_today_label)
                    Text(
                        text = todayText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Categories (centered statistics)
                val categories = summary?.categories?.take(2) ?: emptyList()
                if (categories.isNotEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(AmberDimens.EventsGapTiny),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        categories.forEach { cat ->
                            CategoryScoreRow(category = cat)
                        }
                    }
                } else if (isLoading) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(AmberDimens.EventsGapTiny),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        SkeletonBar(
                            width = AmberDimens.EventsSkeletonWidthWide,
                            height = AmberDimens.EventsSkeletonHeight,
                        )
                        SkeletonBar(
                            width = AmberDimens.EventsSkeletonWidthNarrow,
                            height = AmberDimens.EventsSkeletonHeight,
                        )
                    }
                } else {
                    Text(
                        text = stringResource(Res.string.events_summary_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Right side (50%): Devices Card with Centered Header + Metrics
        Surface(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(AmberDimens.EventsDevicesCardPadding),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Centered Header
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(Res.string.events_summary_devices_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val totalCount = summary?.devices?.totalCount
                    val totalText =
                        if (totalCount != null) {
                            stringResource(Res.string.events_summary_devices_total_format, totalCount)
                        } else {
                            stringResource(Res.string.events_summary_devices_total)
                        }
                    Text(
                        text = totalText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Device Metrics: OK, Unknown, Error
                val devices = summary?.devices
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(AmberDimens.EventsGapTiny),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    DeviceMetricItem(
                        icon = Res.drawable.ic_check_circle,
                        tint = MaterialTheme.colorScheme.primary,
                        tooltipText = stringResource(Res.string.events_summary_devices_ok),
                        countText =
                            when {
                                devices == null -> "—"
                                isCompact -> devices.onlineOkCount.toString()
                                else -> "${devices.onlineOkCount}/${devices.totalCount}"
                            },
                    )
                    DeviceMetricItem(
                        icon = Res.drawable.ic_question_mark,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        tooltipText = stringResource(Res.string.events_summary_devices_unknown),
                        countText =
                            when {
                                devices == null -> "—"
                                isCompact -> devices.unknownCount.toString()
                                else -> "${devices.unknownCount}/${devices.totalCount}"
                            },
                    )
                    DeviceMetricItem(
                        icon = Res.drawable.ic_info,
                        tint = MaterialTheme.colorScheme.error,
                        tooltipText = stringResource(Res.string.events_summary_devices_error),
                        countText =
                            when {
                                devices == null -> "—"
                                isCompact -> devices.errorCount.toString()
                                else -> "${devices.errorCount}/${devices.totalCount}"
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryScoreRow(
    category: CategoryScore,
    modifier: Modifier = Modifier,
) {
    val (trendIcon, trendTint, trendDesc) =
        when (category.trend) {
            ScoreTrend.IMPROVING ->
                Triple(
                    Res.drawable.ic_trending_up,
                    MaterialTheme.colorScheme.tertiary,
                    stringResource(Res.string.events_trend_improving),
                )
            ScoreTrend.WORSENING ->
                Triple(
                    Res.drawable.ic_trending_down,
                    MaterialTheme.colorScheme.error,
                    stringResource(Res.string.events_trend_worsening),
                )
            ScoreTrend.STABLE ->
                Triple(
                    Res.drawable.ic_trending_flat,
                    MaterialTheme.colorScheme.outline,
                    stringResource(Res.string.events_trend_stable),
                )
            ScoreTrend.UNKNOWN ->
                Triple(
                    Res.drawable.ic_trending_flat,
                    MaterialTheme.colorScheme.outline,
                    stringResource(Res.string.events_trend_unknown),
                )
        }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    contentDescription = "${category.name}: ${category.score}%, $trendDesc"
                },
        horizontalArrangement = Arrangement.spacedBy(AmberDimens.EventsGapTiny, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = category.name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            text = "${category.score}%",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Icon(
            painter = painterResource(trendIcon),
            contentDescription = trendDesc,
            tint = trendTint,
            modifier = Modifier.size(AmberDimens.EventsSmallIcon),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceMetricItem(
    icon: DrawableResource,
    tint: Color,
    tooltipText: String,
    countText: String,
    modifier: Modifier = Modifier,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip {
                Text(tooltipText)
            }
        },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        Row(
            modifier =
                Modifier
                    .semantics(mergeDescendants = true) {
                        contentDescription = "$tooltipText: $countText"
                    },
            horizontalArrangement = Arrangement.spacedBy(AmberDimens.EventsGapTiny),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(AmberDimens.EventsSmallIcon),
            )
            Text(
                text = countText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SkeletonBar(
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .width(width)
                .height(height)
                .clip(RoundedCornerShape(AmberDimens.EventsGapTiny))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)),
    )
}
