package studio.acmenra.control.feature.events.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.events_export_download_btn
import studio.acmenra.control.core.ui.generated.resources.events_export_format_excel
import studio.acmenra.control.core.ui.generated.resources.events_export_format_label
import studio.acmenra.control.core.ui.generated.resources.events_export_format_standard
import studio.acmenra.control.core.ui.generated.resources.events_export_include_media_links
import studio.acmenra.control.core.ui.generated.resources.events_export_media_links_warning
import studio.acmenra.control.core.ui.generated.resources.events_export_panel_title
import studio.acmenra.control.core.ui.generated.resources.events_export_rows_count
import studio.acmenra.control.core.ui.generated.resources.events_export_rows_count_server
import studio.acmenra.control.core.ui.generated.resources.events_export_start_btn
import studio.acmenra.control.core.ui.generated.resources.events_export_status_completed
import studio.acmenra.control.core.ui.generated.resources.events_export_status_expired
import studio.acmenra.control.core.ui.generated.resources.events_export_status_failed
import studio.acmenra.control.core.ui.generated.resources.events_export_status_processing
import studio.acmenra.control.core.ui.generated.resources.events_export_status_queued
import studio.acmenra.control.core.ui.generated.resources.ic_download
import studio.acmenra.control.core.ui.generated.resources.ic_warning
import studio.acmenra.control.core.ui.theme.AmberCorners
import studio.acmenra.control.core.ui.theme.AmberDimens
import studio.acmenra.control.feature.events.domain.model.EventsExportFormatPreset
import studio.acmenra.control.feature.events.domain.model.EventsExportStatus
import studio.acmenra.control.feature.events.domain.model.EventsExportStatusCode

@Composable
fun EventsExportPanel(
    formatPreset: EventsExportFormatPreset,
    includeMediaLinks: Boolean,
    totalRecords: Int?,
    jobStatus: EventsExportStatus?,
    isSubmitting: Boolean,
    errorMessage: StringResource?,
    onFormatPresetChanged: (EventsExportFormatPreset) -> Unit,
    onIncludeMediaLinksChanged: (Boolean) -> Unit,
    onStartExport: () -> Unit,
    onDownload: (String) -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(AmberDimens.GapMedium),
        ) {
            // Header
            Text(
                text = stringResource(Res.string.events_export_panel_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // Format selection
            Column {
                Text(
                    text = stringResource(Res.string.events_export_format_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(AmberDimens.GapSmall))
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onFormatPresetChanged(EventsExportFormatPreset.EXCEL_SEMICOLON_BOM) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = formatPreset == EventsExportFormatPreset.EXCEL_SEMICOLON_BOM,
                        onClick = { onFormatPresetChanged(EventsExportFormatPreset.EXCEL_SEMICOLON_BOM) },
                    )
                    Spacer(modifier = Modifier.width(AmberDimens.GapSmall))
                    Text(
                        text = stringResource(Res.string.events_export_format_excel),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onFormatPresetChanged(EventsExportFormatPreset.STANDARD_COMMA_UTF8) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = formatPreset == EventsExportFormatPreset.STANDARD_COMMA_UTF8,
                        onClick = { onFormatPresetChanged(EventsExportFormatPreset.STANDARD_COMMA_UTF8) },
                    )
                    Spacer(modifier = Modifier.width(AmberDimens.GapSmall))
                    Text(
                        text = stringResource(Res.string.events_export_format_standard),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // Media links toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.events_export_include_media_links),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = includeMediaLinks,
                    onCheckedChange = onIncludeMediaLinksChanged,
                )
            }

            // Warning if media links enabled
            if (includeMediaLinks) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(AmberCorners.Small),
                            ).padding(AmberDimens.GapMedium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_warning),
                        contentDescription = null,
                        modifier = Modifier.size(AmberDimens.ListIcon),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(modifier = Modifier.width(AmberDimens.GapSmall))
                    Text(
                        text = stringResource(Res.string.events_export_media_links_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            // Records count
            val recordsText =
                if (totalRecords != null) {
                    stringResource(Res.string.events_export_rows_count, totalRecords)
                } else {
                    stringResource(Res.string.events_export_rows_count_server)
                }
            Text(
                text = recordsText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Start Export Button
            Button(
                onClick = onStartExport,
                modifier = Modifier.fillMaxWidth().height(AmberDimens.ButtonHeight),
                enabled = !isSubmitting,
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(AmberDimens.ListIcon),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(
                        text = stringResource(Res.string.events_export_start_btn),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            // Job Status & Download
            jobStatus?.let { status ->
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                shape = RoundedCornerShape(AmberCorners.Small),
                            ).padding(AmberDimens.GapMedium),
                    verticalArrangement = Arrangement.spacedBy(AmberDimens.GapSmall),
                ) {
                    when (status.status) {
                        EventsExportStatusCode.QUEUED -> {
                            Text(
                                text = stringResource(Res.string.events_export_status_queued),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        EventsExportStatusCode.PROCESSING -> {
                            val percent = status.progressPercent
                            if (percent != null) {
                                Text(
                                    text =
                                        stringResource(
                                            Res.string.events_export_status_processing,
                                            percent,
                                        ),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                LinearProgressIndicator(
                                    progress = { percent / 100f },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                Text(
                                    text = stringResource(Res.string.events_export_status_queued),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                        EventsExportStatusCode.COMPLETED -> {
                            Text(
                                text = stringResource(Res.string.events_export_status_completed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Button(
                                onClick = { onDownload(status.exportId) },
                                enabled = status.isDownloadable,
                                modifier = Modifier.fillMaxWidth().height(AmberDimens.ButtonHeight),
                            ) {
                                Icon(
                                    painter = painterResource(Res.drawable.ic_download),
                                    contentDescription = null,
                                    modifier = Modifier.size(AmberDimens.ListIcon),
                                )
                                Spacer(modifier = Modifier.width(AmberDimens.GapSmall))
                                Text(text = stringResource(Res.string.events_export_download_btn))
                            }
                        }
                        EventsExportStatusCode.FAILED,
                        EventsExportStatusCode.UNKNOWN,
                        -> {
                            Text(
                                text = stringResource(Res.string.events_export_status_failed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        EventsExportStatusCode.EXPIRED -> {
                            Text(
                                text = stringResource(Res.string.events_export_status_expired),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            // Error display
            errorMessage?.let { err ->
                Text(
                    text = stringResource(err),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
