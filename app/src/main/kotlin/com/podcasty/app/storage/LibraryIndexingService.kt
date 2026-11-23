/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.storage

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.podcasty.app.R
import com.podcasty.app.app.MainActivity
import com.podcasty.app.app.PodcastyApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps audio file indexing running in the background until completion,
 * even when the user switches screens, minimizes the app, or turns off the display.
 */
class LibraryIndexingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        startForegroundCompat(
            buildProgressNotification(
                LibraryScanState(
                    isScanning = true,
                    lastMessage = "Indexing audio files in background…",
                ),
            ),
        )
        observeScannerState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as? PodcastyApplication
        val currentState = app?.container?.localMusicScanner?.scanState?.value
            ?: LibraryScanState(isScanning = false)
        if (!currentState.isScanning && intent == null) {
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat(buildProgressNotification(currentState))
        if (currentState.isScanning) {
            acquireWakeLock()
        }
        if (observeJob?.isActive != true) {
            observeScannerState()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        val app = application as? PodcastyApplication
        app?.container?.scanCoordinator?.cancelActiveScans()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun observeScannerState() {
        val app = application as? PodcastyApplication ?: return
        val scanner = app.container.localMusicScanner
        observeJob?.cancel()
        observeJob = serviceScope.launch {
            var sawActiveScan = scanner.scanState.value.isScanning
            scanner.scanState.collectLatest { state ->
                if (state.isScanning) {
                    sawActiveScan = true
                    acquireWakeLock()
                    val nm = getSystemService(NotificationManager::class.java)
                    nm?.notify(NOTIFICATION_ID, buildProgressNotification(state))
                } else {
                    if (!sawActiveScan) {
                        // Grace window in case the service started a few ms before the scan flipped isScanning = true
                        delay(800L)
                        if (scanner.scanState.value.isScanning) return@collectLatest
                    }
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun buildProgressNotification(state: LibraryScanState): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val total = state.totalFiles
        val current = state.scannedFiles
        val indeterminate = total <= 0 || current <= 0
        val contentText = state.lastMessage?.takeIf { it.isNotBlank() }
            ?: if (total > 0) {
                "Indexing audio files ($current / $total)…"
            } else {
                "Discovering audio files in background…"
            }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Indexing Audio Library")
            .setContentText(contentText)
            .setSubText(if (total > 0) "$current / $total" else null)
            .setProgress(total.coerceAtLeast(0), current.coerceAtLeast(0), indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Audio Library Indexing",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shows background progress while indexing local audio files"
                    setShowBadge(false)
                    setSound(null, null)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun acquireWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(PowerManager::class.java) ?: return
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Podcasty:LibraryIndexingWakeLock",
            ).apply {
                setReferenceCounted(false)
                // Bounded 10-minute safety timeout so partial wake lock can never leak on idle
                acquire(10L * 60L * 1000L)
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let { lock ->
                if (lock.isHeld) lock.release()
            }
            wakeLock = null
        }
    }

    companion object {
        private const val CHANNEL_ID = "podcasty_library_indexing"
        private const val NOTIFICATION_ID = 4208

        fun start(context: Context) {
            runCatching {
                val appContext = context.applicationContext
                val intent = Intent(appContext, LibraryIndexingService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            }
        }
    }
}
