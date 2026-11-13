package studio.acmenra.control.core.ui.notice

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.ic_check_circle
import studio.acmenra.control.core.ui.generated.resources.ic_close
import studio.acmenra.control.core.ui.generated.resources.ic_error
import studio.acmenra.control.core.ui.generated.resources.ic_info
import studio.acmenra.control.core.ui.generated.resources.ic_language
import studio.acmenra.control.core.ui.generated.resources.notice_close
import studio.acmenra.control.core.ui.theme.AmberDimens

// edgeToEdge (narrow screens): full width, paints behind the status bar, rounded only at the bottom.
// Otherwise a floating card with all corners rounded or connected list item shape.
@Composable
fun NoticeBanner(
    notice: Notice,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    edgeToEdge: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val container: Color
    val content: Color
    val icon: DrawableResource
    when (notice.type) {
        NoticeType.Info -> {
            container = colors.secondaryContainer
            content = colors.onSecondaryContainer
            icon = Res.drawable.ic_info
        }
        NoticeType.Offline -> {
            container = colors.secondaryContainer
            content = colors.onSecondaryContainer
            icon = Res.drawable.ic_language
        }
        NoticeType.Success -> {
            container = colors.tertiaryContainer
            content = colors.onTertiaryContainer
            icon = Res.drawable.ic_check_circle
        }
        NoticeType.Error -> {
            container = colors.errorContainer
            content = colors.onErrorContainer
            icon = Res.drawable.ic_error
        }
    }

    val resolvedShape =
        shape ?: if (edgeToEdge) {
            MaterialTheme.shapes.extraLarge.copy(topStart = ZeroCornerSize, topEnd = ZeroCornerSize)
        } else {
            MaterialTheme.shapes.extraLarge
        }

    val insets =
        if (edgeToEdge) {
            Modifier.windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        } else {
            Modifier
        }

    Surface(
        // Screen readers announce the notice when it appears
        modifier =
            modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
        shape = resolvedShape,
        color = container,
        contentColor = content,
        shadowElevation = AmberDimens.NoticeElevation,
    ) {
        Box {
            Row(
                modifier =
                    insets.padding(
                        start = AmberDimens.ScreenMargin,
                        end = AmberDimens.GapSmall,
                        top = AmberDimens.NoticeVerticalPadding,
                        bottom = AmberDimens.NoticeVerticalPadding,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AmberDimens.NoticeContentGap),
            ) {
                IconCircle(icon = icon, pulse = notice.type == NoticeType.Offline)
                Column(Modifier.weight(1f)) {
                    Text(text = notice.title.resolve(), style = MaterialTheme.typography.titleMedium)
                    notice.message?.let {
                        Text(text = it.resolve(), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_close),
                        contentDescription = stringResource(Res.string.notice_close),
                    )
                }
            }
            notice.durationMs?.let { TimerBar(id = notice.id, durationMs = it, color = content) }
        }
    }
}

@Composable
private fun IconCircle(
    icon: DrawableResource,
    pulse: Boolean,
) {
    val scale = if (pulse) pulseScale() else 1f
    Box(
        modifier =
            Modifier
                .size(AmberDimens.ListIconCircle)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.background(MaterialTheme.colorScheme.surface, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(AmberDimens.ListIcon),
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun pulseScale(): Float {
    val transition = rememberInfiniteTransition(label = "notice-pulse")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = NoticeDefaults.PulseScale,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = NoticeDefaults.PulseDurationMs, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "notice-pulse-scale",
    )
    return scale
}

@Composable
private fun BoxScope.TimerBar(
    id: Long,
    durationMs: Long,
    color: Color,
) {
    val progress = remember(id) { Animatable(1f) }
    LaunchedEffect(id) {
        progress.animateTo(0f, tween(durationMillis = durationMs.toInt(), easing = LinearEasing))
    }
    Box(
        Modifier
            .align(Alignment.TopStart)
            .fillMaxWidth()
            .height(AmberDimens.NoticeTimerHeight)
            .drawBehind {
                drawRect(
                    color = color.copy(alpha = NoticeDefaults.TimerAlpha),
                    size = Size(width = size.width * progress.value, height = size.height),
                )
            },
    )
}
