package studio.acmenra.control.feature.events.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun EventMediaPlayer(
    videoUrl: String?,
    previewUrl: String?,
    modifier: Modifier = Modifier,
    onDownloadClick: (() -> Unit)? = null,
)
