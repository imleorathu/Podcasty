/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.storage.scanner

import android.content.Context
import android.net.Uri
import com.podcasty.app.database.PodcastyDao
import com.podcasty.app.domain.StorageProviderType
import com.podcasty.app.storage.LibraryIndexingService
import com.podcasty.app.storage.LibraryScanState
import com.podcasty.app.storage.LocalMusicScanner
import com.podcasty.app.storage.core.ScanSessionStatus
import com.podcasty.app.storage.core.StorageLog
import com.podcasty.app.storage.core.StoragePermissionMonitor
import com.podcasty.app.storage.monitoring.MediaStoreChangeObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Centralized ScanCoordinator for Storage Engine 2.0.
 *
 * Guarantees:
 * - At most one active full-library scan or per-folder scan at a time.
 * - Debounces repeated USB/storage attachment broadcasts (1500 ms window).
 * - Recovers interrupted `RUNNING` scan sessions on startup without deleting indexed tracks.
 * - Coordinates `LibraryIndexingService` lifecycle cleanly with active work.
 */
class ScanCoordinator(
    private val context: Context,
    private val dao: PodcastyDao,
    private val scanner: LocalMusicScanner,
    private val permissionMonitor: StoragePermissionMonitor,
) {
    private val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private var activeFullScanJob: Job? = null
    private var activeMediaStoreJob: Job? = null
    private val activeFolderJobs = HashMap<String, Job>()
    private var usbAttachDebounceJob: Job? = null
    @Volatile
    private var lastSyncedMediaStoreGeneration: Long = -1L
    internal var mediaStoreObserver: MediaStoreChangeObserver? = null

    val scanState: StateFlow<LibraryScanState> = scanner.scanState

    /**
     * Called once at app startup to close out any scan sessions left `RUNNING` by a prior process kill,
     * validate persisted SAF folder permissions, and prune stale session logs (>30 days).
     */
    fun recoverOnStartup() {
        coordinatorScope.launch {
            runCatching {
                val now = System.currentTimeMillis()
                val interrupted = dao.getInterruptedScanSessions()
                if (interrupted.isNotEmpty()) {
                    dao.markInterruptedScanSessions(
                        newStatus = ScanSessionStatus.CANCELLED.name,
                        completedAt = now,
                        errorMessage = "Previous scan interrupted by process exit; existing tracks preserved.",
                    )
                    StorageLog.warn("ScanCoordinator", "Recovered ${interrupted.size} interrupted scan session(s) on startup.")
                }
                permissionMonitor.validatePersistedFolders()
                dao.pruneOldScanSessions(now - 30L * 24L * 60L * 60L * 1000L)
            }.onFailure { err ->
                StorageLog.error("ScanCoordinator", "Startup recovery failed", err)
            }
        }
    }

    /**
     * Launch-time intelligent synchronization:
     * - NEVER starts a full-library scan or foreground indexing service on app startup.
     * - Heals `initialIndexingCompleted = true` for any folder that already has tracks in Room.
     * - Validates persisted folder permissions silently in the background.
     * - Compares persisted `lastMediaStoreGeneration` in Room against MediaStore.getGeneration()
     *   and runs a silent delta query ONLY if the generation actually advanced.
     */
    fun syncOnAppLaunch(autoLibraryRefresh: Boolean, includeSubfolders: Boolean) {
        coordinatorScope.launch {
            val allFolders = dao.getAllMusicFolders()
            val enabledFolders = allFolders.filter { it.isEnabled }
            if (enabledFolders.isEmpty()) return@launch

            val now = System.currentTimeMillis()
            for (folder in enabledFolders) {
                if (!folder.initialIndexingCompleted) {
                    val existingCount = if (folder.trackCount > 0) {
                        folder.trackCount
                    } else {
                        dao.getExistingTrackStampsForFolder(folder.treeUri).size
                    }
                    if (existingCount > 0) {
                        dao.markFolderInitialIndexingCompleted(folder.treeUri, now, existingCount)
                        StorageLog.info("ScanCoordinator", "Healed initialIndexingCompleted=true for '${folder.displayName}' ($existingCount tracks)")
                    }
                }
            }

            // Validate availability silently without launching LibraryIndexingService
            permissionMonitor.validatePersistedFolders()
            if (autoLibraryRefresh) {
                syncMediaStoreIfGenerationChanged()
            }
        }
    }

    fun syncMediaStoreIfGenerationChanged() {
        coordinatorScope.launch {
            val currentGen = mediaStoreObserver?.currentAudioGeneration() ?: return@launch
            if (currentGen <= 0L) return@launch
            val mediaStoreFolder = dao.getAllMusicFolders()
                .firstOrNull { it.treeUri == StorageProviderType.MediaStore.key }
                ?: return@launch
            if (!mediaStoreFolder.isEnabled || !mediaStoreFolder.initialIndexingCompleted) return@launch
            val persistedGen = maxOf(lastSyncedMediaStoreGeneration, mediaStoreFolder.lastMediaStoreGeneration)
            if (currentGen <= persistedGen) return@launch
            lastSyncedMediaStoreGeneration = currentGen

            mutex.withLock {
                if (activeMediaStoreJob?.isActive == true) return@withLock
                StorageLog.info("ScanCoordinator", "MediaStore generation advanced ($persistedGen -> $currentGen); running silent delta sync")
                activeMediaStoreJob = coordinatorScope.launch {
                    try {
                        scanner.scanMediaStoreAudioInternal(
                            sinceGeneration = persistedGen,
                            silentIncremental = true,
                        )
                    } finally {
                        mutex.withLock { activeMediaStoreJob = null }
                    }
                }
            }
        }
    }

    fun requestFullScan(includeSubfoldersOverride: Boolean? = null) {
        coordinatorScope.launch {
            mutex.withLock {
                if (activeFullScanJob?.isActive == true) {
                    StorageLog.info("ScanCoordinator", "Ignored duplicate full-library scan request.")
                    return@withLock
                }
                LibraryIndexingService.start(context)
                activeFullScanJob = coordinatorScope.launch {
                    try {
                        permissionMonitor.validatePersistedFolders()
                        scanner.scanAllFoldersInternal(includeSubfoldersOverride)
                    } finally {
                        mutex.withLock { activeFullScanJob = null }
                    }
                }
            }
        }
    }

    fun requestFolderScan(
        treeUri: Uri,
        includeSubfolders: Boolean = true,
        onFinished: ((LibraryScanState) -> Unit)? = null,
    ) {
        val key = treeUri.toString()
        coordinatorScope.launch {
            mutex.withLock {
                if (activeFullScanJob?.isActive == true || activeFolderJobs[key]?.isActive == true) {
                    StorageLog.info("ScanCoordinator", "Ignored duplicate folder scan request for $key")
                    return@withLock
                }
                LibraryIndexingService.start(context)
                val job = coordinatorScope.launch {
                    try {
                        val result = scanner.scanFolderInternal(treeUri, includeSubfolders)
                        onFinished?.invoke(result)
                    } finally {
                        mutex.withLock { activeFolderJobs.remove(key) }
                    }
                }
                activeFolderJobs[key] = job
            }
        }
    }

    fun requestMediaStoreScan(onFinished: ((LibraryScanState) -> Unit)? = null) {
        coordinatorScope.launch {
            mutex.withLock {
                if (activeMediaStoreJob?.isActive == true) {
                    StorageLog.info("ScanCoordinator", "Ignored duplicate MediaStore scan request.")
                    return@withLock
                }
                LibraryIndexingService.start(context)
                activeMediaStoreJob = coordinatorScope.launch {
                    try {
                        val result = scanner.scanMediaStoreAudioInternal()
                        onFinished?.invoke(result)
                    } finally {
                        mutex.withLock { activeMediaStoreJob = null }
                    }
                }
            }
        }
    }

    fun onUsbStorageAttached(autoRefreshEnabled: Boolean, includeSubfolders: Boolean) {
        usbAttachDebounceJob?.cancel()
        usbAttachDebounceJob = coordinatorScope.launch {
            delay(UsbAttachDebounceMs)
            val beforeAvailability = dao.getAllMusicFolders().associate { it.treeUri to it.isAvailable }
            permissionMonitor.refreshRemovableAvailability()
            if (!autoRefreshEnabled) return@launch
            val removableToRefresh = dao.getAllMusicFolders().filter { folder ->
                folder.isEnabled &&
                    (folder.storageType == StorageProviderType.UsbOtg.key || folder.storageType == StorageProviderType.SdCard.key) &&
                    folder.isAvailable &&
                    (beforeAvailability[folder.treeUri] == false || !folder.initialIndexingCompleted)
            }
            for (folder in removableToRefresh) {
                requestFolderScan(
                    treeUri = Uri.parse(folder.treeUri),
                    includeSubfolders = includeSubfolders || folder.includeSubfolders,
                )
            }
        }
    }

    fun onUsbStorageDetached() {
        coordinatorScope.launch {
            permissionMonitor.refreshRemovableAvailability()
        }
    }

    fun cancelActiveScans() {
        coordinatorScope.launch {
            mutex.withLock {
                activeFullScanJob?.cancel()
                activeMediaStoreJob?.cancel()
                activeFolderJobs.values.forEach { it.cancel() }
                activeFolderJobs.clear()
            }
            scanner.cancelActiveScan()
        }
    }

    companion object {
        private const val UsbAttachDebounceMs = 1_500L
    }
}
