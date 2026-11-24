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
import android.provider.Settings
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
 * - Preserves Media3's native MediaStyle notification and transport controls (no duplicate media controls).
 * - Uses a silent IMPORTANCE_DEFAULT channel (sound = null, vibration = false, badge = false,
 *   onlyAlertOnce = true) so Android 15 / Xiaomi HyperOS 2.0 surfaces synchronized lyrics directly
 *   on the lock screen without sound, vibration, or intrusive heads-up popups.
 * - Supports Compact presentation (active line) and Expanded presentation (previous, active "♪ ...",
 *   optional translation, and next line via BigTextStyle).
 * - Deep-links directly into Podcasty's Full-Screen Lyrics Viewer via a secure PendingIntent
 *   (never bypasses keyguard or uses overlays).
 * - Respects Android lock-screen privacy settings (VISIBILITY_PUBLIC only when private notifications
 *   on the lock screen are permitted by the user; falls back to VISIBILITY_PRIVATE otherwise).
 * - Gates IPC updates while the screen is off to save battery and refreshes immediately on screen-on.
 */
class LockScreenLyricsNotificationController(private val context: Context) {
    private var lastRenderedKey: String? = null
    private var skippedWhileScreenOff: Boolean = false

    init {
        ensureChannel()
    }

    fun onScreenInteractiveChanged(interactive: Boolean) {
        if (interactive && skippedWhileScreenOff) {
            // Force next update() to push the exact current line immediately upon waking lock screen
            lastRenderedKey = null
            skippedWhileScreenOff = false
        }
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
        expandedMode: Boolean = true,
        showTranslation: Boolean = true,
        isScreenInteractive: Boolean = true,
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
            // During instrumental intro before first lyric line, keep notification dismissed cleanly
            dismiss()
            return
        }
        if (!isScreenInteractive) {
            // Avoid waking NotificationManagerService IPC while screen is off in pocket
            skippedWhileScreenOff = true
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
            previousLyric = snapshot.previousCue?.text,
            translationLyric = if (showTranslation) currentCue.translation else null,
            expandedMode = expandedMode,
        )
        if (content.renderKey == lastRenderedKey) return
        lastRenderedKey = content.renderKey
        skippedWhileScreenOff = false

        val openLyricsIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.ExtraOpenFullScreenLyrics, true)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            LyricsRequestCode,
            openLyricsIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val lockScreenVisibility = resolveLockScreenVisibility()
        val builder = NotificationCompat.Builder(context, ChannelId)
            .setSmallIcon(R.drawable.ic_lyrics_note)
            .setContentTitle(content.title)
            .setContentText(content.collapsedLine)
            .setSubText(content.summaryText)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setGroup(LyricsGroupKey)
            .setSortKey("zzzz_lyrics")
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(lockScreenVisibility)

        if (expandedMode) {
            val style = NotificationCompat.BigTextStyle()
                .setBigContentTitle(content.title)
                .bigText(content.expandedBody)
            content.summaryText?.let { style.setSummaryText(it) }
            builder.setStyle(style)
        }

        val notification = builder.build()

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
        skippedWhileScreenOff = false
        if (lastRenderedKey == null) return
        lastRenderedKey = null
        runCatching {
            NotificationManagerCompat.from(context).cancel(LyricsNotificationId)
        }
    }

    private fun resolveLockScreenVisibility(): Int {
        val showOnLockScreen = runCatching {
            Settings.Secure.getInt(context.contentResolver, "lock_screen_show_notifications", 1)
        }.getOrDefault(1)
        val allowPrivateContent = runCatching {
            Settings.Secure.getInt(context.contentResolver, "lock_screen_allow_private_notifications", 1)
        }.getOrDefault(1)
        return if (showOnLockScreen == 1 && allowPrivateContent == 1) {
            NotificationCompat.VISIBILITY_PUBLIC
        } else {
            NotificationCompat.VISIBILITY_PRIVATE
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
        // Clean up legacy IMPORTANCE_LOW channel that HyperOS / Android 15 collapsed as silent on lock screen
        runCatching {
            if (manager.getNotificationChannel(LegacyChannelId) != null) {
                manager.deleteNotificationChannel(LegacyChannelId)
            }
        }
        if (manager.getNotificationChannel(ChannelId) != null) return
        val channel = NotificationChannel(
            ChannelId,
            context.getString(R.string.lockscreen_lyrics_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.lockscreen_lyrics_channel_desc)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val LegacyChannelId = "podcasty_lockscreen_lyrics"
        const val ChannelId = "podcasty_lockscreen_lyrics_v2"
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
            previousLyric: String? = null,
            translationLyric: String? = null,
            expandedMode: Boolean = true,
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
            val cleanPrev = previousLyric?.trim()?.takeIf { it.isNotBlank() && it != cleanCurrent }
            val cleanTranslation = translationLyric?.trim()?.takeIf { it.isNotBlank() && it != cleanCurrent }
            val cleanNext = nextLyric?.trim()?.takeIf { it.isNotBlank() && it != cleanCurrent }
            val collapsedLine = "♪ $cleanCurrent"
            val expandedBody = if (!expandedMode) {
                collapsedLine
            } else {
                buildList {
                    if (cleanPrev != null) add(cleanPrev)
                    add(collapsedLine)
                    if (cleanTranslation != null) add(cleanTranslation)
                    if (cleanNext != null) add(cleanNext)
                }.joinToString("\n")
            }
            val summaryText = if (isPaused) "Paused" else null
            val renderKey = "$episodeId|$title|$collapsedLine|${cleanPrev.orEmpty()}|${cleanTranslation.orEmpty()}|${cleanNext.orEmpty()}|$isPaused|$expandedMode"
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
