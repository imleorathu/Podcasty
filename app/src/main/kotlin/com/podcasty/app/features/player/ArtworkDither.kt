/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.features.player

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.random.Random

@Composable
internal fun ArtworkDither() {
    val brush = remember {
        val bitmap = Bitmap.createBitmap(artworkDitherPixels(), 128, 128, Bitmap.Config.ARGB_8888)
        ShaderBrush(ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
    }
    Canvas(Modifier.fillMaxSize()) {
        drawRect(brush, alpha = 2f / 255f)
    }
}

internal fun artworkDitherPixels(): IntArray {
    val random = Random(0)
    return IntArray(128 * 128) {
        val gray = random.nextInt(256)
        (0xff shl 24) or (gray shl 16) or (gray shl 8) or gray
    }
}
