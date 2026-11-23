/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.features.player

import androidx.compose.runtime.Composable

internal enum class PlayerLayoutMode { Phone, Square, Wide }

internal fun resolvePlayerLayout(widthDp: Float, heightDp: Float): PlayerLayoutMode {
    if (!widthDp.isFinite() || !heightDp.isFinite() || widthDp <= 0f || heightDp <= 0f) return PlayerLayoutMode.Phone
    val ratio = widthDp / heightDp
    return when {
        widthDp < 360f -> PlayerLayoutMode.Square
        widthDp >= 840f && heightDp >= 400f && widthDp > heightDp -> PlayerLayoutMode.Wide
        widthDp > heightDp -> PlayerLayoutMode.Square
        widthDp >= 600f && heightDp >= 480f -> PlayerLayoutMode.Phone
        ratio in 0.8f..1.25f -> PlayerLayoutMode.Square
        else -> PlayerLayoutMode.Phone
    }
}

internal fun isLargeWidePlayer(widthDp: Float, heightDp: Float): Boolean =
    widthDp >= 1280f && heightDp >= 720f && resolvePlayerLayout(widthDp, heightDp) == PlayerLayoutMode.Wide

internal fun widePlayerExtraVerticalPadding(widthDp: Float, heightDp: Float, insetDp: Float): Float =
    if (isLargeWidePlayer(widthDp, heightDp)) (32f - insetDp).coerceAtLeast(0f) else 0f

internal fun phonePlayerHorizontalPadding(widthDp: Float, heightDp: Float): Float {
    if (!widthDp.isFinite() || !heightDp.isFinite() || widthDp < 600f || heightDp <= 0f) return 20f
    return if (widthDp / heightDp >= 0.6f) widthDp * 0.1f else 20f
}

internal fun usesCompactPlayerControls(widthDp: Float, heightDp: Float): Boolean {
    if (!widthDp.isFinite() || !heightDp.isFinite() || widthDp <= 0f || heightDp <= 0f) return false
    return when (resolvePlayerLayout(widthDp, heightDp)) {
        PlayerLayoutMode.Phone -> widthDp / heightDp >= 0.6f
        PlayerLayoutMode.Wide -> (widthDp / 2f) / heightDp >= 0.6f
        PlayerLayoutMode.Square -> false
    }
}

@Composable
internal fun AdaptivePlayerContent(
    mode: PlayerLayoutMode,
    wide: @Composable () -> Unit,
    square: @Composable () -> Unit,
    phone: @Composable () -> Unit,
) {
    when (mode) {
        PlayerLayoutMode.Phone -> phone()
        PlayerLayoutMode.Square -> square()
        PlayerLayoutMode.Wide -> wide()
    }
}
