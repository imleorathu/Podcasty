/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.features.player

import android.animation.ValueAnimator
import android.database.ContentObserver
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Composable
internal fun LivingArtworkBackground(
    palette: FloatArray?,
    isPlaying: Boolean,
    onFailure: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    var motionEnabled by remember { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }

    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                motionEnabled = ValueAnimator.areAnimatorsEnabled()
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }

    val renderer = remember { LivingArtworkRenderer(onFrame = { image = it }, onFailure = onFailure) }
    DisposableEffect(renderer) { onDispose { renderer.close() } }
    LaunchedEffect(palette?.toList(), isPlaying, lifecycleState, motionEnabled, size) {
        renderer.update(
            colors = palette,
            isPlaying = isPlaying,
            started = lifecycleState.isAtLeast(Lifecycle.State.STARTED),
            animations = motionEnabled,
            size = size,
        )
    }

    Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged { size = it }.testTag("living_artwork_background")) {
        image?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = 1.2f; scaleY = 1.2f }
                    .blur(32.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded),
            )
            ArtworkDither()
        }
    }
}

private class LivingArtworkRenderer(
    private val onFrame: (ImageBitmap) -> Unit,
    private val onFailure: () -> Unit,
) {
    private val thread = HandlerThread("PodcastyLivingArtwork").apply { start() }
    private val handler = Handler(thread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var surface = EGL14.EGL_NO_SURFACE
    private var config: EGLConfig? = null
    private var program = 0
    private var width = 0
    private var height = 0
    private var pixels: ByteBuffer? = null
    // Ping-pong bitmaps eliminate 30fps per-frame ARGB_8888 Bitmap allocation (~7 MB/s GC churn)
    private var bitmapPool: Array<Bitmap?> = arrayOfNulls(2)
    private var imagePool: Array<ImageBitmap?> = arrayOfNulls(2)
    private var poolIndex = 0
    private var uTimeLoc = -1
    private var uResolutionLoc = -1
    private val uColorLocs = IntArray(4) { -1 }
    private var positionLoc = -1
    private val paletteAnimation = ArtworkPaletteAnimation()
    private var playing = false
    @Volatile private var active = false
    private var motion = true
    private var failed = false
    @Volatile private var closed = false
    @Volatile private var pendingImage: ImageBitmap? = null
    private val publishFrame = Runnable { if (!closed && active) pendingImage?.let(onFrame) }
    private var lastFrame = 0L
    private var time = 0f
    private val vertices = ByteBuffer.allocateDirect(6 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f))
        position(0)
    }

    private val frame = object : Runnable {
        override fun run() {
            if (!active || failed || program == 0) return
            try {
                val now = System.nanoTime()
                val delta = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1_000_000_000f).coerceAtMost(0.1f)
                lastFrame = now
                if (motion) time += delta * if (playing) 1f else 0.1f
                val current = paletteAnimation.frame(delta, motion) ?: return
                GLES20.glViewport(0, 0, width, height)
                GLES20.glUseProgram(program)
                GLES20.glUniform1f(uTimeLoc, time)
                GLES20.glUniform2f(uResolutionLoc, width.toFloat(), height.toFloat())
                for (index in 0 until 4) {
                    GLES20.glUniform3fv(uColorLocs[index], 1, current, index * 3)
                }
                GLES20.glEnableVertexAttribArray(positionLoc)
                GLES20.glVertexAttribPointer(positionLoc, 2, GLES20.GL_FLOAT, false, 0, vertices)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
                val buffer = checkNotNull(pixels)
                buffer.rewind()
                GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
                check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "Cannot read living artwork frame" }
                buffer.rewind()
                val idx = poolIndex
                poolIndex = 1 - idx
                val bitmap = bitmapPool[idx] ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    bitmapPool[idx] = it
                    imagePool[idx] = it.asImageBitmap()
                }
                bitmap.copyPixelsFromBuffer(buffer)
                pendingImage = imagePool[idx] ?: bitmap.asImageBitmap().also { imagePool[idx] = it }
                mainHandler.removeCallbacks(publishFrame)
                mainHandler.post(publishFrame)
                // When paused (0.1x drift), 100ms cadence is visually identical while cutting GPU/CPU drain 3x
                if (motion) handler.postDelayed(this, if (playing) 34L else 100L)
            } catch (error: Exception) {
                fail(error)
            }
        }
    }

    fun update(colors: FloatArray?, isPlaying: Boolean, started: Boolean, animations: Boolean, size: IntSize) {
        if (closed) return
        handler.post {
            if (failed || closed) return@post
            paletteAnimation.update(colors)
            playing = isPlaying
            active = started
            motion = animations
            try {
                if (active && size.width > 0 && size.height > 0) {
                    resize((size.width * 0.15f).toInt().coerceAtLeast(1), (size.height * 0.15f).toInt().coerceAtLeast(1))
                }
                schedule()
            } catch (error: Exception) {
                fail(error)
            }
        }
    }

    private fun schedule() {
        if (active && !failed && program != 0) {
            if (!handler.hasCallbacks(frame)) handler.post(frame)
        } else {
            handler.removeCallbacks(frame)
            lastFrame = 0L
        }
    }

    private fun resize(newWidth: Int, newHeight: Int) {
        if (width == newWidth && height == newHeight && program != 0) return
        if (display == EGL14.EGL_NO_DISPLAY) {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(
                EGL14.eglChooseConfig(
                    display,
                    intArrayOf(
                        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                        EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                        EGL14.EGL_NONE,
                    ),
                    0, configs, 0, 1, count, 0,
                ) && count[0] > 0,
            )
            config = configs[0]
            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            check(context != EGL14.EGL_NO_CONTEXT)
        }
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
        surface = EGL14.eglCreatePbufferSurface(
            display, config,
            intArrayOf(EGL14.EGL_WIDTH, newWidth, EGL14.EGL_HEIGHT, newHeight, EGL14.EGL_NONE), 0,
        )
        check(surface != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, surface, surface, context))
        width = newWidth
        height = newHeight
        pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        for (i in bitmapPool.indices) {
            bitmapPool[i] = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            imagePool[i] = bitmapPool[i]?.asImageBitmap()
        }
        if (program == 0) {
            val vertex = compileShader(GLES20.GL_VERTEX_SHADER, "attribute vec2 position; void main() { gl_Position = vec4(position, 0.0, 1.0); }")
            try {
                val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, LivingArtworkFragmentShader)
                try {
                    program = GLES20.glCreateProgram()
                    GLES20.glAttachShader(program, vertex)
                    GLES20.glAttachShader(program, fragment)
                    GLES20.glLinkProgram(program)
                    val linked = IntArray(1)
                    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
                    check(linked[0] != 0) { GLES20.glGetProgramInfoLog(program) }
                    uTimeLoc = GLES20.glGetUniformLocation(program, "uTime")
                    uResolutionLoc = GLES20.glGetUniformLocation(program, "uResolution")
                    uColorLocs[0] = GLES20.glGetUniformLocation(program, "uColor1")
                    uColorLocs[1] = GLES20.glGetUniformLocation(program, "uColor2")
                    uColorLocs[2] = GLES20.glGetUniformLocation(program, "uColor3")
                    uColorLocs[3] = GLES20.glGetUniformLocation(program, "uBase")
                    positionLoc = GLES20.glGetAttribLocation(program, "position")
                } finally {
                    GLES20.glDeleteShader(fragment)
                }
            } finally {
                GLES20.glDeleteShader(vertex)
            }
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val message = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error(message)
        }
        return shader
    }

    private fun fail(error: Exception) {
        android.util.Log.w("PodcastyLivingArtwork", "Falling back to solid background", error)
        failed = true
        release()
        mainHandler.post { if (!closed) onFailure() }
    }

    private fun release() {
        handler.removeCallbacks(frame)
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (program != 0) GLES20.glDeleteProgram(program)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            EGL14.eglReleaseThread()
        }
        program = 0
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        surface = EGL14.EGL_NO_SURFACE
        pixels = null
        mainHandler.removeCallbacks(publishFrame)
        pendingImage = null
    }

    fun close() {
        if (closed) return
        closed = true
        handler.post { release(); thread.quitSafely() }
    }
}
