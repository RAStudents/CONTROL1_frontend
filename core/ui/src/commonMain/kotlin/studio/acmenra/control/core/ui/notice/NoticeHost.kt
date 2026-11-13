package studio.acmenra.control.core.ui.notice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import studio.acmenra.control.core.ui.theme.AmberCorners
import studio.acmenra.control.core.ui.theme.AmberDimens

// Root notification layer. Place it in App.kt as the LAST child of the root Box (after AppNavHost)
// so it draws above every screen, top bar, rail and bottom bar.
// Displays up to 3 notifications simultaneously, aligned top-right on wide screens.
// If a new notice arrives, the oldest one is evicted with smooth upward animation.
// Narrower than TabletBreakpoint: full width, connected edge-to-edge stack.
// Wider: floating connected cards at top right, width AmberDimens.NoticeMaxWidth.
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NoticeHost(
    modifier: Modifier = Modifier,
    controller: NoticeController = koinInject(),
) {
    val hostToken = remember { Any() }
    DisposableEffect(controller, hostToken) {
        controller.attachHost(hostToken)
        onDispose { controller.detachHost(hostToken) }
    }
    val hosts by controller.hosts.collectAsState()
    val notices by controller.notices.collectAsState()
    val activeNotices = if (hosts.lastOrNull() === hostToken) notices else emptyList()

    val displayedNotices = remember { mutableStateListOf<Notice>() }
    var swipingNoticeId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(activeNotices) {
        activeNotices.forEach { notice ->
            if (displayedNotices.none { it.id == notice.id }) {
                displayedNotices.add(notice)
            }
        }
    }

    val motion = MaterialTheme.motionScheme
    val offsetSpec = motion.fastSpatialSpec<IntOffset>()
    val sizeSpec = motion.fastSpatialSpec<IntSize>()
    val scaleSpec = motion.fastSpatialSpec<Float>()
    val fadeSpec = motion.fastEffectsSpec<Float>()
    val cornerSpec = motion.fastSpatialSpec<Dp>()

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val compact = maxWidth < AmberDimens.TabletBreakpoint

        val frame =
            if (compact) {
                Modifier.fillMaxWidth()
            } else {
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = AmberDimens.ScreenMargin, vertical = AmberDimens.GapSmall)
            }

        Box(
            modifier = frame,
            contentAlignment = if (compact) Alignment.TopCenter else Alignment.TopEnd,
        ) {
            Column(
                modifier =
                    if (compact) {
                        Modifier.fillMaxWidth()
                    } else {
                        Modifier.width(AmberDimens.NoticeMaxWidth)
                    },
                verticalArrangement = Arrangement.spacedBy(AmberDimens.ListGap),
                horizontalAlignment = if (compact) Alignment.CenterHorizontally else Alignment.End,
            ) {
                val activeIds = remember(activeNotices) { activeNotices.map { it.id }.toSet() }

                displayedNotices.forEachIndexed { index, notice ->
                    key(notice.id) {
                        val isActive = notice.id in activeIds
                        val isSwiping = swipingNoticeId == notice.id
                        val isGluedToTop = compact && (index == 0)

                        val hasGluedAbove =
                            (index > 0) &&
                                !isSwiping &&
                                (swipingNoticeId != displayedNotices[index - 1].id)
                        val hasGluedBelow =
                            (index < displayedNotices.size - 1) &&
                                !isSwiping &&
                                (swipingNoticeId != displayedNotices[index + 1].id)

                        val targetTopCorner =
                            when {
                                isGluedToTop && !isSwiping -> AmberCorners.None
                                hasGluedAbove -> AmberCorners.Small
                                else -> AmberCorners.ExtraLarge
                            }

                        val targetBottomCorner =
                            when {
                                hasGluedBelow -> AmberCorners.Small
                                else -> AmberCorners.ExtraLarge
                            }

                        val animatedTopCorner by animateDpAsState(
                            targetValue = targetTopCorner,
                            animationSpec = cornerSpec,
                            label = "notice-corner-top",
                        )
                        val animatedBottomCorner by animateDpAsState(
                            targetValue = targetBottomCorner,
                            animationSpec = cornerSpec,
                            label = "notice-corner-bottom",
                        )

                        // Prevent negative corner radius when spring overshoots past 0.dp
                        val safeTopCorner = animatedTopCorner.coerceAtLeast(0.dp)
                        val safeBottomCorner = animatedBottomCorner.coerceAtLeast(0.dp)

                        val shape =
                            RoundedCornerShape(
                                topStart = safeTopCorner,
                                topEnd = safeTopCorner,
                                bottomStart = safeBottomCorner,
                                bottomEnd = safeBottomCorner,
                            )

                        AnimatedNoticeItem(
                            notice = notice,
                            isActive = isActive,
                            shape = shape,
                            edgeToEdge = isGluedToTop && !isSwiping,
                            offsetSpec = offsetSpec,
                            sizeSpec = sizeSpec,
                            scaleSpec = scaleSpec,
                            fadeSpec = fadeSpec,
                            onSwipingChanged = { swiping ->
                                swipingNoticeId =
                                    if (swiping) {
                                        notice.id
                                    } else if (swipingNoticeId == notice.id) {
                                        null
                                    } else {
                                        swipingNoticeId
                                    }
                            },
                            onDismiss = { controller.dismiss(notice.id) },
                            onAnimationEnd = { displayedNotices.removeAll { it.id == notice.id } },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AnimatedNoticeItem(
    notice: Notice,
    isActive: Boolean,
    shape: Shape,
    edgeToEdge: Boolean,
    offsetSpec: FiniteAnimationSpec<IntOffset>,
    sizeSpec: FiniteAnimationSpec<IntSize>,
    scaleSpec: FiniteAnimationSpec<Float>,
    fadeSpec: FiniteAnimationSpec<Float>,
    onSwipingChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onAnimationEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visibleState = remember { MutableTransitionState(false) }
    var dismissedBySwipe by remember { mutableStateOf(false) }

    LaunchedEffect(isActive) {
        visibleState.targetState = isActive
    }

    LaunchedEffect(visibleState.currentState, visibleState.targetState, visibleState.isIdle) {
        if (visibleState.isIdle && !visibleState.currentState && !visibleState.targetState) {
            onAnimationEnd()
        }
    }

    // Auto dismiss timer per notice
    LaunchedEffect(notice.id, notice.durationMs) {
        val duration = notice.durationMs ?: return@LaunchedEffect
        delay(duration)
        onDismiss()
    }

    val enterTransition =
        slideInVertically(offsetSpec) { -it } +
            fadeIn(fadeSpec) +
            scaleIn(
                animationSpec = scaleSpec,
                initialScale = NoticeDefaults.EnterScale,
                transformOrigin = TransformOrigin(0.5f, 0f),
            ) +
            expandVertically(sizeSpec, expandFrom = Alignment.Top, clip = false)

    val exitTransition =
        if (dismissedBySwipe) {
            fadeOut(fadeSpec) +
                shrinkVertically(sizeSpec, shrinkTowards = Alignment.Top, clip = false)
        } else {
            slideOutVertically(offsetSpec) { -it } +
                fadeOut(fadeSpec) +
                scaleOut(
                    animationSpec = scaleSpec,
                    targetScale = NoticeDefaults.EnterScale,
                    transformOrigin = TransformOrigin(0.5f, 0f),
                ) +
                shrinkVertically(sizeSpec, shrinkTowards = Alignment.Top, clip = false)
        }

    AnimatedVisibility(
        visibleState = visibleState,
        enter = enterTransition,
        exit = exitTransition,
        modifier = modifier,
    ) {
        SwipeableNotice(
            notice = notice,
            shape = shape,
            edgeToEdge = edgeToEdge,
            onSwipingChanged = onSwipingChanged,
            onDismiss = { bySwipe ->
                if (bySwipe) {
                    dismissedBySwipe = true
                }
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableNotice(
    notice: Notice,
    shape: Shape,
    edgeToEdge: Boolean,
    onSwipingChanged: (Boolean) -> Unit,
    onDismiss: (bySwipe: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberSwipeToDismissBoxState()

    val offset = runCatching { state.requireOffset() }.getOrDefault(0f)
    val isSwiping = kotlin.math.abs(offset) > 1f || state.targetValue != SwipeToDismissBoxValue.Settled

    LaunchedEffect(isSwiping) {
        onSwipingChanged(isSwiping)
    }

    LaunchedEffect(state.currentValue) {
        if (state.currentValue != SwipeToDismissBoxValue.Settled) {
            onDismiss(true)
        }
    }

    SwipeToDismissBox(
        state = state,
        backgroundContent = {},
        modifier = modifier,
    ) {
        NoticeBanner(
            notice = notice,
            onDismiss = { onDismiss(false) },
            shape = shape,
            edgeToEdge = edgeToEdge,
        )
    }
}

// Bridge between State and the banner: the ViewModel puts a Notice into a State field,
// the screen calls ConsumeNotice(state.notice, viewModel::onNoticeConsumed),
// the ViewModel then resets the field to null.
@Composable
fun ConsumeNotice(
    notice: Notice?,
    onConsumed: () -> Unit,
    controller: NoticeController = koinInject(),
) {
    val currentOnConsumed by rememberUpdatedState(onConsumed)
    LaunchedEffect(notice) {
        if (notice != null) {
            controller.show(notice)
            currentOnConsumed()
        }
    }
}

// Queue bridge: the ViewModel keeps a list of pending notices in State.
// The screen calls ConsumeNotices(state.notices, viewModel::onNoticeConsumed).
// Notices are delivered to NoticeController one-by-one without conflation.
@Composable
fun ConsumeNotices(
    notices: List<Notice>,
    onConsumed: (Long) -> Unit,
    controller: NoticeController = koinInject(),
) {
    val currentOnConsumed by rememberUpdatedState(onConsumed)
    LaunchedEffect(notices) {
        val pending = notices.firstOrNull() ?: return@LaunchedEffect
        controller.show(pending)
        currentOnConsumed(pending.id)
    }
}
