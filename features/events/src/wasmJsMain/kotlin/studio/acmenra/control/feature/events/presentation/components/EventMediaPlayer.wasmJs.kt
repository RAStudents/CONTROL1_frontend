package studio.acmenra.control.feature.events.presentation.components

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
import androidx.compose.ui.layout.ContentScale
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.events_media_preview_desc
import studio.acmenra.control.core.ui.generated.resources.events_video_play
import studio.acmenra.control.core.ui.generated.resources.ic_play
import studio.acmenra.control.core.ui.generated.resources.ic_videocam
import studio.acmenra.control.core.ui.theme.AmberCorners
import studio.acmenra.control.core.ui.theme.AmberDimens

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun(
    """(videoUrl) => {
    var id = 'control-events-video-' + Date.now();
    var video = document.createElement('video');
    video.id = id;
    video.src = videoUrl;
    video.controls = true;
    video.autoplay = true;
    video.style.position = 'fixed';
    video.style.top = '50%';
    video.style.left = '50%';
    video.style.transform = 'translate(-50%, -50%)';
    video.style.maxWidth = '90vw';
    video.style.maxHeight = '80vh';
    video.style.zIndex = '99999';
    video.style.borderRadius = '16px';
    video.style.boxShadow = '0 8px 32px rgba(0,0,0,0.5)';
    video.style.backgroundColor = '#000';
    
    var backdrop = document.createElement('div');
    backdrop.id = id + '-backdrop';
    backdrop.style.position = 'fixed';
    backdrop.style.top = '0';
    backdrop.style.left = '0';
    backdrop.style.width = '100vw';
    backdrop.style.height = '100vh';
    backdrop.style.backgroundColor = 'rgba(0,0,0,0.6)';
    backdrop.style.zIndex = '99998';
    backdrop.onclick = function() {
        try { video.pause(); } catch(e) {}
        if (video.parentNode) video.parentNode.removeChild(video);
        if (backdrop.parentNode) backdrop.parentNode.removeChild(backdrop);
    };

    document.body.appendChild(backdrop);
    document.body.appendChild(video);
    return id;
}""",
)
private external fun createAndPlayVideo(videoUrl: String): String

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun(
    """(elementId) => {
    var video = document.getElementById(elementId);
    if (video) {
        try { video.pause(); } catch(e) {}
        if (video.parentNode) video.parentNode.removeChild(video);
    }
    var backdrop = document.getElementById(elementId + '-backdrop');
    if (backdrop && backdrop.parentNode) {
        backdrop.parentNode.removeChild(backdrop);
    }
}""",
)
private external fun removeVideo(elementId: String)

@Composable
actual fun EventMediaPlayer(
    videoUrl: String?,
    previewUrl: String?,
    modifier: Modifier,
    onDownloadClick: (() -> Unit)?,
) {
    var activeVideoId by remember(videoUrl) { mutableStateOf<String?>(null) }
    var previewBitmap by remember(previewUrl) { mutableStateOf<ImageBitmap?>(null) }

    val isValidHttpsPreview = previewUrl?.startsWith("https://", ignoreCase = true) == true
    val isValidHttpsVideo = videoUrl?.startsWith("https://", ignoreCase = true) == true

    LaunchedEffect(previewUrl, isValidHttpsPreview) {
        if (isValidHttpsPreview && previewUrl != null) {
            val client = HttpClient()
            try {
                val bytes: ByteArray = client.get(previewUrl).body()
                previewBitmap = bytes.decodeToImageBitmap()
            } catch (_: Throwable) {
                previewBitmap = null
            } finally {
                client.close()
            }
        } else {
            previewBitmap = null
        }
    }

    DisposableEffect(videoUrl) {
        onDispose {
            activeVideoId?.let { removeVideo(it) }
            activeVideoId = null
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
        if (previewBitmap != null) {
            Image(
                bitmap = previewBitmap!!,
                contentDescription = stringResource(Res.string.events_media_preview_desc),
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(AmberCorners.Large)),
            )
        } else {
            Icon(
                painter = painterResource(Res.drawable.ic_videocam),
                contentDescription = stringResource(Res.string.events_media_preview_desc),
                modifier = Modifier.size(AmberDimens.ListIconCircle),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (isValidHttpsVideo && !videoUrl.isNullOrBlank()) {
            FilledIconButton(
                onClick = {
                    activeVideoId?.let { removeVideo(it) }
                    activeVideoId = createAndPlayVideo(videoUrl)
                },
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
