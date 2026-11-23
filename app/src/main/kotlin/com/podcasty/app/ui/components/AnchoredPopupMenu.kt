/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import com.podcasty.app.ui.theme.LocalPodcastyColors
import kotlin.math.roundToInt

private data class AnchoredPopupMenuRequest(
    val id: Any,
    val anchorRightPx: Float,
    val anchorTopPx: Float,
    val anchorBottomPx: Float,
    val offset: DpOffset,
    val width: Dp,
    val shape: Shape,
    val hazeState: HazeState?,
    val onDismissRequest: () -> Unit,
    val menu: @Composable () -> Unit,
)

private class AnchoredPopupMenuHostState {
    var request by mutableStateOf<AnchoredPopupMenuRequest?>(null)

    fun show(request: AnchoredPopupMenuRequest) {
        val current = this.request
        if (
            current == null ||
            current.id !== request.id ||
            current.anchorRightPx != request.anchorRightPx ||
            current.anchorTopPx != request.anchorTopPx ||
            current.anchorBottomPx != request.anchorBottomPx ||
            current.offset != request.offset ||
            current.width != request.width ||
            current.shape != request.shape ||
            current.hazeState !== request.hazeState
        ) {
            this.request = request
        }
    }

    fun dismiss(id: Any) {
        if (request?.id === id) request = null
    }

    fun dismissAll() {
        request = null
    }
}

private val LocalAnchoredPopupMenuHost = staticCompositionLocalOf<AnchoredPopupMenuHostState?> { null }

@Composable
fun AnchoredPopupMenuHost(
    hazeState: HazeState?,
    dismissKey: Any? = Unit,
    content: @Composable () -> Unit,
) {
    val host = remember { AnchoredPopupMenuHostState() }
    LaunchedEffect(dismissKey) {
        host.dismissAll()
    }
    CompositionLocalProvider(LocalAnchoredPopupMenuHost provides host) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
            AnchoredPopupMenuOverlay(host.request, hazeState)
        }
    }
}

@Composable
private fun AnchoredPopupMenuOverlay(
    request: AnchoredPopupMenuRequest?,
    defaultHazeState: HazeState?,
) {
    val colors = LocalPodcastyColors.current
    var retainedRequest by remember { mutableStateOf<AnchoredPopupMenuRequest?>(null) }
    var hazeEnabled by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }

    LaunchedEffect(request?.id) {
        if (request == null) {
            progress.animateTo(0f, animationSpec = tween(durationMillis = 180))
            retainedRequest = null
        } else {
            retainedRequest = request
            hazeEnabled = true
            progress.snapTo(0f)
            progress.animateTo(1f, animationSpec = tween(durationMillis = 180))
        }
    }
    val activeRequest = retainedRequest ?: return

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                onClick = activeRequest.onDismissRequest,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ),
    ) {
        val density = LocalDensity.current
        val safeInsets = WindowInsets.safeDrawing
        val topSafePx = safeInsets.getTop(density)
        val bottomSafePx = safeInsets.getBottom(density)
        val edgeInsetPx = with(density) { 16.dp.roundToPx() }
        val minY = topSafePx + edgeInsetPx
        val maxBottomY = (with(density) { maxHeight.roundToPx() } - bottomSafePx - edgeInsetPx).coerceAtLeast(minY + 1)
        val maxAllowedHeightPx = (maxBottomY - minY).coerceAtLeast(with(density) { 200.dp.roundToPx() })
        val maxAllowedHeightDp = with(density) { maxAllowedHeightPx.toDp() }
        val responsiveWidth = minOf(activeRequest.width, (maxWidth - 32.dp).coerceAtLeast(220.dp))
        val hostWidthPx = with(density) { maxWidth.roundToPx() }
        val menuWidthPx = with(density) { responsiveWidth.roundToPx() }
        val offsetX = with(density) { activeRequest.offset.x.roundToPx() }
        val offsetY = with(density) { activeRequest.offset.y.roundToPx() }
        var menuHeightPx by remember(activeRequest.id) { mutableIntStateOf(0) }
        val preferredX = (activeRequest.anchorRightPx - menuWidthPx + offsetX).roundToInt()
        val maxX = (hostWidthPx - menuWidthPx - edgeInsetPx).coerceAtLeast(edgeInsetPx)
        val constrainedX = preferredX.coerceIn(edgeInsetPx, maxX)
        val belowY = (activeRequest.anchorBottomPx + offsetY).roundToInt()
        val aboveY = (activeRequest.anchorTopPx - offsetY - menuHeightPx).roundToInt()
        val renderAbove = menuHeightPx > 0 && belowY + menuHeightPx > maxBottomY
        val preferredY = if (renderAbove) aboveY else belowY
        val maxY = (maxBottomY - menuHeightPx).coerceAtLeast(minY)
        val constrainedY = preferredY.coerceIn(minY, maxY)
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset { IntOffset(constrainedX, constrainedY) }
                .width(responsiveWidth)
                .heightIn(max = maxAllowedHeightDp)
                .onSizeChanged { size -> if (menuHeightPx != size.height) menuHeightPx = size.height }
                .graphicsLayer {
                    alpha = progress.value
                    scaleX = 0.94f + (0.06f * progress.value)
                    scaleY = 0.94f + (0.06f * progress.value)
                    transformOrigin = TransformOrigin(1f, if (renderAbove) 1f else 0f)
                }
                .clip(activeRequest.shape)
                .liquidGlassBackground(
                    hazeState = if (hazeEnabled) activeRequest.hazeState ?: defaultHazeState else null,
                    colors = colors,
                )
                .border(1.dp, colors.borderGlass, activeRequest.shape),
        ) {
            activeRequest.menu()
        }
    }
}

