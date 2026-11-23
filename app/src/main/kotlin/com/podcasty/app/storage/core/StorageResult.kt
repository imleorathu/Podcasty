/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.storage.core

import android.util.Log

enum class ScanSessionStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    CANCELLED,
    FAILED,
    STORAGE_UNAVAILABLE,
}

enum class StorageErrorKind {
    PERMISSION_REVOKED,
    STORAGE_DISCONNECTED,
    READ_ONLY_PROVIDER,
    INSUFFICIENT_SPACE,
    IO_ERROR,
    CORRUPTED_MEDIA,
}

sealed interface StorageResult<out T> {
    data class Success<T>(val value: T) : StorageResult<T>
    data class Failure(
        val kind: StorageErrorKind,
        val message: String,
        val cause: Throwable? = null,
    ) : StorageResult<Nothing>
}

object StorageLog {
    private const val TAG = "PodcastyStorage"

    fun info(component: String, message: String) {
        runCatching { Log.i(TAG, "[$component] $message") }
    }

    fun warn(component: String, message: String, error: Throwable? = null) {
        runCatching {
            if (error != null) Log.w(TAG, "[$component] $message: ${error.localizedMessage ?: error.javaClass.simpleName}")
            else Log.w(TAG, "[$component] $message")
        }
    }

    fun error(component: String, message: String, error: Throwable? = null) {
        runCatching {
            if (error != null) Log.e(TAG, "[$component] $message", error)
            else Log.e(TAG, "[$component] $message")
        }
    }
}
