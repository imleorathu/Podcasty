/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.features.player

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.podcasty.app.ui.components.MaterialSymbol
import com.podcasty.app.ui.components.MaterialSymbols
import com.podcasty.app.ui.components.decodeArtworkBitmap
import com.podcasty.app.ui.theme.LocalPodcastyColors

private val FullScreenArtworkShape = RoundedCornerShape(16.dp)

internal data class FullScreenArtwork(
    val image: ImageBitmap,
    val dominant: Color,
    val palette: ArtworkPalette? = null,
)

@Composable
internal fun FullScreenPlayerBackground(
    artwork: FullScreenArtwork?,
    modifier: Modifier,
    showArtwork: Boolean = false,
    livingArtworkBackground: Boolean = true,
    isPlaying: Boolean = false,
) {
    val colors = LocalPodcastyColors.current
    val dominantColor by animateColorAsState(
        targetValue = artwork?.dominant ?: colors.playerBackdrop,
        animationSpec = tween(280, easing = FastOutSlowInEasing),
        label = "full-screen-background-colour",
    )
    val artworkAlpha by animateFloatAsState(
        targetValue = if (showArtwork) 1f else 0f,
        animationSpec = tween(220),
        label = "square-background-artwork-alpha",
    )
    var livingFailed by remember(livingArtworkBackground) { mutableStateOf(false) }

    Box(modifier.background(colors.playerBackdrop)) {
        if (artworkAlpha < 1f || !showArtwork) {
            Box(Modifier.fillMaxSize().background(dominantColor.copy(alpha = 0.66f)))
            if (livingArtworkBackground && !livingFailed) {
                LivingArtworkBackground(
                    palette = artwork?.palette?.shaderColors(),
                    isPlaying = isPlaying,
                    onFailure = { livingFailed = true },
                )
            }
        }
        if (artworkAlpha > 0f || showArtwork) {
            Box(Modifier.fillMaxSize().alpha(artworkAlpha)) {
                ArtworkLayer(artwork, 1f)
            }
        }
        Box(Modifier.fillMaxSize().background(colors.playerBackdrop.copy(alpha = 0.24f + 0.36f * artworkAlpha)))
    }
}

@Composable
internal fun FullScreenPlayerArtwork(
    artwork: FullScreenArtwork?,
    modifier: Modifier,
    livingArtworkBackground: Boolean,
) {
    val colors = LocalPodcastyColors.current
    Box(
        modifier
            .shadow(
                elevation = if (livingArtworkBackground) 24.dp else 0.dp,
                shape = FullScreenArtworkShape,
                clip = false,
                ambientColor = colors.playerBackdrop.copy(alpha = 0.60f),
                spotColor = colors.playerBackdrop.copy(alpha = 0.85f),
            )
            .clip(FullScreenArtworkShape)
            .background(colors.glassElevated)
            .border(1.dp, colors.borderGlass, FullScreenArtworkShape),
        contentAlignment = Alignment.Center,
    ) {
        ArtworkLayer(artwork, 1f)
    }
}

@Composable
private fun ArtworkLayer(artwork: FullScreenArtwork?, alpha: Float) {
    val colors = LocalPodcastyColors.current
    if (artwork != null) {
        Image(artwork.image, null, Modifier.fillMaxSize().alpha(alpha), contentScale = ContentScale.Crop)
    } else {
        Box(Modifier.fillMaxSize().alpha(alpha), contentAlignment = Alignment.Center) {
            MaterialSymbol(MaterialSymbols.Podcasts, null, tint = colors.textMuted, size = 64.dp)
        }
    }
}

@Composable
internal fun rememberFullscreenArtwork(artworkPath: String?): FullScreenArtwork? {
    val context = LocalContext.current
    var artwork by remember { mutableStateOf<FullScreenArtwork?>(null) }
    LaunchedEffect(artworkPath) {
        if (artworkPath.isNullOrBlank()) {
            artwork = null
            return@LaunchedEffect
        }
        artwork = withContext(Dispatchers.IO) {
            val bitmap = decodeArtworkBitmap(context, artworkPath, 1080, Bitmap.Config.ARGB_8888)
                ?: return@withContext null
            val thumbnail = Bitmap.createScaledBitmap(bitmap, 64, 64, false)
            val pixels = IntArray(64 * 64)
            thumbnail.getPixels(pixels, 0, 64, 0, 0, 64, 64)
            if (thumbnail !== bitmap) thumbnail.recycle()
            val backdropSample = Bitmap.createScaledBitmap(bitmap, 24, 24, false)
            val backdropPixels = IntArray(24 * 24)
            backdropSample.getPixels(backdropPixels, 0, 24, 0, 0, 24, 24)
            if (backdropSample !== bitmap) backdropSample.recycle()
            FullScreenArtwork(
                image = bitmap.asImageBitmap(),
                dominant = dominantColor(bitmap),
                palette = extractArtworkPalette(pixels, backdropPixels),
            )
        }
    }
    return artwork
}

private fun dominantColor(bitmap: Bitmap): Color {
    val sample = Bitmap.createScaledBitmap(bitmap, 24, 24, true)
    val pixels = IntArray(24 * 24)
    sample.getPixels(pixels, 0, 24, 0, 0, 24, 24)
    if (sample !== bitmap) sample.recycle()
    val opaque = pixels.filter { android.graphics.Color.alpha(it) > 32 }
    if (opaque.isEmpty()) return Color.Transparent
    return Color(
        opaque.sumOf { android.graphics.Color.red(it) } / opaque.size,
        opaque.sumOf { android.graphics.Color.green(it) } / opaque.size,
        opaque.sumOf { android.graphics.Color.blue(it) } / opaque.size,
    )
}
