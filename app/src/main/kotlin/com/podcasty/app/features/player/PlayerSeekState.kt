/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlin.math.roundToLong

/**
 * Separates user-driven scrub/seek interaction state from Media3 playback progress ticks
 * so background position updates never overwrite an active drag or cause thumb jumps.
 */
internal class PlayerSeekState(
    val isScrubbing: MutableState<Boolean>,
    val pendingFraction: MutableState<Float?>,
    val pendingTargetMs: MutableState<Long?>,
    val pendingGeneration: MutableState<Long>,
    val awaitingConfirmation: MutableState<Boolean>,
) {
    fun updateScrub(fraction: Float) {
        val clamped = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
        isScrubbing.value = true
        awaitingConfirmation.value = false
        pendingFraction.value = clamped
    }

    fun commitScrub(totalDurationMs: Long): Long? {
        val fraction = pendingFraction.value ?: run {
            isScrubbing.value = false
            return null
        }
        val safeDuration = totalDurationMs.coerceAtLeast(0L)
        if (safeDuration <= 0L) {
            reset()
            return null
        }
        val targetMs = (safeDuration.toDouble() * fraction.coerceIn(0f, 1f)).roundToLong()
            .coerceIn(0L, safeDuration)
        isScrubbing.value = false
        pendingTargetMs.value = targetMs
        pendingGeneration.value += 1L
        awaitingConfirmation.value = true
        return targetMs
    }

    fun cancelScrub() {
        isScrubbing.value = false
        if (!awaitingConfirmation.value) {
            pendingFraction.value = null
            pendingTargetMs.value = null
        }
    }

    fun confirmSeek() {
        if (!isScrubbing.value) {
            pendingFraction.value = null
            pendingTargetMs.value = null
            awaitingConfirmation.value = false
        }
    }

    fun reset() {
        isScrubbing.value = false
        pendingFraction.value = null
        pendingTargetMs.value = null
        awaitingConfirmation.value = false
    }
}

@Composable
internal fun rememberPlayerSeekState(episodeId: String): PlayerSeekState {
    val isScrubbing = remember(episodeId) { mutableStateOf(false) }
    val pendingFraction = remember(episodeId) { mutableStateOf<Float?>(null) }
    val pendingTargetMs = remember(episodeId) { mutableStateOf<Long?>(null) }
    val pendingGeneration = remember(episodeId) { mutableLongStateOf(0L) }
    val awaitingConfirmation = remember(episodeId) { mutableStateOf(false) }
    return remember(episodeId) {
        PlayerSeekState(
            isScrubbing = isScrubbing,
            pendingFraction = pendingFraction,
            pendingTargetMs = pendingTargetMs,
            pendingGeneration = pendingGeneration,
            awaitingConfirmation = awaitingConfirmation,
        )
    }
}
