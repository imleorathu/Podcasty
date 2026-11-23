/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.player

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.podcasty.app.R
import com.podcasty.app.app.MainActivity
import com.podcasty.app.transcript.TranscriptCue

/**
 * Native Android lock-screen & AOD-compatible caption notification controller.
 *
 * Design principles:
 * - Strictly OFF by default; posts only when the user enables Lock Screen Captions or
 *   Transcript Notification in Settings.
 * - Uses Android's native notification pipeline (VISIBILITY_PUBLIC when lock-screen captions
 *   are enabled, VISIBILITY_PRIVATE otherwise) — respects lock-screen privacy & OEM AOD settings.
 * - Never creates a fake AOD overlay Activity or keeps the screen awake.
 * - Updates a single fixed notification ID in-place ONLY when the active cue changes.
 */
class CaptionNotificationController(private val context: Context) {
    private var lastPostedKey: String? = null

    init {
        ensureChannel()
    }

    fun update(
        episodeTitle: String,
        podcastTitle: String,
        cue: TranscriptCue?,
        isPlaying: Boolean,
        showOnLockScreen: Boolean,
        showTranscriptNotification: Boolean,
    ) {
        val enabled = showOnLockScreen || showTranscriptNotification
        if (!enabled || !isPlaying || cue == null || cue.text.isBlank()) {
            dismiss()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val text = if (!cue.speaker.isNullOrBlank()) "${cue.speaker}: ${cue.text}" else cue.text
        val key = "$episodeTitle|${cue.id}|$text|$showOnLockScreen"
        if (key == lastPostedKey) return
        lastPostedKey = key

        val contentIntent = PendingIntent.getActivity(
            context,
            CaptionRequestCode,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val visibility = if (showOnLockScreen) {
            NotificationCompat.VISIBILITY_PUBLIC
        } else {
            NotificationCompat.VISIBILITY_PRIVATE
        }

        val notification = NotificationCompat.Builder(context, ChannelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("$episodeTitle · $podcastTitle")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(visibility)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(CaptionNotificationId, notification)
        }
    }

    fun dismiss() {
        if (lastPostedKey == null) return
        lastPostedKey = null
        runCatching {
            NotificationManagerCompat.from(context).cancel(CaptionNotificationId)
        }
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(ChannelId) != null) return
        val channel = NotificationChannel(
            ChannelId,
            context.getString(R.string.caption_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.caption_notification_channel_desc)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ChannelId = "podcasty_live_captions"
        const val CaptionNotificationId = 4202
        private const val CaptionRequestCode = 4203
    }
}
