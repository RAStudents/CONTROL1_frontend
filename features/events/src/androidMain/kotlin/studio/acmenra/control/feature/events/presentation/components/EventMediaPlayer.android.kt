package studio.acmenra.control.feature.events.presentation.components

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    var previewBitmap by remember(previewUrl) { mutableStateOf<ImageBitmap?>(null) }

    val isValidHttpsPreview = previewUrl?.startsWith("https://", ignoreCase = true) == true
    val isValidHttpsVideo = videoUrl?.startsWith("https://", ignoreCase = true) == true

    LaunchedEffect(previewUrl, isValidHttpsPreview) {
        if (isValidHttpsPreview && previewUrl != null) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val conn = java.net.URL(previewUrl).openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    // Public media URL: strictly no Bearer / Authorization headers added
                    conn.inputStream.use { stream ->
                        android.graphics.BitmapFactory
                            .decodeStream(stream)
                            ?.asImageBitmap()
                    }
                }.getOrNull()?.let {
                    previewBitmap = it
                }
            }
        } else {
            previewBitmap = null
        }
    }

    Box(
        modifier =
            modifier
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(AmberCorners.Large),
                ),
        contentAlignment = Alignment.Center,
    ) {
        if (isPlaying && isValidHttpsVideo && !videoUrl.isNullOrBlank()) {
            var videoViewRef by remember(videoUrl) { mutableStateOf<VideoView?>(null) }
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
                        videoViewRef = this
                    }
                },
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(AmberCorners.Large)),
            )
            DisposableEffect(videoUrl) {
                onDispose {
                    videoViewRef?.stopPlayback()
                    videoViewRef = null
                    isPlaying = false
                }
            }
        } else {
            if (previewBitmap != null) {
                Image(
                    bitmap = previewBitmap!!,
                    contentDescription = stringResource(Res.string.events_media_preview_desc),
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(AmberCorners.Large)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                // Placeholder state
                Icon(
                    painter = painterResource(Res.drawable.ic_videocam),
                    contentDescription = stringResource(Res.string.events_media_preview_desc),
                    modifier = Modifier.size(AmberDimens.ListIconCircle),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (isValidHttpsVideo && !videoUrl.isNullOrBlank()) {
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
