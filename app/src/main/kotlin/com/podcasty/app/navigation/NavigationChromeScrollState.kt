/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.navigation

import kotlin.math.abs

internal class NavigationChromeScrollAccumulator {
    var compact: Boolean = false
        private set
    private var accumulatedDistancePx: Float = 0f

    fun update(delta: ContentScrollDelta, thresholdPx: Float): Boolean {
        val targetCompact = delta.direction == ContentScrollDirection.Up
        if (targetCompact == compact) {
            accumulatedDistancePx = 0f
            return false
        }
        val signedDistance = if (targetCompact) -delta.distancePx else delta.distancePx
        accumulatedDistancePx = if (
            accumulatedDistancePx != 0f && accumulatedDistancePx * signedDistance < 0f
        ) {
            signedDistance
        } else {
            accumulatedDistancePx + signedDistance
        }
        if (abs(accumulatedDistancePx) < thresholdPx) return false
        compact = targetCompact
        accumulatedDistancePx = 0f
        return true
    }

    fun reset() {
        compact = false
        accumulatedDistancePx = 0f
    }
}
