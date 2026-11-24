/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.storage

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.kyant.taglib.AudioPropertiesReadStyle
import com.kyant.taglib.TagLib
import com.podcasty.app.audio.AudioFormatCatalog
import com.podcasty.app.core.UnicodeSortUtil
import com.podcasty.app.database.CategoryEntity
import com.podcasty.app.database.EpisodeEntity
import com.podcasty.app.database.ExistingTrackStamp
import com.podcasty.app.database.HostEntity
import com.podcasty.app.database.MusicFolderEntity
import com.podcasty.app.database.PodcastEntity
import com.podcasty.app.database.PodcastyDao
import com.podcasty.app.database.ScanSessionEntity
import com.podcasty.app.domain.MediaContentType
import com.podcasty.app.domain.MediaImportSource
import com.podcasty.app.domain.MediaSourceType
import com.podcasty.app.domain.StorageProviderType
import com.podcasty.app.storage.core.ScanSessionStatus
import com.podcasty.app.storage.core.StorageLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class LibraryScanState(
    val isScanning: Boolean = false,
    val scannedFiles: Int = 0,
    val totalFiles: Int = 0,
    val addedCount: Int = 0,
    val removedCount: Int = 0,
    val lastMessage: String? = null,
    val lastError: String? = null,
) {
    val progressFraction: Float
        get() = if (totalFiles > 0) (scannedFiles.toFloat() / totalFiles.toFloat()).coerceIn(0f, 1f) else 0f
}

