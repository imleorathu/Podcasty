/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.ui.components

import android.animation.ValueAnimator
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun PodcastyMarqueeText(
    text: String,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
    fade: Boolean = false,
    continuous: Boolean = true,
) {
    key(text, continuous) {
        var containerWidth by remember { mutableIntStateOf(0) }
        var textWidth by remember { mutableIntStateOf(0) }
        val overflows = textWidth > containerWidth && containerWidth > 0
        val iterations = when {
            !ValueAnimator.areAnimatorsEnabled() -> 0
            continuous -> Int.MAX_VALUE
            else -> 1
        }
        Text(
            text = text,
            modifier = modifier
                .fillMaxWidth()
                .onSizeChanged { containerWidth = it.width }
                .graphicsLayer {
                    compositingStrategy = if (fade && overflows) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                }
                .drawWithCache {
                    val mask = Brush.horizontalGradient(
                        colors = listOf(color.copy(alpha = 1f), color.copy(alpha = 0f)),
                        startX = (size.width - 16.dp.toPx()).coerceAtLeast(0f),
                        endX = size.width,
                    )
                    onDrawWithContent {
                        drawContent()
                        if (fade && overflows) drawRect(mask, blendMode = BlendMode.DstIn)
                    }
                }
                .basicMarquee(
                    iterations = iterations,
                    repeatDelayMillis = 3_000,
                    initialDelayMillis = 3_000,
                    spacing = MarqueeSpacing(32.dp),
                    velocity = 20.dp,
                ),
            color = color,
            style = style,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            onTextLayout = { textWidth = it.size.width },
        )
    }
}
