/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.podcasty.app.ui.theme.LocalPodcastyColors

@Composable
fun PodcastyPlayingIndicator(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPodcastyColors.current
    val playbackProgress by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "playing-indicator-playback-progress",
    )
    val shouldAnimate = isPlaying || playbackProgress > 0.01f
    val scales = if (shouldAnimate) {
        val transition = rememberInfiniteTransition(label = "playing-indicator")
        listOf(
            transition.animateScale(0.3f, 0.8f, 800, "playing-indicator-first"),
            transition.animateScale(1f, 0.4f, 600, "playing-indicator-second"),
            transition.animateScale(0.6f, 0.9f, 700, "playing-indicator-third"),
        )
    } else {
        null
    }

    Row(
        modifier = modifier.testTag("playing_indicator").height(18.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (i in 0 until 3) {
            val scaleState = scales?.get(i)
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .graphicsLayer {
                        val s = scaleState?.value ?: 0.3f
                        scaleY = 0.3f + (s - 0.3f) * playbackProgress
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .background(colors.onPrimary, RoundedCornerShape(2.dp)),
            )
        }
    }
}

@Composable
private fun InfiniteTransition.animateScale(
    start: Float,
    middle: Float,
    duration: Int,
    label: String,
) = animateFloat(
    initialValue = start,
    targetValue = middle,
    animationSpec = infiniteRepeatable(
        animation = keyframes {
            durationMillis = duration
            start at 0 using FastOutSlowInEasing
            middle at duration / 2 using FastOutSlowInEasing
            start at duration
        },
        repeatMode = RepeatMode.Restart,
    ),
    label = label,
)
