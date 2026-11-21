package studio.acmenra.control.feature.events.presentation.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import studio.acmenra.control.core.ui.theme.AmberDimens
import studio.acmenra.control.feature.events.domain.model.EventsExportFormatPreset
import studio.acmenra.control.feature.events.presentation.EventsState

/**
 * Permanent structural shell for EventsSummaryCard and EventsExportPanel.
 * Maintains fixed height [AmberDimens.EventsSummaryExportHeight] (144.dp),
 * surfaceContainerHigh color, and extraLarge shape across all responsive layout modes.
 * Switches between Summary and Export modes using AnimatedContent with fadeIn/fadeOut.
 */
@Composable
fun EventsSummaryExportSlot(
    state: EventsState,
    isCompact: Boolean,
    onFormatPresetChanged: (EventsExportFormatPreset) -> Unit,
    onIncludeMediaLinksChanged: (Boolean) -> Unit,
    onStartExport: () -> Unit,
    onDownload: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .height(AmberDimens.EventsSummaryExportHeight),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        AnimatedContent(
            targetState = state.isExportModeActive,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "EventsSummaryExportTransition",
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(AmberDimens.EventsSummaryPadding),
        ) { isExportMode ->
            if (isExportMode) {
                EventsExportPanel(
                    formatPreset = state.exportFormatPreset,
                    includeMediaLinks = state.exportIncludeMediaLinks,
                    totalRecords = state.snapshotTotalCount,
                    jobStatus = state.exportJobStatus,
                    isSubmitting = state.isExportSubmitting,
                    isDownloading = state.isExportDownloading,
                    canExport = state.access.canExport,
                    onFormatPresetChanged = onFormatPresetChanged,
                    onIncludeMediaLinksChanged = onIncludeMediaLinksChanged,
                    onStartExport = onStartExport,
                    onDownload = onDownload,
                )
            } else {
                EventsSummaryCard(
                    summary = state.summary,
                    isLoading = state.isInitialLoading,
                    isCompact = isCompact,
                )
            }
        }
    }
}
