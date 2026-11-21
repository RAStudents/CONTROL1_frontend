package studio.acmenra.control.feature.events.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.events_export_downloading
import studio.acmenra.control.core.ui.generated.resources.events_export_format_excel_short
import studio.acmenra.control.core.ui.generated.resources.events_export_format_standard_short
import studio.acmenra.control.core.ui.generated.resources.events_export_media_links_chip
import studio.acmenra.control.core.ui.generated.resources.events_export_panel_title
import studio.acmenra.control.core.ui.generated.resources.events_export_redownload_tooltip
import studio.acmenra.control.core.ui.generated.resources.events_export_rows_count
import studio.acmenra.control.core.ui.generated.resources.events_export_rows_count_server
import studio.acmenra.control.core.ui.generated.resources.events_export_start_btn
import studio.acmenra.control.core.ui.generated.resources.events_export_status_processing
import studio.acmenra.control.core.ui.generated.resources.events_export_status_queued
import studio.acmenra.control.core.ui.generated.resources.ic_download
import studio.acmenra.control.core.ui.theme.AmberDimens
import studio.acmenra.control.feature.events.domain.model.EventsExportFormatPreset
import studio.acmenra.control.feature.events.domain.model.EventsExportStatus
import studio.acmenra.control.feature.events.domain.model.EventsExportStatusCode
import kotlin.time.Clock

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsExportPanel(
    formatPreset: EventsExportFormatPreset,
    includeMediaLinks: Boolean,
    totalRecords: Int?,
    jobStatus: EventsExportStatus?,
    isSubmitting: Boolean,
    onFormatPresetChanged: (EventsExportFormatPreset) -> Unit,
    onIncludeMediaLinksChanged: (Boolean) -> Unit,
    onStartExport: () -> Unit,
    onDownload: (String) -> Unit,
    modifier: Modifier = Modifier,
    isDownloading: Boolean = false,
    canExport: Boolean = true,
) {
    val isQueuedOrProcessing =
        jobStatus?.status == EventsExportStatusCode.QUEUED ||
            jobStatus?.status == EventsExportStatusCode.PROCESSING

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // Line 1: Header + Count
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.events_export_panel_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val countText =
                when {
                    jobStatus?.totalRecords != null ->
                        stringResource(Res.string.events_export_rows_count, jobStatus.totalRecords)
                    totalRecords != null ->
                        stringResource(Res.string.events_export_rows_count, totalRecords)
                    else ->
                        stringResource(Res.string.events_export_rows_count_server)
                }
            Text(
                text = countText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Line 2: Export settings (stretched to full width)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AmberDimens.GapSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = formatPreset == EventsExportFormatPreset.EXCEL_SEMICOLON_BOM,
                onClick = { onFormatPresetChanged(EventsExportFormatPreset.EXCEL_SEMICOLON_BOM) },
                label = {
                    Text(
                        text = stringResource(Res.string.events_export_format_excel_short),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = formatPreset == EventsExportFormatPreset.STANDARD_COMMA_UTF8,
                onClick = { onFormatPresetChanged(EventsExportFormatPreset.STANDARD_COMMA_UTF8) },
                label = {
                    Text(
                        text = stringResource(Res.string.events_export_format_standard_short),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = includeMediaLinks,
                onClick = { onIncludeMediaLinksChanged(!includeMediaLinks) },
                label = {
                    Text(
                        text = stringResource(Res.string.events_export_media_links_chip),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                modifier = Modifier.weight(1f),
            )
        }

        // Line 3: Main Button (full width, or with redownload button if completed)
        var lastClickTime by remember { mutableStateOf(0L) }
        val canRedownload = jobStatus?.isDownloadable == true
        val isWorking = isSubmitting || isQueuedOrProcessing || isDownloading

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(AmberDimens.EventsCompactButtonHeight),
            horizontalArrangement = Arrangement.spacedBy(AmberDimens.GapSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    val clickNow = Clock.System.now().toEpochMilliseconds()
                    if (clickNow - lastClickTime >= 500L) {
                        lastClickTime = clickNow
                        onStartExport()
                    }
                },
                enabled = canExport && !isWorking,
                modifier =
                    (if (canRedownload) Modifier.weight(1f) else Modifier.fillMaxWidth())
                        .height(AmberDimens.EventsCompactButtonHeight),
            ) {
                if (isWorking) {
                    val processed = jobStatus?.processedRecords
                    val total = jobStatus?.totalRecords
                    if (processed != null && total != null && total > 0) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(AmberDimens.GapSmall),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                progress = { (processed.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                                modifier = Modifier.size(AmberDimens.EventsSmallIcon),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Text(
                                text = "$processed / $total",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(AmberDimens.GapSmall),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(AmberDimens.EventsSmallIcon),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            val statusLabel =
                                when {
                                    isDownloading -> stringResource(Res.string.events_export_downloading)
                                    jobStatus?.status == EventsExportStatusCode.QUEUED ->
                                        stringResource(Res.string.events_export_status_queued)
                                    jobStatus?.status == EventsExportStatusCode.PROCESSING ->
                                        stringResource(Res.string.events_export_status_processing)
                                    else -> stringResource(Res.string.events_export_start_btn)
                                }
                            Text(
                                text = statusLabel,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                } else {
                    Text(
                        text = stringResource(Res.string.events_export_start_btn),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            if (canRedownload) {
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = {
                        PlainTooltip {
                            Text(stringResource(Res.string.events_export_redownload_tooltip))
                        }
                    },
                    state = rememberTooltipState(),
                ) {
                    FilledTonalIconButton(
                        onClick = { jobStatus?.exportId?.let(onDownload) },
                        enabled = !isDownloading && (jobStatus?.isDownloadable == true),
                        modifier = Modifier.size(AmberDimens.EventsCompactButtonHeight),
                    ) {
                        if (isDownloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(AmberDimens.EventsSmallIcon),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                painter = painterResource(Res.drawable.ic_download),
                                contentDescription =
                                    stringResource(Res.string.events_export_redownload_tooltip),
                                modifier = Modifier.size(AmberDimens.EventsSmallIcon),
                            )
                        }
                    }
                }
            }
        }
    }
}
