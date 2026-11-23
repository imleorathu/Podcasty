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
import com.podcasty.app.transcript.CaptionSyncSnapshot

internal data class LockScreenLyricDisplayContent(
    val title: String,
    val collapsedLine: String,
    val expandedBody: String,
    val summaryText: String?,
    val renderKey: String,
)

/**
 * Dedicated companion notification controller for Lock Screen Synchronized Lyrics.
 *
 * Design:
 * - Preserves Media3's MediaStyle notification and transport controls (no duplicate media buttons).
 * - Displays song title, artist name, current synchronized lyric ("♪ ..."), and optional next lyric line.
 * - Uses a silent IMPORTANCE_LOW channel with VISIBILITY_PUBLIC so lyrics appear on the Android
 *   lock screen cleanly without sound, vibration, or heads-up alerts.
 * - Keeps the current lyric visible when paused, updates immediately on seek/resume/track change,
 *   and dismisses cleanly when playback stops or when the track has no synchronized lyrics.
 * - Updates the single fixed notification ID in-place ONLY when the displayed lyric/state actually changes.
 */
class LockScreenLyricsNotificationController(private val context: Context) {
    private var lastRenderedKey: String? = null

    init {
        ensureChannel()
    }

    fun update(
        episodeId: String,
        songTitle: String,
        artistName: String,
        albumTitle: String,
        snapshot: CaptionSyncSnapshot,
        isPlaying: Boolean,
        isPaused: Boolean,
        enabled: Boolean,
    ) {
        if (!enabled || (!isPlaying && !isPaused)) {
            dismiss()
            return
        }
        if (snapshot.episodeId != episodeId || !snapshot.isTimed) {
            dismiss()
            return
        }
        val currentCue = snapshot.activeCue
        if (currentCue == null || currentCue.text.isBlank()) {
            dismiss()
            return
        }
        if (!canPostNotifications()) {
            return
        }

        val content = formatDisplayContent(
            episodeId = episodeId,
            songTitle = songTitle,
            artistName = artistName,
            albumTitle = albumTitle,
            currentLyric = currentCue.text,
            nextLyric = snapshot.nextCue?.text,
            isPaused = isPaused,
        )
        if (content.renderKey == lastRenderedKey) return
        lastRenderedKey = content.renderKey

        val contentIntent = PendingIntent.getActivity(
            context,
            LyricsRequestCode,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val style = NotificationCompat.BigTextStyle()
            .setBigContentTitle(content.title)
            .bigText(content.expandedBody)
        content.summaryText?.let { style.setSummaryText(it) }

        val notification = NotificationCompat.Builder(context, ChannelId)
            .setSmallIcon(R.drawable.ic_lyrics_note)
            .setContentTitle(content.title)
            .setContentText(content.collapsedLine)
            .setSubText(content.summaryText)
            .setStyle(style)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setGroup(LyricsGroupKey)
            .setSortKey("zzzz_lyrics")
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            NotificationManagerCompat.from(context).notify(LyricsNotificationId, notification)
        } catch (_: SecurityException) {
            // Permission revoked concurrently by user
        } catch (_: Throwable) {
            // Ignore notification manager IPC failures
        }
    }

    fun dismiss() {
        if (lastRenderedKey == null) return
        lastRenderedKey = null
        runCatching {
            NotificationManagerCompat.from(context).cancel(LyricsNotificationId)
        }
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }.getOrDefault(true)
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(ChannelId) != null) return
        val channel = NotificationChannel(
            ChannelId,
            context.getString(R.string.lockscreen_lyrics_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.lockscreen_lyrics_channel_desc)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ChannelId = "podcasty_lockscreen_lyrics"
        const val LyricsNotificationId = 4209
        private const val LyricsRequestCode = 4210
        private const val LyricsGroupKey = "podcasty_lockscreen_lyrics_companion"

        internal fun formatDisplayContent(
            episodeId: String,
            songTitle: String,
            artistName: String,
            albumTitle: String,
            currentLyric: String,
            nextLyric: String?,
            isPaused: Boolean,
        ): LockScreenLyricDisplayContent {
            val cleanSong = songTitle.trim().ifBlank { "Now Playing" }
            val resolvedArtist = artistName.trim()
                .takeIf { it.isNotBlank() && !it.equals("Unknown Artist", ignoreCase = true) }
                ?: albumTitle.trim().takeIf {
                    it.isNotBlank() && !it.equals("Local Audio", ignoreCase = true)
                }
            val title = if (resolvedArtist != null && !resolvedArtist.equals(cleanSong, ignoreCase = true)) {
                "$cleanSong · $resolvedArtist"
            } else {
                cleanSong
            }

            val cleanCurrent = currentLyric.trim()
            val cleanNext = nextLyric?.trim()?.takeIf { it.isNotBlank() && it != cleanCurrent }
            val collapsedLine = "♪ $cleanCurrent"
            val expandedBody = if (cleanNext != null) {
                "♪ $cleanCurrent\n$cleanNext"
            } else {
                collapsedLine
            }
            val summaryText = if (isPaused) "Paused" else null
            val renderKey = "$episodeId|$title|$collapsedLine|${cleanNext.orEmpty()}|$isPaused"
            return LockScreenLyricDisplayContent(
                title = title,
                collapsedLine = collapsedLine,
                expandedBody = expandedBody,
                summaryText = summaryText,
                renderKey = renderKey,
            )
        }
    }
}
