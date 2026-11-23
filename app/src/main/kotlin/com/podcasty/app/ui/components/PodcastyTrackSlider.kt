/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.podcasty.app.ui.theme.LocalPodcastyColors
import com.podcasty.app.ui.theme.PodcastyColors

internal fun touchXToSliderValue(
    touchX: Float,
    widthPx: Float,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
): Float {
    val start = valueRange.start
    val end = valueRange.endInclusive
    if (widthPx <= 0f || !touchX.isFinite() || end <= start) return start
    val fraction = (touchX / widthPx).coerceIn(0f, 1f)
    return (start + (end - start) * fraction).coerceIn(start, end)
}

@Composable
fun PodcastyTrackSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    onValueChangeCancelled: (() -> Unit)? = null,
    onInteractionChange: (Boolean) -> Unit = {},
    trackHeight: Dp = 3.dp,
    activeTrackColor: Color? = null,
    inactiveTrackColor: Color? = null,
    trackAlignment: Alignment = Alignment.Center,
) {
    val colors = LocalPodcastyColors.current
    var isInteracting by remember { mutableStateOf(false) }
    var dragPreviewValue by remember { mutableStateOf<Float?>(null) }

    val animatedTrackHeight by animateDpAsState(
        targetValue = if (isInteracting) trackHeight + 3.dp else trackHeight,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "podcasty-track-slider-height",
    )
    val animatedTrackScaleX by animateFloatAsState(
        targetValue = if (isInteracting) 1.03f else 1f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "podcasty-track-slider-scale-x",
    )
    val animatedTrackScaleY by animateFloatAsState(
        targetValue = if (isInteracting) 1.10f else 1f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "podcasty-track-slider-scale-y",
    )
    val filledTrackColor by animateColorAsState(
        targetValue = activeTrackColor ?: sliderFilledTrackColor(colors, isInteracting),
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "podcasty-track-slider-fill-colour",
    )

    val currentOnValueChange = rememberUpdatedState(onValueChange)
    val currentOnValueChangeFinished = rememberUpdatedState(onValueChangeFinished)
    val currentOnValueChangeCancelled = rememberUpdatedState(onValueChangeCancelled)
    val currentOnInteractionChange = rememberUpdatedState(onInteractionChange)

    val safeStart = valueRange.start
    val safeEnd = valueRange.endInclusive.coerceAtLeast(safeStart)
    val safeValue = if (value.isFinite()) value.coerceIn(safeStart, safeEnd) else safeStart
    val rangeSize = (safeEnd - safeStart).coerceAtLeast(0f)

    // Compute fraction directly without spawning an animateFloatAsState tween on every playback tick,
    // keeping scrubbing instantaneous and playback redraws strictly in the Canvas draw phase.
    val displayedFractionState = rememberUpdatedState(
        if (rangeSize > 0f) {
            val active = (dragPreviewValue ?: safeValue).coerceIn(safeStart, safeEnd)
            ((active - safeStart) / rangeSize).coerceIn(0f, 1f)
        } else {
            0f
        },
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(safeValue, safeStart..safeEnd, 0)
                if (enabled) {
                    setProgress { requestedValue ->
                        val clamped = if (requestedValue.isFinite()) {
                            requestedValue.coerceIn(safeStart, safeEnd)
                        } else {
                            safeStart
                        }
                        currentOnValueChange.value(clamped)
                        currentOnValueChangeFinished.value?.invoke()
                        true
                    }
                }
            }
            .pointerInput(enabled, safeStart, safeEnd) {
                if (!enabled) return@pointerInput
                val activeRange = safeStart..safeEnd
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val widthPx = size.width.toFloat()
                    if (widthPx <= 0f) return@awaitEachGesture

                    // Claim the gesture immediately so parent vertical-dismiss & horizontal-swipe
                    // handlers never steal slider taps or drags.
                    down.consume()
                    val initialValue = touchXToSliderValue(down.position.x, widthPx, activeRange)
                    isInteracting = true
                    dragPreviewValue = initialValue
                    currentOnInteractionChange.value(true)
                    currentOnValueChange.value(initialValue)

                    var latestValue = initialValue
                    var completedNormally = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) {
                                // Pointer disappeared unexpectedly -> treat as cancelled
                                break
                            }
                            if (!change.pressed) {
                                // Normal pointer UP release
                                val finalValue = touchXToSliderValue(change.position.x, size.width.toFloat(), activeRange)
                                latestValue = finalValue
                                dragPreviewValue = finalValue
                                change.consume()
                                completedNormally = true
                                break
                            }
                            if (change.isConsumed) {
                                // External cancellation (e.g., system gesture)
                                break
                            }
                            val movedValue = touchXToSliderValue(change.position.x, size.width.toFloat(), activeRange)
                            if (movedValue != latestValue) {
                                latestValue = movedValue
                                dragPreviewValue = movedValue
                                currentOnValueChange.value(movedValue)
                            }
                            change.consume()
                        }

                        if (completedNormally) {
                            currentOnValueChange.value(latestValue)
                            currentOnValueChangeFinished.value?.invoke()
                        } else {
                            currentOnValueChangeCancelled.value?.invoke()
                        }
                    } finally {
                        isInteracting = false
                        dragPreviewValue = null
                        currentOnInteractionChange.value(false)
                    }
                }
            },
        contentAlignment = trackAlignment,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(animatedTrackHeight)
                .graphicsLayer {
                    scaleX = animatedTrackScaleX
                    scaleY = animatedTrackScaleY
                }
                .clip(CircleShape)
                .background(inactiveTrackColor ?: colors.sliderInactive)
                .testTag("podcasty-track-slider-track"),
        ) {
            val fraction = displayedFractionState.value
            if (fraction > 0f) {
                drawTrack(filledTrackColor, fraction)
            }
        }
    }
}

internal fun sliderFilledTrackColor(
    colors: PodcastyColors,
    isInteracting: Boolean,
): Color = if (isInteracting) {
    colors.onPrimary
} else {
    lerp(colors.foregroundSubtle, colors.onPrimary, 0.5f)
}

private fun DrawScope.drawTrack(color: Color, fraction: Float) {
    val clamped = fraction.coerceIn(0f, 1f)
    val width = size.width * clamped
    if (width <= 0f) return
    val radius = size.height / 2f
    drawRoundRect(
        color = color,
        size = Size(width, size.height),
        cornerRadius = CornerRadius(radius, radius),
    )
}