/**
 * Memory-bounded, incremental, cycle-safe local audio indexer.
 *
 * Key optimizations for 6k-25k+ libraries on mid-range Android devices:
 * - Iterative BFS traversal bounded at MaxDirectoryDepth (avoids stack overflow & symlink loops).
 * - O(1) per-directory sidecar matching (.lrc/.srt/.vtt) instead of O(D*N) scans.
 * - Lightweight ExistingTrackStamp queries (never loads lyrics blobs at scan start).
 * - Streams and flushes Room batches incrementally (ScanBatchSize = 250) in one transaction per batch.
 * - Content-hashes and downsamples cached cover art (max 600px JPEG) so album tracks share one tiny file.
 * - Releases MediaMetadataRetriever in finally blocks on every fallback read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalMusicScanner(
    private val context: Context,
    private val dao: PodcastyDao,
    private val onLibraryMutated: (() -> Unit)? = null,
) {
    private val _scanState = MutableStateFlow(LibraryScanState())
    val scanState: StateFlow<LibraryScanState> = _scanState.asStateFlow()

    private val scannerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(2))
    private val scanMutex = Mutex()
    private var activeScanJob: Job? = null
    internal var scanCoordinator: com.podcasty.app.storage.scanner.ScanCoordinator? = null
    @Volatile
    var isPlaybackActive: Boolean = false

    internal val artworkResolver: LocalArtworkResolver = LocalArtworkResolver(context)

    fun startBackgroundScanAllFolders(includeSubfoldersOverride: Boolean? = null) {
        val coord = scanCoordinator
        if (coord != null) {
            coord.requestFullScan(includeSubfoldersOverride)
            return
        }
        if (activeScanJob?.isActive == true) return
        LibraryIndexingService.start(context)
        activeScanJob = scannerScope.launch {
            scanAllFoldersInternal(includeSubfoldersOverride)
        }
    }

    fun startBackgroundScanFolder(
        treeUri: Uri,
        includeSubfolders: Boolean = true,
        onFinished: ((LibraryScanState) -> Unit)? = null,
    ) {
        val coord = scanCoordinator
        if (coord != null) {
            coord.requestFolderScan(treeUri, includeSubfolders, onFinished)
            return
        }
        LibraryIndexingService.start(context)
        scannerScope.launch {
            val result = scanFolderInternal(treeUri, includeSubfolders)
            onFinished?.invoke(result)
        }
    }

    fun startBackgroundScanMediaStore(onFinished: ((LibraryScanState) -> Unit)? = null) {
        val coord = scanCoordinator
        if (coord != null) {
            coord.requestMediaStoreScan(onFinished)
            return
        }
        LibraryIndexingService.start(context)
        scannerScope.launch {
            val result = scanMediaStoreAudioInternal()
            onFinished?.invoke(result)
        }
    }

    fun cancelActiveScan() {
        activeScanJob?.cancel()
    }

    fun detectStorageType(treeUri: Uri): StorageProviderType {
        val raw = treeUri.toString().lowercase()
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrDefault("").lowercase()
        return when {
            raw.contains("usb") || docId.contains("usb") || raw.contains("otg") -> StorageProviderType.UsbOtg
            docId.startsWith("primary:") || raw.contains("primary%3a") -> StorageProviderType.Internal
            docId.matches(Regex("^[0-9a-f]{4}-[0-9a-f]{4}:.*")) -> {
                if (raw.contains("usb") || isUsbVolumeHeuristic(docId.substringBefore(':'))) {
                    StorageProviderType.UsbOtg
                } else {
                    StorageProviderType.SdCard
                }
            }
            else -> StorageProviderType.Internal
        }
    }

    private fun isUsbVolumeHeuristic(uuid: String): Boolean = runCatching {
        val storageManager = context.getSystemService(android.os.storage.StorageManager::class.java)
        storageManager?.storageVolumes?.any { vol ->
            vol.uuid.equals(uuid, ignoreCase = true) &&
                vol.isRemovable &&
                vol.getDescription(context).contains("usb", ignoreCase = true)
        } ?: false
    }.getOrDefault(false)

    fun resolveFolderDisplayName(treeUri: Uri): String {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrDefault("")
        if (docId.contains(':')) {
            val tail = docId.substringAfter(':').trim('/')
            if (tail.isNotEmpty()) return tail
            val vol = docId.substringBefore(':')
            return if (vol.equals("primary", ignoreCase = true)) "Internal storage" else "Volume $vol"
        }
        return treeUri.lastPathSegment?.substringAfterLast(':') ?: treeUri.toString()
    }

    suspend fun registerFolder(
        treeUri: Uri,
        makeDefault: Boolean = true,
        includeSubfolders: Boolean = true,
        forceStorageType: StorageProviderType? = null,
    ): MusicFolderEntity = withContext(Dispatchers.IO) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(treeUri, flags) }
            .onFailure {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        if (makeDefault) {
            dao.clearDefaultMusicFolderFlag()
        }
        val entity = MusicFolderEntity(
            treeUri = treeUri.toString(),
            displayName = resolveFolderDisplayName(treeUri),
            storageType = (forceStorageType ?: detectStorageType(treeUri)).key,
            isDefault = makeDefault,
            includeSubfolders = includeSubfolders,
            lastScannedAt = 0L,
            trackCount = 0,
            isAvailable = true,
        )
        dao.upsertMusicFolder(entity)
        entity
    }

    suspend fun removeFolder(treeUri: String) = withContext(Dispatchers.IO) {
        dao.deleteEpisodesByFolderUri(treeUri)
        dao.deleteMusicFolder(treeUri)
        dao.deleteOrphanPodcasts()
        dao.deleteOrphanHosts()
        dao.deleteOrphanCategories()
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(treeUri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    suspend fun scanAllFolders(includeSubfoldersOverride: Boolean? = null): LibraryScanState {
        LibraryIndexingService.start(context)
        return scannerScope.async {
            scanAllFoldersInternal(includeSubfoldersOverride)
        }.await()
    }

    internal suspend fun scanAllFoldersInternal(
        includeSubfoldersOverride: Boolean? = null,
        recheckMissingArtwork: Boolean = false,
    ): LibraryScanState {
        val folders = dao.getAllMusicFolders().filter { it.isEnabled }
        if (folders.isEmpty()) {
            val state = LibraryScanState(
                isScanning = false,
                lastMessage = "No enabled music folder selected.",
            )
            _scanState.value = state
            return state
        }
        var totalScanned = 0
        var totalAdded = 0
        var totalRemoved = 0
        var lastErr: String? = null
        for (folder in folders) {
            currentCoroutineContext().ensureActive()
            val res = if (folder.treeUri == StorageProviderType.MediaStore.key) {
                scanMediaStoreAudioInternal(recheckMissingArtwork = recheckMissingArtwork)
            } else {
                scanFolderInternal(
                    treeUri = Uri.parse(folder.treeUri),
                    includeSubfolders = includeSubfoldersOverride ?: folder.includeSubfolders,
                    recheckMissingArtwork = recheckMissingArtwork,
                )
            }
            totalScanned += res.scannedFiles
            totalAdded += res.addedCount
            totalRemoved += res.removedCount
            if (res.lastError != null) lastErr = res.lastError
        }
        if (lastErr == null) {
            runCatching {
                val referenced = dao.getAllReferencedArtworkPaths().toHashSet()
                artworkResolver.pruneUnreferencedArtwork(referenced)
            }
        }
        val finalState = LibraryScanState(
            isScanning = false,
            scannedFiles = totalScanned,
            totalFiles = totalScanned,
            addedCount = totalAdded,
            removedCount = totalRemoved,
            lastMessage = "Scanned $totalScanned tracks ($totalAdded added, $totalRemoved removed).",
            lastError = lastErr,
        )
        _scanState.value = finalState
        return finalState
    }

    suspend fun scanFolder(
        treeUri: Uri,
        includeSubfolders: Boolean = true,
    ): LibraryScanState {
        LibraryIndexingService.start(context)
        return scannerScope.async {
            scanFolderInternal(treeUri, includeSubfolders)
        }.await()
    }

    internal suspend fun scanFolderInternal(
        treeUri: Uri,
        includeSubfolders: Boolean = true,
        recheckMissingArtwork: Boolean = false,
    ): LibraryScanState = scanMutex.withLock {
        val folderKey = treeUri.toString()
        val scanId = UUID.randomUUID().toString()
        val scanStartedAt = System.currentTimeMillis()
        StorageLog.info("Scanner", "Starting folder scan id=$scanId uri=$folderKey")

        val excludedKeys = dao.getExcludedFolders().mapTo(HashSet()) { it.folderKey }
        if ("$folderKey::" in excludedKeys || folderKey in excludedKeys) {
            val skipped = LibraryScanState(isScanning = false, lastMessage = "Skipped excluded folder.")
            _scanState.value = skipped
            return@withLock skipped
        }

        dao.upsertScanSession(
            ScanSessionEntity(
                scanId = scanId,
                folderUri = folderKey,
                startedAt = scanStartedAt,
                status = ScanSessionStatus.RUNNING.name,
            ),
        )

        _scanState.value = LibraryScanState(
            isScanning = true,
            lastMessage = "Indexing audio files in background…",
        )

        val rootDocId = resolveRootDocumentId(treeUri)
        if (rootDocId == null) {
            dao.setMusicFolderAvailable(folderKey, false)
            val errMsg = "Folder access was revoked or the storage device is unavailable."
            dao.upsertScanSession(
                ScanSessionEntity(
                    scanId = scanId,
                    folderUri = folderKey,
                    startedAt = scanStartedAt,
                    completedAt = System.currentTimeMillis(),
                    status = ScanSessionStatus.STORAGE_UNAVAILABLE.name,
                    errorMessage = errMsg,
                ),
            )
            StorageLog.warn("Scanner", "Root unreachable for $folderKey; existing tracks preserved")
            val err = LibraryScanState(
                isScanning = false,
                lastError = errMsg,
            )
            _scanState.value = err
            return@withLock err
        }

        val existingStamps = dao.getExistingTrackStampsForFolder(folderKey).associateBy { it.id }
        val seenIds = HashSet<String>(maxOf(256, existingStamps.size))
        val batchWriter = ScanBatchWriter(dao, onLibraryMutated)
        val folderCoverMemo = HashMap<String, String?>()
        val volatileArtMigrationMemo = HashMap<String, String?>()

        val now = System.currentTimeMillis()
        var discoveredCount = 0
        var addedCount = 0
        var totalIndexedCount = 0
        var failedFilesCount = 0
        var lastCheckpoint = ""
        var lastUiEmitMs = 0L
        var hadTreeError = false
        var treeErrorMessage: String? = null
        var traversalCompletedCleanly = false

        suspend fun processDiscoveredFile(file: DiscoveredDocFile) {
            currentCoroutineContext().ensureActive()
            val relKey = "$folderKey::${file.relativeFolderPath.trim('/')}"
            if (relKey in excludedKeys) return
            val stableId = stableLocalEpisodeId(file.uri.toString())
            if (!seenIds.add(stableId)) return
            discoveredCount++

            try {
                val existingStamp = existingStamps[stableId]
                val index = totalIndexedCount++
                val existingArt = existingStamp?.artworkAsset
                val existingArtInVolatileCache = existingArt != null &&
                    existingArt.startsWith(context.cacheDir.absolutePath)
                val migratedPersistentArt = if (existingArtInVolatileCache) {
                    artworkResolver.migrateVolatileCachedArtwork(existingArt, volatileArtMigrationMemo)
                } else {
                    null
                }
                val existingArtUsable = when {
                    migratedPersistentArt != null -> true
                    existingArt != null && !existingArtInVolatileCache ->
                        LocalArtworkResolver.isUsableCachedArtworkFile(existingArt)
                    else -> false
                }
                val needsArtworkRefresh = when {
                    existingStamp == null -> true
                    existingArt != null && !existingArtUsable -> true
                    existingArt == null && (recheckMissingArtwork || file.folderCoverUri != null) -> true
                    else -> false
                }
                val canFastReuse = existingStamp != null &&
                    !needsArtworkRefresh &&
                    existingStamp.fileSizeBytes == file.sizeBytes &&
                    existingStamp.durationMs > 0L &&
                    (file.lastModified <= 0L || existingStamp.lastModifiedMs == file.lastModified) &&
                    (file.sidecarLyricsUri == null || existingStamp.hasLyrics)

                if (canFastReuse) {
                    val expectedAsset = if (file.relativeFolderPath.isNotBlank()) {
                        "${file.relativeFolderPath}/${file.displayName}"
                    } else {
                        file.displayName
                    }
                    val needsPathOrArtUpdate = existingStamp.audioAsset != expectedAsset ||
                        existingStamp.relativeFolderPath != file.relativeFolderPath ||
                        migratedPersistentArt != null
                    if (needsPathOrArtUpdate) {
                        val existingFull = dao.getEpisodeById(stableId)
                        if (existingFull != null) {
                            batchWriter.addEpisode(
                                existingFull.copy(
                                    audioAsset = expectedAsset,
                                    relativeFolderPath = file.relativeFolderPath,
                                    lastModifiedMs = file.lastModified,
                                    artworkAsset = migratedPersistentArt ?: existingFull.artworkAsset,
                                ),
                            )
                        }
                    }
                } else {
                    if (isPlaybackActive) {
                        delay(12L)
                    }
                    val existingFull = existingStamp?.let { dao.getEpisodeById(stableId) }
                    val parsed = extractMetadataForDocument(
                        file = file,
                        folderUri = folderKey,
                        stableId = stableId,
                        existing = existingFull,
                        now = now,
                        index = index,
                        folderCoverMemo = folderCoverMemo,
                        forceReextractArtwork = needsArtworkRefresh,
                    )
                    if (existingFull == null) addedCount++
                    batchWriter.add(parsed)
                }
                lastCheckpoint = file.relativeFolderPath
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                failedFilesCount++
                StorageLog.warn("Scanner", "Metadata extraction failed for '${file.displayName}'", t)
            }

            if (batchWriter.flushIfNeeded()) {
                dao.upsertScanSession(
                    ScanSessionEntity(
                        scanId = scanId,
                        folderUri = folderKey,
                        startedAt = scanStartedAt,
                        status = ScanSessionStatus.RUNNING.name,
                        discoveredCount = discoveredCount,
                        indexedCount = totalIndexedCount,
                        failedCount = failedFilesCount,
                        lastCheckpointPath = lastCheckpoint,
                    ),
                )
            }
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastUiEmitMs >= ProgressEmitIntervalMs) {
                lastUiEmitMs = nowMs
                _scanState.value = _scanState.value.copy(
                    isScanning = true,
                    scannedFiles = totalIndexedCount,
                    totalFiles = maxOf(totalIndexedCount, _scanState.value.totalFiles),
                    addedCount = addedCount,
                    lastMessage = "Indexing audio files in background ($totalIndexedCount)…",
                )
            }
        }

        // Iterative BFS walk with bounded depth & O(1) directory sidecar lookup
        val queue = ArrayDeque<DirWalkNode>()
        queue.addLast(DirWalkNode(docId = rootDocId, relativeFolder = "", depth = 0))
        val visitedDirs = HashSet<String>()

        try {
            while (queue.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val node = queue.removeFirst()
                if (node.depth > MaxDirectoryDepth || visitedDirs.size >= MaxDirectoriesPerScan || !visitedDirs.add(node.docId)) continue
                val relPrefixKey = "$folderKey::${node.relativeFolder.trim('/')}"
                if (node.relativeFolder.isNotEmpty() && relPrefixKey in excludedKeys) continue

                val dirAudioFiles = ArrayList<DiscoveredDocFile>(32)
                val sidecarFilesInDir = HashMap<String, Uri>(8)
                var bestFolderCoverPriority = Int.MAX_VALUE
                var bestFolderCoverUri: Uri? = null
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, node.docId)
                val projection = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                )
                try {
                    context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                        val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                        val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                        val modIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                        while (cursor.moveToNext()) {
                            val docId = if (idIdx >= 0) cursor.getString(idIdx) else continue
                            val rawName = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                            val name = rawName?.takeIf { it.isNotBlank() }
                                ?: docId.substringAfterLast('/').substringAfterLast(':').ifBlank { "track" }
                            val mime = (if (mimeIdx >= 0) cursor.getString(mimeIdx) else null)?.lowercase().orEmpty()
                            val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                            val lastMod = if (modIdx >= 0) cursor.getLong(modIdx) else 0L

                            val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR.lowercase() ||
                                mime.endsWith("/directory") ||
                                (mime.isEmpty() && !name.contains('.') && size == 0L)

                            if (isDir) {
                                if (includeSubfolders && !name.startsWith(".") && node.depth + 1 <= MaxDirectoryDepth) {
                                    val childRel = if (node.relativeFolder.isEmpty()) name else "${node.relativeFolder}/$name"
                                    queue.addLast(DirWalkNode(docId, childRel, node.depth + 1))
                                }
                            } else {
                                val ext = name.substringAfterLast('.', "").lowercase()
                                val docIdExt = docId.substringAfterLast('.', "").lowercase()
                                val isAudioMime = mime.startsWith("audio/") ||
                                    mime == "application/ogg" ||
                                    mime == "application/x-ogg" ||
                                    mime == "application/flac" ||
                                    mime == "application/x-flac" ||
                                    mime == "video/mp4" ||
                                    mime == "video/3gpp"
                                if (ext in SidecarExtensions || docIdExt in SidecarExtensions) {
                                    val baseKey = name.substringBeforeLast('.').trim().lowercase()
                                    if (baseKey.isNotEmpty()) {
                                        sidecarFilesInDir[baseKey] = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                                    }
                                } else if (LocalArtworkResolver.folderCoverPriority(name) >= 0) {
                                    val coverPrio = LocalArtworkResolver.folderCoverPriority(name)
                                    if (coverPrio in 0 until bestFolderCoverPriority && size > 32L) {
                                        bestFolderCoverPriority = coverPrio
                                        bestFolderCoverUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                                    }
                                } else if (
                                    ext in AudioFormatCatalog.scannableExtensions ||
                                    docIdExt in AudioFormatCatalog.scannableExtensions ||
                                    isAudioMime
                                ) {
                                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                                    dirAudioFiles += DiscoveredDocFile(
                                        uri = docUri,
                                        displayName = name,
                                        sizeBytes = size,
                                        lastModified = lastMod,
                                        relativeFolderPath = node.relativeFolder,
                                    )
                                }
                            }
                        }
                    } ?: run {
                        hadTreeError = true
                        treeErrorMessage = "Directory query returned null."
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (dirErr: Throwable) {
                    hadTreeError = true
                    treeErrorMessage = dirErr.localizedMessage
                    StorageLog.warn("Scanner", "Directory read error at '${node.relativeFolder}'; preserving existing tracks", dirErr)
                }

                // Match sidecars and folder cover image only for files discovered in this directory (O(1) per file)
                for (audioFile in dirAudioFiles) {
                    var matched = if (sidecarFilesInDir.isEmpty()) {
                        audioFile
                    } else {
                        val baseKey = audioFile.displayName.substringBeforeLast('.').trim().lowercase()
                        val sidecarUri = sidecarFilesInDir[baseKey]
                            ?: sidecarFilesInDir.entries.firstOrNull { (k, _) -> k.startsWith("$baseKey.") }?.value
                        if (sidecarUri != null) audioFile.copy(sidecarLyricsUri = sidecarUri) else audioFile
                    }
                    if (bestFolderCoverUri != null) {
                        matched = matched.copy(folderCoverUri = bestFolderCoverUri)
                    }
                    processDiscoveredFile(matched)
                }
            }
            traversalCompletedCleanly = !hadTreeError
        } catch (ce: CancellationException) {
            batchWriter.flush()
            dao.upsertScanSession(
                ScanSessionEntity(
                    scanId = scanId,
                    folderUri = folderKey,
                    startedAt = scanStartedAt,
                    completedAt = System.currentTimeMillis(),
                    status = ScanSessionStatus.CANCELLED.name,
                    discoveredCount = discoveredCount,
                    indexedCount = totalIndexedCount,
                    failedCount = failedFilesCount,
                    lastCheckpointPath = lastCheckpoint,
                    errorMessage = "Scan cancelled; existing tracks preserved.",
                ),
            )
            _scanState.value = LibraryScanState(isScanning = false, scannedFiles = totalIndexedCount, lastMessage = "Scan cancelled.")
            StorageLog.warn("Scanner", "Folder scan cancelled id=$scanId after $totalIndexedCount tracks; preserved existing DB")
            throw ce
        }

        if (totalIndexedCount == 0 && hadTreeError) {
            val mediaStoreFallback = queryMediaStoreForTreeUri(treeUri, rootDocId, includeSubfolders)
            if (mediaStoreFallback.isNotEmpty()) {
                for (fallbackFile in mediaStoreFallback) {
                    processDiscoveredFile(fallbackFile)
                }
                traversalCompletedCleanly = true
                hadTreeError = false
            } else {
                batchWriter.flush()
                dao.setMusicFolderAvailable(folderKey, false)
                val errMsg = "Could not read folder (${treeErrorMessage ?: "permission revoked or USB unplugged"})."
                dao.upsertScanSession(
                    ScanSessionEntity(
                        scanId = scanId,
                        folderUri = folderKey,
                        startedAt = scanStartedAt,
                        completedAt = System.currentTimeMillis(),
                        status = ScanSessionStatus.STORAGE_UNAVAILABLE.name,
                        discoveredCount = discoveredCount,
                        indexedCount = totalIndexedCount,
                        failedCount = failedFilesCount,
                        lastCheckpointPath = lastCheckpoint,
                        errorMessage = errMsg,
                    ),
                )
                val err = LibraryScanState(isScanning = false, lastError = errMsg)
                _scanState.value = err
                return@withLock err
            }
        }

        batchWriter.flush()

        // CRITICAL DATA INTEGRITY GUARD: Only delete missing tracks when full traversal completed without errors
        val missingIds = if (traversalCompletedCleanly && !hadTreeError) {
            existingStamps.keys.filter { it !in seenIds }
        } else {
            StorageLog.warn("Scanner", "Incomplete traversal for $folderKey; skipped missing-file cleanup to protect existing tracks")
            emptyList()
        }
        if (missingIds.isNotEmpty()) {
            dao.deleteEpisodesCascade(missingIds)
            onLibraryMutated?.invoke()
        }

        val currentFolder = dao.getAllMusicFolders().firstOrNull { it.treeUri == folderKey }
        dao.upsertMusicFolder(
            MusicFolderEntity(
                treeUri = folderKey,
                displayName = currentFolder?.displayName ?: resolveFolderDisplayName(treeUri),
                storageType = currentFolder?.storageType ?: detectStorageType(treeUri).key,
                isDefault = currentFolder?.isDefault ?: true,
                includeSubfolders = includeSubfolders,
                lastScannedAt = now,
                trackCount = if (traversalCompletedCleanly) totalIndexedCount else maxOf(totalIndexedCount, currentFolder?.trackCount ?: 0),
                isAvailable = !hadTreeError || totalIndexedCount > 0,
                isEnabled = currentFolder?.isEnabled ?: true,
                initialIndexingCompleted = (currentFolder?.initialIndexingCompleted == true) || traversalCompletedCleanly || totalIndexedCount > 0,
                lastSyncAt = System.currentTimeMillis(),
                lastMediaStoreGeneration = currentFolder?.lastMediaStoreGeneration ?: 0L,
            ),
        )

        val elapsedMs = System.currentTimeMillis() - scanStartedAt
        val finalStatus = if (hadTreeError) ScanSessionStatus.FAILED else ScanSessionStatus.COMPLETED
        dao.upsertScanSession(
            ScanSessionEntity(
                scanId = scanId,
                folderUri = folderKey,
                startedAt = scanStartedAt,
                completedAt = System.currentTimeMillis(),
                status = finalStatus.name,
                discoveredCount = discoveredCount,
                indexedCount = totalIndexedCount,
                failedCount = failedFilesCount,
                lastCheckpointPath = lastCheckpoint,
                errorMessage = treeErrorMessage,
            ),
        )
        StorageLog.info("Scanner", "Completed $folderKey in ${elapsedMs}ms: indexed=$totalIndexedCount added=$addedCount removed=${missingIds.size} failed=$failedFilesCount")

        val state = LibraryScanState(
            isScanning = false,
            scannedFiles = totalIndexedCount,
            totalFiles = totalIndexedCount,
            addedCount = addedCount,
            removedCount = missingIds.size,
            lastMessage = "Indexed $totalIndexedCount audio files ($addedCount new, ${missingIds.size} removed).",
            lastError = treeErrorMessage,
        )
        _scanState.value = state
        state
    }

    suspend fun scanMediaStoreAudio(): LibraryScanState {
        LibraryIndexingService.start(context)
        return scannerScope.async {
            scanMediaStoreAudioInternal()
        }.await()
    }

    internal suspend fun scanMediaStoreAudioInternal(
        sinceGeneration: Long = 0L,
        silentIncremental: Boolean = false,
        recheckMissingArtwork: Boolean = false,
    ): LibraryScanState = scanMutex.withLock {
        val scanId = UUID.randomUUID().toString()
        val scanStartedAt = System.currentTimeMillis()
        val currentGeneration = runCatching {
            MediaStore.getGeneration(context, MediaStore.VOLUME_EXTERNAL)
        }.getOrDefault(0L)
        if (!silentIncremental) {
            _scanState.value = LibraryScanState(
                isScanning = true,
                lastMessage = "Scanning MediaStore audio in background…",
            )
        }
        val folderKey = StorageProviderType.MediaStore.key
        val excludedKeys = dao.getExcludedFolders().mapTo(HashSet()) { it.folderKey }
        if ("$folderKey::" in excludedKeys || folderKey in excludedKeys) {
            val state = LibraryScanState(isScanning = false, lastMessage = "MediaStore scanning is excluded.")
            _scanState.value = state
            return@withLock state
        }

        dao.upsertScanSession(
            ScanSessionEntity(
                scanId = scanId,
                folderUri = folderKey,
                startedAt = scanStartedAt,
                status = ScanSessionStatus.RUNNING.name,
            ),
        )

        val existingFolderStamps = dao.getExistingTrackStampsForFolder(folderKey).associateBy { it.id }
        val isDeltaQuery = sinceGeneration > 0L && existingFolderStamps.isNotEmpty()
        // Only load SAF deduplication signatures when non-MediaStore SAF folders actually exist
        val hasOtherFolders = dao.getAllMusicFolders().any { it.treeUri != folderKey }
        val safDedupSignatures = if (hasOtherFolders) {
            dao.getDedupSignaturesExcludingFolder(folderKey)
                .asSequence()
                .filter { !existingFolderStamps.containsKey(it.id) && it.fileSizeBytes > 0L }
                .mapTo(HashSet()) { "${it.fileSizeBytes}:${it.audioAsset.substringAfterLast('/').lowercase()}" }
        } else {
            emptySet()
        }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.RELATIVE_PATH,
        )
        val selection = if (isDeltaQuery) {
            "${MediaStore.MediaColumns.GENERATION_ADDED} > ? OR ${MediaStore.MediaColumns.GENERATION_MODIFIED} > ?"
        } else {
            null
        }
        val selectionArgs = if (isDeltaQuery) {
            arrayOf(sinceGeneration.toString(), sinceGeneration.toString())
        } else {
            null
        }

        val now = System.currentTimeMillis()
        val batchWriter = ScanBatchWriter(dao, onLibraryMutated)
        val folderCoverMemo = HashMap<String, String?>()
        val volatileArtMigrationMemo = HashMap<String, String?>()
        val seenIds = HashSet<String>(256)
        var discoveredCount = 0
        var addedCount = 0
        var totalIndexedCount = 0
        var failedFilesCount = 0
        var lastUiEmitMs = 0L

        val queryError: Throwable? = try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val modCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
                val relCol = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val mediaId = cursor.getLong(idCol)
                    val displayName = cursor.getString(nameCol) ?: "audio_$mediaId"
                    val size = cursor.getLong(sizeCol)
                    if (size <= 0L) continue
                    val modMs = (if (modCol >= 0) cursor.getLong(modCol) else 0L) * 1000L
                    val relDir = (if (relCol >= 0) cursor.getString(relCol) else null)?.trim('/').orEmpty()
                    if ("$folderKey::$relDir" in excludedKeys) continue

                    val sig = "$size:${displayName.lowercase()}"
                    if (sig in safDedupSignatures) continue

                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId)
                    val stableId = stableLocalEpisodeId(uri.toString())
                    if (!seenIds.add(stableId)) continue
                    discoveredCount++

                    try {
                        val existingStamp = existingFolderStamps[stableId]
                        val index = totalIndexedCount++
                        val existingArt = existingStamp?.artworkAsset
                        val existingArtInVolatileCache = existingArt != null &&
                            existingArt.startsWith(context.cacheDir.absolutePath)
                        val migratedPersistentArt = if (existingArtInVolatileCache) {
                            artworkResolver.migrateVolatileCachedArtwork(existingArt, volatileArtMigrationMemo)
                        } else {
                            null
                        }
                        val existingArtUsable = when {
                            migratedPersistentArt != null -> true
                            existingArt != null && !existingArtInVolatileCache ->
                                LocalArtworkResolver.isUsableCachedArtworkFile(existingArt)
                            else -> false
                        }
                        val needsArtworkRefresh = when {
                            existingStamp == null -> true
                            existingArt != null && !existingArtUsable -> true
                            existingArt == null && recheckMissingArtwork -> true
                            else -> false
                        }
                        val canFastReuse = existingStamp != null &&
                            !needsArtworkRefresh &&
                            existingStamp.fileSizeBytes == size &&
                            existingStamp.durationMs > 0L &&
                            (modMs <= 0L || existingStamp.lastModifiedMs == modMs)

                        if (canFastReuse && migratedPersistentArt != null) {
                            val existingFull = dao.getEpisodeById(stableId)
                            if (existingFull != null) {
                                batchWriter.addEpisode(existingFull.copy(artworkAsset = migratedPersistentArt))
                                batchWriter.flushIfNeeded()
                            }
                        } else if (!canFastReuse) {
                            if (isPlaybackActive) {
                                delay(12L)
                            }
                            val file = DiscoveredDocFile(
                                uri = uri,
                                displayName = displayName,
                                sizeBytes = size,
                                lastModified = modMs,
                                relativeFolderPath = relDir,
                            )
                            val existingFull = existingStamp?.let { dao.getEpisodeById(stableId) }
                            val parsed = extractMetadataForDocument(
                                file = file,
                                folderUri = folderKey,
                                stableId = stableId,
                                existing = existingFull,
                                now = now,
                                index = index,
                                folderCoverMemo = folderCoverMemo,
                                forceReextractArtwork = needsArtworkRefresh,
                            )
                            if (existingFull == null) addedCount++
                            batchWriter.add(parsed)
                            batchWriter.flushIfNeeded()
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        failedFilesCount++
                        StorageLog.warn("Scanner", "MediaStore metadata failed for '$displayName'", t)
                    }

                    val nowMs = System.currentTimeMillis()
                    if (!silentIncremental && nowMs - lastUiEmitMs >= ProgressEmitIntervalMs) {
                        lastUiEmitMs = nowMs
                        _scanState.value = _scanState.value.copy(
                            isScanning = true,
                            scannedFiles = totalIndexedCount,
                            totalFiles = totalIndexedCount,
                            addedCount = addedCount,
                            lastMessage = "Indexing audio files in background ($totalIndexedCount)…",
                        )
                    }
                }
            }
            null
        } catch (ce: CancellationException) {
            batchWriter.flush()
            dao.upsertScanSession(
                ScanSessionEntity(
                    scanId = scanId,
                    folderUri = folderKey,
                    startedAt = scanStartedAt,
                    completedAt = System.currentTimeMillis(),
                    status = ScanSessionStatus.CANCELLED.name,
                    discoveredCount = discoveredCount,
                    indexedCount = totalIndexedCount,
                    failedCount = failedFilesCount,
                    errorMessage = "MediaStore scan cancelled; existing tracks preserved.",
                ),
            )
            _scanState.value = LibraryScanState(isScanning = false, scannedFiles = totalIndexedCount, lastMessage = "Scan cancelled.")
            throw ce
        } catch (t: Throwable) {
            t
        }

        if (queryError != null) {
            batchWriter.flush()
            dao.upsertScanSession(
                ScanSessionEntity(
                    scanId = scanId,
                    folderUri = folderKey,
                    startedAt = scanStartedAt,
                    completedAt = System.currentTimeMillis(),
                    status = ScanSessionStatus.FAILED.name,
                    discoveredCount = discoveredCount,
                    indexedCount = totalIndexedCount,
                    failedCount = failedFilesCount,
                    errorMessage = queryError.localizedMessage,
                ),
            )
            val state = LibraryScanState(isScanning = false, lastError = queryError.localizedMessage ?: "MediaStore access denied.")
            _scanState.value = state
            return@withLock state
        }

        batchWriter.flush()

        val missingIds = if (!isDeltaQuery) {
            existingFolderStamps.keys.filter { it !in seenIds }
        } else {
            // Fast ID-only reconciliation for incremental MediaStore sync
            val currentMediaStoreIds = HashSet<String>(existingFolderStamps.size + 16)
            val idCheckOk = runCatching {
                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Audio.Media._ID),
                    null,
                    null,
                    null,
                )?.use { idCursor ->
                    val idCol = idCursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    while (idCursor.moveToNext()) {
                        val mId = idCursor.getLong(idCol)
                        val mUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mId)
                        currentMediaStoreIds += stableLocalEpisodeId(mUri.toString())
                    }
                    true
                } ?: false
            }.getOrDefault(false)
            if (idCheckOk) existingFolderStamps.keys.filter { it !in currentMediaStoreIds } else emptyList()
        }
        if (missingIds.isNotEmpty()) {
            dao.deleteEpisodesCascade(missingIds)
            onLibraryMutated?.invoke()
        }

        val finalTrackCount = if (isDeltaQuery) {
            (existingFolderStamps.size + addedCount - missingIds.size).coerceAtLeast(0)
        } else {
            totalIndexedCount
        }
        val existingMediaStoreFolder = dao.getAllMusicFolders().firstOrNull { it.treeUri == folderKey }
        dao.upsertMusicFolder(
            MusicFolderEntity(
                treeUri = folderKey,
                displayName = existingMediaStoreFolder?.displayName ?: "Device Audio (MediaStore)",
                storageType = StorageProviderType.MediaStore.key,
                isDefault = existingMediaStoreFolder?.isDefault ?: false,
                includeSubfolders = true,
                lastScannedAt = if (isDeltaQuery) (existingMediaStoreFolder?.lastScannedAt ?: now) else now,
                trackCount = finalTrackCount,
                isAvailable = true,
                isEnabled = existingMediaStoreFolder?.isEnabled ?: true,
                initialIndexingCompleted = true,
                lastSyncAt = System.currentTimeMillis(),
                lastMediaStoreGeneration = currentGeneration,
            ),
        )

        dao.upsertScanSession(
            ScanSessionEntity(
                scanId = scanId,
                folderUri = folderKey,
                startedAt = scanStartedAt,
                completedAt = System.currentTimeMillis(),
                status = ScanSessionStatus.COMPLETED.name,
                discoveredCount = discoveredCount,
                indexedCount = totalIndexedCount,
                failedCount = failedFilesCount,
            ),
        )
        StorageLog.info("Scanner", "MediaStore scan (delta=$isDeltaQuery) completed in ${System.currentTimeMillis() - scanStartedAt}ms: indexed=$totalIndexedCount added=$addedCount removed=${missingIds.size} failed=$failedFilesCount")

        val state = LibraryScanState(
            isScanning = false,
            scannedFiles = finalTrackCount,
            totalFiles = finalTrackCount,
            addedCount = addedCount,
            removedCount = missingIds.size,
            lastMessage = if (silentIncremental && addedCount == 0 && missingIds.isEmpty()) null else "Indexed $finalTrackCount MediaStore tracks ($addedCount new).",
        )
        _scanState.value = state
        state
    }

    private fun resolveRootDocumentId(treeUri: Uri): String? =
        runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: runCatching { DocumentsContract.getDocumentId(treeUri) }.getOrNull()
            ?: treeUri.lastPathSegment

    private fun queryMediaStoreForTreeUri(
        treeUri: Uri,
        rootDocId: String,
        includeSubfolders: Boolean,
    ): List<DiscoveredDocFile> {
        // NEVER fall back to MediaStore for non-primary, removable USB/SD, or third-party/revoked SAF providers.
        if (treeUri.authority != "com.android.externalstorage.documents" ||
            !rootDocId.startsWith("primary:", ignoreCase = true)
        ) {
            return emptyList()
        }
        val relativeDir = rootDocId.substringAfter(':', "").trim('/')
        if (relativeDir.isEmpty()) return emptyList()
        val results = mutableListOf<DiscoveredDocFile>()
        runCatching {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATE_MODIFIED,
                MediaStore.Audio.Media.RELATIVE_PATH,
            )
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val modCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
                val relCol = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    val mediaId = cursor.getLong(idCol)
                    val displayName = cursor.getString(nameCol) ?: "audio_$mediaId"
                    val size = cursor.getLong(sizeCol)
                    val modSec = if (modCol >= 0) cursor.getLong(modCol) else 0L
                    val relPath = (if (relCol >= 0) cursor.getString(relCol) else null)?.trim('/').orEmpty()

                    val matchesFolder = if (relativeDir.isEmpty()) {
                        true
                    } else if (includeSubfolders) {
                        relPath.equals(relativeDir, ignoreCase = true) ||
                            relPath.startsWith("$relativeDir/", ignoreCase = true)
                    } else {
                        relPath.equals(relativeDir, ignoreCase = true)
                    }

                    if (matchesFolder) {
                        val subRel = when {
                            relativeDir.isEmpty() -> relPath
                            relPath.equals(relativeDir, ignoreCase = true) -> ""
                            relPath.startsWith("$relativeDir/", ignoreCase = true) ->
                                relPath.substring(relativeDir.length + 1).trim('/')
                            else -> ""
                        }
                        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId)
                        results += DiscoveredDocFile(
                            uri = uri,
                            displayName = displayName,
                            sizeBytes = size,
                            lastModified = modSec * 1000L,
                            relativeFolderPath = subRel,
                        )
                    }
                }
            }
        }
        return results
    }

    private data class DirWalkNode(
        val docId: String,
        val relativeFolder: String,
        val depth: Int,
    )

    private data class DiscoveredDocFile(
        val uri: Uri,
        val displayName: String,
        val sizeBytes: Long,
        val lastModified: Long,
        val relativeFolderPath: String = "",
        val sidecarLyricsUri: Uri? = null,
        val folderCoverUri: Uri? = null,
    )

    private data class ExtractedLibraryBundle(
        val host: HostEntity,
        val category: CategoryEntity,
        val podcast: PodcastEntity,
        val episode: EpisodeEntity,
    )

    private class ScanBatchWriter(
        private val dao: PodcastyDao,
        private val onFlushed: (() -> Unit)?,
    ) {
        private val episodes = ArrayList<EpisodeEntity>(ScanBatchSize)
        private val hosts = LinkedHashMap<String, HostEntity>(ScanBatchSize)
        private val categories = LinkedHashMap<String, CategoryEntity>(ScanBatchSize)
        private val podcasts = LinkedHashMap<String, PodcastEntity>(ScanBatchSize)

        fun add(bundle: ExtractedLibraryBundle) {
            episodes += bundle.episode
            hosts[bundle.host.id] = bundle.host
            categories[bundle.category.id] = bundle.category
            podcasts[bundle.podcast.id] = bundle.podcast
        }

        fun addEpisode(episode: EpisodeEntity) {
            episodes += episode
        }

        suspend fun flushIfNeeded(): Boolean {
            if (episodes.size < ScanBatchSize) return false
            flush()
            return true
        }

        suspend fun flush() {
            if (episodes.isEmpty() && hosts.isEmpty() && categories.isEmpty() && podcasts.isEmpty()) return
            dao.insertScanBatch(
                hosts = hosts.values.toList(),
                categories = categories.values.toList(),
                podcasts = podcasts.values.toList(),
                episodes = episodes.toList(),
            )
            episodes.clear()
            hosts.clear()
            categories.clear()
            podcasts.clear()
            onFlushed?.invoke()
        }
    }

    private fun extractMetadataForDocument(
        file: DiscoveredDocFile,
        folderUri: String,
        stableId: String,
        existing: EpisodeEntity?,
        now: Long,
        index: Int,
        folderCoverMemo: MutableMap<String, String?>? = null,
        forceReextractArtwork: Boolean = false,
    ): ExtractedLibraryBundle {
        val detected = AudioFormatCatalog.detectFromUri(context, file.uri, file.displayName)
        val fallbackTitle = file.displayName.substringBeforeLast('.')

        var title = fallbackTitle
        var artist = "Unknown Artist"
        var album = "Local Audio"
        var albumArtist: String? = null
        var genre = "Local Music"
        var composer: String? = null
        var comment: String? = null
        var lyrics: String? = existing?.transcriptLrc
        var year = 0
        var trackNumber = index + 1
        var discNumber = 1
        var durationMs = existing?.durationMs ?: 0L
        var sampleRate = 0
        var bitDepth = 0
        var bitrateKbps = 0
        var channels = 0
        val fileModifiedOrResized = existing != null && (
            existing.fileSizeBytes != file.sizeBytes ||
                (file.lastModified > 0L && existing.lastModifiedMs != file.lastModified)
            )
        var artworkPath = if (forceReextractArtwork || fileModifiedOrResized) {
            null
        } else {
            existing?.artworkAsset?.takeIf {
                !it.startsWith(context.cacheDir.absolutePath) && LocalArtworkResolver.isUsableCachedArtworkFile(it)
            }
        }

        var extractedArtworkViaResolver = artworkPath != null
        runCatching {
            context.contentResolver.openFileDescriptor(file.uri, "r")?.use { pfd ->
                val props = runCatching {
                    LocalArtworkResolver.rewindFd(pfd)
                    TagLib.getAudioProperties(pfd.dup().detachFd(), AudioPropertiesReadStyle.Fast)
                }.getOrNull()
                if (props != null) {
                    if (props.length > 0) durationMs = props.length.toLong()
                    if (props.sampleRate > 0) sampleRate = props.sampleRate
                    if (props.bitrate > 0) bitrateKbps = props.bitrate
                    if (props.channels > 0) channels = props.channels
                }
                val meta = runCatching {
                    LocalArtworkResolver.rewindFd(pfd)
                    TagLib.getMetadata(pfd.dup().detachFd(), /* readPictures = */ !extractedArtworkViaResolver)
                }.getOrNull()
                if (meta != null) {
                    val map = meta.propertyMap
                    fun tagValue(key: String): String? = runCatching {
                        val values: Array<String>? = map[key]
                        values?.firstOrNull { v -> @Suppress("SENSELESS_COMPARISON") (v != null && v.isNotBlank()) }
                    }.getOrNull()

                    tagValue("TITLE")?.let { title = it }
                    tagValue("ARTIST")?.let { artist = it }
                    tagValue("ALBUM")?.let { album = it }
                    tagValue("ALBUMARTIST")?.let { albumArtist = it }
                    tagValue("GENRE")?.let { genre = it }
                    tagValue("COMPOSER")?.let { composer = it }
                    tagValue("COMMENT")?.let { comment = it }
                    val lyricsCandidate = runCatching {
                        map.entries
                            .firstOrNull { (k, v) ->
                                val keyStr: String? = k
                                val vals: Array<String>? = v
                                if (keyStr == null || vals == null) return@firstOrNull false
                                val upper = keyStr.uppercase()
                                (upper == "LYRICS" || upper == "UNSYNCEDLYRICS" || upper == "SYLT" ||
                                    upper.startsWith("LYRICS") || upper.startsWith("USLT")) &&
                                    vals.any { s -> @Suppress("SENSELESS_COMPARISON") (s != null && s.isNotBlank()) }
                            }
                            ?.value
                            ?.firstOrNull { s -> @Suppress("SENSELESS_COMPARISON") (s != null && s.isNotBlank()) }
                    }.getOrNull()
                    if (!lyricsCandidate.isNullOrBlank()) {
                        lyrics = lyricsCandidate
                    }
                    tagValue("DATE")?.take(4)?.toIntOrNull()?.let { year = it }
                    tagValue("TRACKNUMBER")?.substringBefore('/')?.toIntOrNull()?.let { trackNumber = it }
                    tagValue("DISCNUMBER")?.substringBefore('/')?.toIntOrNull()?.let { discNumber = it }
                }
                if (!extractedArtworkViaResolver) {
                    val metaPictures = runCatching { meta?.pictures }.getOrNull()
                    artworkPath = artworkResolver.resolveAndCacheArtwork(
                        audioUri = file.uri,
                        existingFileDescriptor = pfd,
                        folderCoverUri = file.folderCoverUri,
                        folderCoverMemo = folderCoverMemo,
                        preExtractedPictures = metaPictures?.takeIf { it.isNotEmpty() },
                    )
                    extractedArtworkViaResolver = true
                }
            }
        }

        if (!extractedArtworkViaResolver) {
            artworkPath = artworkResolver.resolveAndCacheArtwork(
                audioUri = file.uri,
                existingFileDescriptor = null,
                folderCoverUri = file.folderCoverUri,
                folderCoverMemo = folderCoverMemo,
            )
            extractedArtworkViaResolver = true
        }

        if (durationMs <= 0L) {
            val retriever = MediaMetadataRetriever()
            try {
                runCatching {
                    retriever.setDataSource(context, file.uri)
                    if (durationMs <= 0L) {
                        durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    }
                    if (title == fallbackTitle) {
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let { title = it }
                    }
                    if (artist == "Unknown Artist") {
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }?.let { artist = it }
                    }
                    if (album == "Local Audio") {
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }?.let { album = it }
                    }
                    if (bitrateKbps <= 0) {
                        val bps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
                        if (bps > 0) bitrateKbps = bps / 1000
                    }
                }
            } finally {
                runCatching { retriever.release() }
            }
        }
        if (existing?.artworkAsset != artworkPath) {
            com.podcasty.app.ui.components.invalidateArtworkCache(existing?.artworkAsset)
            com.podcasty.app.ui.components.invalidateArtworkCache(artworkPath)
        }

        val currentLyrics = lyrics
        if (currentLyrics.isNullOrBlank() || !com.podcasty.app.transcript.TranscriptParser.parse(stableId, currentLyrics).isTimed) {
            val syltTrack = runCatching {
                context.contentResolver.openInputStream(file.uri)?.use { input ->
                    com.podcasty.app.transcript.Id3SyltParser.extractFromStream(input)
                }
            }.getOrNull()
            if (syltTrack != null && syltTrack.cues.isNotEmpty()) {
                lyrics = com.podcasty.app.transcript.TranscriptParser.toLrcString(syltTrack.cues)
            }
        }

        val postSyltLyrics = lyrics
        if (file.sidecarLyricsUri != null &&
            (postSyltLyrics.isNullOrBlank() || !com.podcasty.app.transcript.TranscriptParser.parse(stableId, postSyltLyrics).isTimed)
        ) {
            runCatching {
                context.contentResolver.openInputStream(file.sidecarLyricsUri)?.use { input ->
                    val rawBytes = input.readBytes()
                    val decoded = com.podcasty.app.transcript.TranscriptParser.decodeBytesToUnicode(rawBytes)
                    if (decoded.isNotBlank()) {
                        val parsedDoc = com.podcasty.app.transcript.TranscriptParser.parse(
                            episodeId = stableId,
                            content = decoded,
                            fileName = file.sidecarLyricsUri.lastPathSegment,
                        )
                        lyrics = if (parsedDoc.cues.isNotEmpty() && parsedDoc.isTimed) {
                            com.podcasty.app.transcript.TranscriptParser.toLrcString(parsedDoc.cues)
                        } else if (lyrics.isNullOrBlank()) {
                            decoded
                        } else {
                            lyrics
                        }
                    }
                }
            }
        }
        val detectedLyricsLang = lyrics?.takeIf { it.isNotBlank() }?.let {
            com.podcasty.app.transcript.TranscriptParser.detectScriptLanguage(it)
        }
        val safeTitle = title
        val safeArtist = artist
        val safeAlbum = album
        val safeGenre = genre
        val effectiveArtist = albumArtist?.takeIf { it.isNotBlank() } ?: safeArtist
        val normTitle = UnicodeSortUtil.normalizeKey(safeTitle)
        val normArtist = UnicodeSortUtil.normalizeKey(effectiveArtist)
        val normAlbum = UnicodeSortUtil.normalizeKey(safeAlbum)

        val hostId = "local_host_${shortHash(normArtist)}"
        val categoryId = "local_cat_${shortHash(UnicodeSortUtil.normalizeKey(safeGenre))}"
        val podcastId = "local_album_${shortHash("$normArtist::$normAlbum")}"
        val isHighRes = detected.isHighResCandidate &&
            (sampleRate >= 48_000 || bitDepth >= 24 || detected.formatId in setOf("flac", "alac", "wav", "aiff", "dsd", "pcm"))

        val host = HostEntity(
            id = hostId,
            name = effectiveArtist,
            bio = "Local library artist",
            artworkAsset = artworkPath,
            createdAt = now,
        )
        val category = CategoryEntity(
            id = categoryId,
            name = safeGenre,
            createdAt = now,
        )
        val podcast = PodcastEntity(
            id = podcastId,
            title = safeAlbum,
            hostId = hostId,
            hostName = effectiveArtist,
            categoryId = categoryId,
            categoryName = safeGenre,
            description = "Local album · ${detected.displayName}",
            publisher = effectiveArtist,
            artworkAsset = artworkPath,
            isSubscribed = true,
            isRecommended = true,
            isPopular = true,
            addedAt = file.lastModified.takeIf { it > 0L } ?: now,
            sourceType = MediaSourceType.LocalMusic.key,
            contentType = MediaContentType.Music.key,
            importSource = MediaImportSource.LocalLibrary.key,
            importedAt = file.lastModified.takeIf { it > 0L } ?: now,
        )
        val cleanRel = file.relativeFolderPath.trim('/')
        val episode = EpisodeEntity(
            id = stableId,
            podcastId = podcastId,
            podcastTitle = safeAlbum,
            hostId = hostId,
            hostName = safeArtist,
            categoryId = categoryId,
            categoryName = safeGenre,
            episodeNumber = trackNumber,
            title = safeTitle,
            summary = comment ?: "Local ${detected.displayName} file (${detected.codec})",
            artworkAsset = artworkPath,
            audioAsset = if (cleanRel.isNotEmpty()) "$cleanRel/${file.displayName}" else file.displayName,
            durationMs = durationMs.coerceAtLeast(0L),
            playbackPositionMs = existing?.playbackPositionMs ?: 0L,
            isPlayed = existing?.isPlayed ?: false,
            isSaved = existing?.isSaved ?: false,
            isDownloaded = true,
            fileSizeBytes = file.sizeBytes,
            playCount = existing?.playCount ?: 0,
            publishedAt = file.lastModified.takeIf { it > 0L } ?: now,
            lastPlayedAt = existing?.lastPlayedAt,
            transcriptLrc = lyrics,
            sourceType = MediaSourceType.LocalMusic.key,
            sourceUri = file.uri.toString(),
            youtubeUrl = existing?.youtubeUrl,
            linkedAudioUri = existing?.linkedAudioUri,
            folderUri = folderUri,
            containerFormat = detected.displayName,
            audioCodec = detected.codec,
            sampleRateHz = sampleRate,
            bitDepth = bitDepth,
            bitrateKbps = bitrateKbps,
            channels = channels,
            isHighRes = isHighRes,
            year = year,
            trackNumber = trackNumber,
            discNumber = discNumber,
            albumArtist = albumArtist,
            composer = composer,
            comment = comment,
            transcriptLanguage = detectedLyricsLang ?: existing?.transcriptLanguage ?: "en",
            relativeFolderPath = cleanRel,
            lastModifiedMs = file.lastModified,
            normalizedTitle = normTitle,
            normalizedArtist = UnicodeSortUtil.normalizeKey(safeArtist),
            normalizedAlbum = normAlbum,
            contentType = MediaContentType.Music.key,
            importSource = MediaImportSource.LocalLibrary.key,
            importedAt = existing?.importedAt?.takeIf { it > 0L } ?: (file.lastModified.takeIf { it > 0L } ?: now),
        )
        return ExtractedLibraryBundle(host, category, podcast, episode)
    }

    companion object {
        private const val ScanBatchSize = 250
        private const val MaxDirectoryDepth = 32
        private const val MaxDirectoriesPerScan = 25_000
        private const val ProgressEmitIntervalMs = 300L
        private val SidecarExtensions = setOf("lrc", "srt", "vtt")

        fun stableLocalEpisodeId(uriString: String): String = "local_${shortHash(uriString)}"

        private fun shortHash(input: String): String =
            shortHashBytes(input.toByteArray(Charsets.UTF_8))

        private fun shortHashBytes(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}
