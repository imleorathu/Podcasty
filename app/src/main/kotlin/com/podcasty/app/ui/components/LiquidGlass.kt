/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.blurEffect
import dev.chrisbanes.haze.hazeEffect
import com.podcasty.app.ui.theme.PodcastyColors

fun Modifier.liquidGlassBackground(
    hazeState: HazeState?,
    colors: PodcastyColors,
    hazeBlurRadius: Dp = 16.dp,
    glassTint: Color? = null,
): Modifier = if (hazeState == null) {
    background(colors.glassOpaque)
} else {
    hazeEffect(hazeState) {
        inputScale = HazeInputScale.Auto
        blurEffect {
            blurRadius = hazeBlurRadius
            colorEffects = listOf(HazeColorEffect.tint(glassTint ?: colors.glass))
        }
    }.background(glassTint ?: colors.glass)
}