private class AnchorBoundsHolder {
    var right: Float? = null
    var top: Float? = null
    var bottom: Float? = null
}

@Composable
fun AnchoredPopupMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    width: Dp,
    hazeState: HazeState? = null,
    shape: Shape = RoundedCornerShape(28.dp),
    anchor: @Composable () -> Unit,
    menu: @Composable () -> Unit,
) {
    val host = LocalAnchoredPopupMenuHost.current
    val id = remember { Any() }
    // Plain non-State holder: prevents onGloballyPositioned from triggering recomposition on every scroll pixel!
    val boundsHolder = remember { AnchorBoundsHolder() }
    val latestExpanded by rememberUpdatedState(expanded)
    val latestOnDismiss by rememberUpdatedState(onDismissRequest)
    val latestMenu by rememberUpdatedState(menu)

    DisposableEffect(host, id) {
        onDispose { host?.dismiss(id) }
    }

    SideEffect {
        if (expanded && host != null) {
            val right = boundsHolder.right
            val top = boundsHolder.top
            val bottom = boundsHolder.bottom
            if (right != null && top != null && bottom != null) {
                host.show(
                    AnchoredPopupMenuRequest(
                        id = id,
                        anchorRightPx = right,
                        anchorTopPx = top,
                        anchorBottomPx = bottom,
                        offset = offset,
                        width = width,
                        shape = shape,
                        hazeState = hazeState,
                        onDismissRequest = onDismissRequest,
                        menu = menu,
                    ),
                )
            }
        } else if (!expanded) {
            host?.dismiss(id)
        }
    }

    Box(
        modifier = modifier.onGloballyPositioned { coordinates ->
            coordinates.boundsInRoot().let { bounds ->
                boundsHolder.right = bounds.right
                boundsHolder.top = bounds.top
                boundsHolder.bottom = bounds.bottom
                if (latestExpanded && host != null) {
                    host.show(
                        AnchoredPopupMenuRequest(
                            id = id,
                            anchorRightPx = bounds.right,
                            anchorTopPx = bounds.top,
                            anchorBottomPx = bounds.bottom,
                            offset = offset,
                            width = width,
                            shape = shape,
                            hazeState = hazeState,
                            onDismissRequest = latestOnDismiss,
                            menu = latestMenu,
                        ),
                    )
                }
            }
        },
    ) {
        anchor()
    }
}
