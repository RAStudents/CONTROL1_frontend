package studio.acmenra.control.feature.events.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.error_access_denied
import studio.acmenra.control.core.ui.generated.resources.error_network_connection
import studio.acmenra.control.core.ui.generated.resources.error_unknown_occurred
import studio.acmenra.control.core.ui.generated.resources.events_empty_no_events
import studio.acmenra.control.core.ui.generated.resources.events_empty_no_events_filtered
import studio.acmenra.control.core.ui.generated.resources.events_error_apply_suggested
import studio.acmenra.control.core.ui.generated.resources.events_error_filter_invalid
import studio.acmenra.control.core.ui.generated.resources.events_error_snapshot_too_large
import studio.acmenra.control.core.ui.generated.resources.events_filters_reset
import studio.acmenra.control.core.ui.generated.resources.events_list_loading
import studio.acmenra.control.core.ui.generated.resources.events_refresh
import studio.acmenra.control.core.ui.generated.resources.ic_error
import studio.acmenra.control.core.ui.generated.resources.ic_refresh
import studio.acmenra.control.core.ui.theme.AmberCorners
import studio.acmenra.control.core.ui.theme.AmberDimens
import studio.acmenra.control.feature.events.domain.model.EventsInitialLoadError
import kotlin.time.Instant

@Composable
fun EventsEmptyState(
    isLoading: Boolean,
    initialLoadError: EventsInitialLoadError?,
    hasActiveFilters: Boolean,
    onResetFilters: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onApplySuggestedDates: ((Instant?, Instant?) -> Unit)? = null,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(AmberDimens.CardPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(AmberDimens.ListIconCircle),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(AmberDimens.GapMedium))
                Text(
                    text = stringResource(Res.string.events_list_loading),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (initialLoadError != null) {
                Icon(
                    painter = painterResource(Res.drawable.ic_error),
                    contentDescription = null,
                    modifier = Modifier.size(AmberDimens.ListIconCircle),
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(AmberDimens.GapMedium))

                when (initialLoadError) {
                    is EventsInitialLoadError.SnapshotTooLarge -> {
                        Text(
                            text = stringResource(Res.string.events_error_snapshot_too_large),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (initialLoadError.suggestedFrom != null &&
                            initialLoadError.suggestedTo != null &&
                            onApplySuggestedDates != null
                        ) {
                            Spacer(modifier = Modifier.height(AmberDimens.GapMedium))
                            Button(
                                onClick = {
                                    onApplySuggestedDates(
                                        initialLoadError.suggestedFrom,
                                        initialLoadError.suggestedTo,
                                    )
                                },
                                shape = RoundedCornerShape(AmberCorners.Medium),
                            ) {
                                Text(stringResource(Res.string.events_error_apply_suggested))
                            }
                        }
                    }
                    is EventsInitialLoadError.InvalidFilter -> {
                        Text(
                            text = stringResource(Res.string.events_error_filter_invalid),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.height(AmberDimens.GapMedium))
                        Text(
                            text = stringResource(Res.string.events_filters_reset),
                            style =
                                MaterialTheme.typography.bodyMedium.copy(
                                    textDecoration = TextDecoration.Underline,
                                    color = MaterialTheme.colorScheme.primary,
                                ),
                            modifier = Modifier.clickable(onClick = onResetFilters),
                        )
                    }
                    EventsInitialLoadError.AccessDenied -> {
                        Text(
                            text = stringResource(Res.string.error_access_denied),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    EventsInitialLoadError.Network -> {
                        Text(
                            text = stringResource(Res.string.error_network_connection),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.height(AmberDimens.GapMedium))
                        OutlinedButton(onClick = onRetry, shape = RoundedCornerShape(AmberCorners.Medium)) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_refresh),
                                contentDescription = null,
                                modifier = Modifier.size(AmberDimens.ListIcon),
                            )
                            Spacer(modifier = Modifier.size(AmberDimens.GapSmall))
                            Text(stringResource(Res.string.events_refresh))
                        }
                    }
                    is EventsInitialLoadError.Server -> {
                        Text(
                            text = initialLoadError.message ?: stringResource(Res.string.error_unknown_occurred),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.height(AmberDimens.GapMedium))
                        OutlinedButton(onClick = onRetry, shape = RoundedCornerShape(AmberCorners.Medium)) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_refresh),
                                contentDescription = null,
                                modifier = Modifier.size(AmberDimens.ListIcon),
                            )
                            Spacer(modifier = Modifier.size(AmberDimens.GapSmall))
                            Text(stringResource(Res.string.events_refresh))
                        }
                    }
                }
            } else if (hasActiveFilters) {
                Text(
                    text = stringResource(Res.string.events_empty_no_events_filtered),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(AmberDimens.GapMedium))
                Text(
                    text = stringResource(Res.string.events_filters_reset),
                    style =
                        MaterialTheme.typography.bodyMedium.copy(
                            textDecoration = TextDecoration.Underline,
                            color = MaterialTheme.colorScheme.primary,
                        ),
                    modifier = Modifier.clickable(onClick = onResetFilters),
                )
            } else {
                Text(
                    text = stringResource(Res.string.events_empty_no_events),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
