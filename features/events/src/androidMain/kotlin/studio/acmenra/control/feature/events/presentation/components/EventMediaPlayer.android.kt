package studio.acmenra.control.feature.events.presentation.components

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.events_media_preview_desc
import studio.acmenra.control.core.ui.generated.resources.events_video_play
import studio.acmenra.control.core.ui.generated.resources.ic_play
import studio.acmenra.control.core.ui.generated.resources.ic_videocam
import studio.acmenra.control.core.ui.theme.AmberCorners
import studio.acmenra.control.core.ui.theme.AmberDimens

@Composable
actual fun EventMediaPlayer(
    videoUrl: String?,
    previewUrl: String?,
    modifier: Modifier,
    onDownloadClick: (() -> Unit)?,
) {
    var isPlaying by remember(videoUrl) { mutableStateOf(false) }

    Box(
        modifier =
            modifier
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(AmberCorners.Large),
                ),
        contentAlignment = Alignment.Center,
    ) {
        if (isPlaying && !videoUrl.isNullOrBlank()) {
            AndroidView(
                factory = { context ->
                    VideoView(context).apply {
                        val controller = MediaController(context)
                        controller.setAnchorView(this)
                        setMediaController(controller)
                        setVideoURI(Uri.parse(videoUrl))
                        setOnPreparedListener { mp ->
                            mp.isLooping = true
                            start()
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            DisposableEffect(videoUrl) {
                onDispose {
                    isPlaying = false
                }
            }
        } else {
            // Preview / Placeholder state
            Icon(
                painter = painterResource(Res.drawable.ic_videocam),
                contentDescription = stringResource(Res.string.events_media_preview_desc),
                modifier = Modifier.size(AmberDimens.ListIconCircle),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!videoUrl.isNullOrBlank()) {
                FilledIconButton(
                    onClick = { isPlaying = true },
                    modifier = Modifier.size(AmberDimens.ButtonHeight),
                    colors =
                        IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_play),
                        contentDescription = stringResource(Res.string.events_video_play),
                        modifier = Modifier.size(AmberDimens.ListIcon),
                    )
                }
            }
        }
    }
}
