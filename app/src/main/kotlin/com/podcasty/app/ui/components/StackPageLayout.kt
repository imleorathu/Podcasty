/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import com.podcasty.app.R
import com.podcasty.app.ui.theme.LocalPodcastyColors
import com.podcasty.app.ui.theme.PodcastyColors

private val PageHorizontalPadding = 24.dp
private val HeaderTopPadding = 6.dp
private val HeaderBottomPadding = 10.dp
private val HeaderHeight = 48.dp
internal val StackPageHeaderContentGap = 12.dp
private val HeaderControlGap = 12.dp
internal const val StackPageTitleTag = "stack-page-title"

internal fun Modifier.headerGlassSurface(
    hazeState: HazeState?,
    colors: PodcastyColors,
): Modifier = clip(RectangleShape)
    .liquidGlassBackground(hazeState, colors)
    .drawBehind {
        val strokeWidth = 1.dp.toPx()
        val y = size.height - strokeWidth / 2
        drawLine(
            color = colors.borderGlass,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = strokeWidth,
        )
    }

private data class HeaderTitle(
    val stackKey: String,
    val text: String,
)

@Composable
fun StackPageLayout(
    title: String,
    hazeState: HazeState?,
    contentBottomPadding: Dp,
    isContentScrolled: Boolean,
    onBackClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
    hasActions: Boolean = false,
    animateChanges: Boolean = true,
    titleStackKey: String = "",
    isForward: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (Modifier, PaddingValues) -> Unit,
) {
    val density = LocalDensity.current
    val statusBarPadding = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val contentPadding = PaddingValues(
        start = PageHorizontalPadding,
        top = statusBarPadding + HeaderTopPadding + HeaderHeight + HeaderBottomPadding + StackPageHeaderContentGap,
        end = PageHorizontalPadding,
        bottom = contentBottomPadding,
    )
    Box(modifier = modifier.fillMaxSize()) {
        content(Modifier.fillMaxSize(), contentPadding)
        if (showHeader) {
            StackPageHeader(
                title = title,
                hazeState = hazeState,
                isContentScrolled = isContentScrolled,
                onBackClick = onBackClick,
                hasActions = hasActions,
                animateChanges = animateChanges,
                titleStackKey = titleStackKey,
                isForward = isForward,
                solidBackButton = false,
                actions = actions,
            )
        }
    }
}

@Composable
fun StackPageHeader(
    title: String,
    hazeState: HazeState?,
    isContentScrolled: Boolean,
    onBackClick: (() -> Unit)?,
    hasActions: Boolean = false,
    animateChanges: Boolean = true,
    titleStackKey: String = "",
    isForward: Boolean = true,
    solidBackButton: Boolean = false,
    backGlassTintAlpha: Float? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = LocalPodcastyColors.current
    val density = LocalDensity.current
    val statusBarPadding = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val targetTitleStartPadding = PageHorizontalPadding + if (onBackClick != null) {
        HeaderHeight + HeaderControlGap
    } else {
        0.dp
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(statusBarPadding + HeaderTopPadding + HeaderHeight + HeaderBottomPadding)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var change = down
                    while (change.pressed) {
                        change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    }
                }
            },
    ) {
        AnimatedVisibility(
            visible = isContentScrolled,
            enter = if (animateChanges) fadeIn() else EnterTransition.None,
            exit = if (animateChanges) fadeOut() else ExitTransition.None,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .headerGlassSurface(hazeState, colors),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = targetTitleStartPadding,
                    end = PageHorizontalPadding,
                    top = statusBarPadding + HeaderTopPadding,
                )
                .height(HeaderHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AnimatedContent(
                targetState = HeaderTitle(stackKey = titleStackKey, text = title),
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    if (animateChanges) {
                        (
                            (
                                slideInHorizontally(animationSpec = tween(200)) { width ->
                                    if (isForward) width / 3 else -width / 3
                                } + fadeIn(animationSpec = tween(200))
                            ) togetherWith ExitTransition.None
                        ).using(null)
                    } else {
                        (EnterTransition.None togetherWith ExitTransition.None).using(null)
                    }
                },
                label = "page-title",
            ) { currentTitle ->
                Text(
                    text = currentTitle.text,
                    modifier = Modifier
                        .wrapContentWidth(Alignment.Start)
                        .testTag(StackPageTitleTag),
                    color = colors.textMain,
                    style = if (onBackClick != null) {
                        MaterialTheme.typography.headlineSmall
                    } else {
                        MaterialTheme.typography.headlineLarge
                    },
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HeaderActionSlot(
                visible = hasActions,
                animateChanges = animateChanges,
                actions = actions,
            )
        }
        Box(
            modifier = Modifier
                .padding(
                    start = PageHorizontalPadding,
                    top = statusBarPadding + HeaderTopPadding,
                )
                .size(HeaderHeight),
        ) {
            AnimatedVisibility(
                visible = onBackClick != null,
                enter = if (animateChanges) fadeIn() else EnterTransition.None,
                exit = if (animateChanges) fadeOut() else ExitTransition.None,
            ) {
                PodcastyBackButton(
                    hazeState = hazeState,
                    solid = solidBackButton,
                    glassTint = backGlassTintAlpha?.let { colors.glass.copy(alpha = it) },
                    onClick = onBackClick ?: {},
                )
            }
        }
    }
}

@Composable
private fun HeaderActionSlot(
    visible: Boolean,
    animateChanges: Boolean,
    actions: @Composable RowScope.() -> Unit,
) {
    Box(
        modifier = Modifier.width(HeaderHeight),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = if (animateChanges) fadeIn() else EnterTransition.None,
            exit = if (animateChanges) fadeOut() else ExitTransition.None,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                content = actions,
            )
        }
    }
}

@Composable
fun PodcastyBackButton(
    hazeState: HazeState?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    solid: Boolean = false,
    glassTint: Color? = null,
) {
    val label = stringResource(R.string.navigate_back)
    val colors = LocalPodcastyColors.current
    PodcastyGlassIconButton(
        hazeState = hazeState,
        symbol = MaterialSymbols.ChevronLeft,
        label = label,
        onClick = onClick,
        modifier = modifier,
        surfaceColor = if (solid) colors.background else null,
        borderColor = if (solid) colors.textMuted.copy(alpha = 0.55f) else null,
        glassTint = glassTint,
    )
}

@Composable
fun PodcastyGlassIconButton(
    hazeState: HazeState?,
    symbol: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    surfaceColor: Color? = null,
    borderColor: Color? = null,
    glassTint: Color? = null,
) {
    val colors = LocalPodcastyColors.current
    Box(
        modifier = modifier
            .size(HeaderHeight)
            .clip(CircleShape)
            .then(
                if (surfaceColor == null) Modifier.liquidGlassBackground(hazeState, colors, 30.dp, glassTint)
                else Modifier.background(surfaceColor),
            )
            .border(1.dp, borderColor ?: colors.borderGlass, CircleShape)
            .semantics { contentDescription = label }
            .clickable(
                onClick = onClick,
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ),
        contentAlignment = Alignment.Center,
    ) {
        MaterialSymbol(
            symbol = symbol,
            contentDescription = null,
            tint = colors.textMain,
        )
    }
}
