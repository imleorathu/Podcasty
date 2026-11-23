package com.podcasty.app.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatPlaybackTime(timeMs: Long): String {
    val totalSeconds = (timeMs.coerceAtLeast(0L) / 1000L).toInt()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
    } else {
        "%d:%02d".format(Locale.US, minutes, seconds)
    }
}

fun formatRemainingTime(positionMs: Long, durationMs: Long): String {
    if (durationMs <= 0L) return "--:--"
    val remaining = (durationMs - positionMs).coerceAtLeast(0L)
    return "-${formatPlaybackTime(remaining)}"
}

fun formatCompactDuration(durationMs: Long): String {
    val totalMinutes = ((durationMs.coerceAtLeast(0L) + 30_000L) / 60_000L).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0 -> "${hours}h"
        totalMinutes > 0 -> "${totalMinutes} min"
        else -> "${(durationMs / 1000L).coerceAtLeast(1L)}s"
    }
}

fun formatSpeedLabel(speed: Float): String {
    val rounded = (speed * 100).toInt()
    return when {
        rounded % 100 == 0 -> "${rounded / 100}.0x"
        rounded % 10 == 0 -> "%.1fx".format(Locale.US, speed)
        else -> "%.2fx".format(Locale.US, speed)
    }
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var size = bytes.toDouble()
    var unit = 0
    while (size >= 1024.0 && unit < units.lastIndex) {
        size /= 1024.0
        unit++
    }
    return "%.1f %s".format(Locale.US, size, units[unit])
}

fun formatShortDate(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    return SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(epochMs))
}

fun formatTimerCountdown(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val mins = safe / 60
    val secs = safe % 60
    return "%d:%02d".format(Locale.US, mins, secs)
}

fun libraryAlphabeticalIndexLabel(value: String): String? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null
    val first = trimmed.first().uppercaseChar()
    return if (first in 'A'..'Z') first.toString() else "#"
}
