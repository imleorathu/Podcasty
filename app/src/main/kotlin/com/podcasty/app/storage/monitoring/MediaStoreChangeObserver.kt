/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.storage.monitoring

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.podcasty.app.storage.core.StorageLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Lightweight, debounced ContentObserver for MediaStore audio changes.
 * Avoids polling and only notifies [onAudioChanged] when MediaStore audio rows actually change.
 */
class MediaStoreChangeObserver(
    private val context: Context,
    private val onAudioChanged: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var debounceJob: Job? = null
    private var registered = false

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            debounceJob?.cancel()
            debounceJob = scope.launch {
                delay(DebounceMs)
                onAudioChanged()
            }
        }
    }

    fun start() {
        if (registered) return
        runCatching {
            context.contentResolver.registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                true,
                observer,
            )
            registered = true
        }.onFailure { err ->
            StorageLog.warn("MediaStoreObserver", "Could not register MediaStore observer", err)
        }
    }

    fun stop() {
        if (!registered) return
        debounceJob?.cancel()
        runCatching { context.contentResolver.unregisterContentObserver(observer) }
        registered = false
    }

    fun currentAudioGeneration(): Long = runCatching {
        MediaStore.getGeneration(context, MediaStore.VOLUME_EXTERNAL)
    }.getOrDefault(0L)

    companion object {
        private const val DebounceMs = 3_000L
    }
}
