/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "hosts")
data class HostEntity(
    @PrimaryKey val id: String,
    val name: String,
    val bio: String,
    val artworkAsset: String?,
    val createdAt: Long,
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
)

@Entity(
    tableName = "podcasts",
    indices = [
        Index(value = ["contentType", "importedAt"]),
        Index(value = ["contentType", "title"]),
    ],
)
data class PodcastEntity(
    @PrimaryKey val id: String,
    val title: String,
    val hostId: String,
    val hostName: String,
    val categoryId: String,
    val categoryName: String,
    val description: String,
    val publisher: String,
    val artworkAsset: String?,
    val isSubscribed: Boolean,
    val isRecommended: Boolean,
    val isPopular: Boolean,
    val addedAt: Long,
    val feedUrl: String? = null,
    @ColumnInfo(defaultValue = "'demo'") val sourceType: String = "demo",
    val youtubeVideoId: String? = null,
    @ColumnInfo(defaultValue = "'en-US'") val preferredCaptionLanguage: String = "en-US",
    @ColumnInfo(defaultValue = "'MUSIC'") val contentType: String = "MUSIC",
    @ColumnInfo(defaultValue = "'local_library'") val importSource: String = "local_library",
    @ColumnInfo(defaultValue = "0") val importedAt: Long = 0L,
)

@Entity(
    tableName = "episodes",
    indices = [
        Index(value = ["sourceType"]),
        Index(value = ["folderUri"]),
        Index(value = ["folderUri", "relativeFolderPath"]),
        Index(value = ["podcastId"]),
        Index(value = ["hostId"]),
        Index(value = ["publishedAt"]),
        Index(value = ["lastPlayedAt"]),
        Index(value = ["normalizedTitle"]),
        Index(value = ["normalizedArtist"]),
        Index(value = ["normalizedAlbum"]),
        Index(value = ["sourceType", "publishedAt"]),
        Index(value = ["sourceType", "lastPlayedAt"]),
        Index(value = ["sourceType", "normalizedTitle"]),
        Index(value = ["sourceType", "normalizedArtist", "normalizedTitle"]),
        Index(value = ["sourceType", "normalizedAlbum", "normalizedTitle"]),
        Index(value = ["sourceType", "durationMs"]),
        Index(value = ["isSaved", "publishedAt"]),
        Index(value = ["isDownloaded", "publishedAt"]),
        Index(value = ["playCount"]),
        Index(value = ["isHighRes", "sampleRateHz"]),
        Index(value = ["playCount", "isPlayed", "publishedAt"]),
        Index(value = ["contentType", "importedAt"]),
        Index(value = ["contentType", "publishedAt"]),
        Index(value = ["contentType", "podcastId"]),
        Index(value = ["contentType", "normalizedAlbum"]),
    ],
)
data class EpisodeEntity(
    @PrimaryKey val id: String,
    val podcastId: String,
    val podcastTitle: String,
    val hostId: String,
    val hostName: String,
    val categoryId: String,
    val categoryName: String,
    val episodeNumber: Int,
    val title: String,
    val summary: String,
    val artworkAsset: String?,
    val audioAsset: String,
    val durationMs: Long,
    val playbackPositionMs: Long,
    val isPlayed: Boolean,
    val isSaved: Boolean,
    val isDownloaded: Boolean,
    val fileSizeBytes: Long,
    val playCount: Int,
    val publishedAt: Long,
    val lastPlayedAt: Long?,
    val transcriptLrc: String?,
    @ColumnInfo(defaultValue = "'demo'") val sourceType: String = "demo",
    val sourceUri: String? = null,
    val youtubeUrl: String? = null,
    val linkedAudioUri: String? = null,
    val folderUri: String? = null,
    val containerFormat: String? = null,
    val audioCodec: String? = null,
    @ColumnInfo(defaultValue = "0") val sampleRateHz: Int = 0,
    @ColumnInfo(defaultValue = "0") val bitDepth: Int = 0,
    @ColumnInfo(defaultValue = "0") val bitrateKbps: Int = 0,
    @ColumnInfo(defaultValue = "0") val channels: Int = 0,
    @ColumnInfo(defaultValue = "0") val isHighRes: Boolean = false,
    @ColumnInfo(defaultValue = "0") val year: Int = 0,
    @ColumnInfo(defaultValue = "0") val trackNumber: Int = 0,
    @ColumnInfo(defaultValue = "0") val discNumber: Int = 0,
    val albumArtist: String? = null,
    val composer: String? = null,
    val comment: String? = null,
    @ColumnInfo(defaultValue = "'en'") val transcriptLanguage: String = "en",
    @ColumnInfo(defaultValue = "''") val relativeFolderPath: String = "",
    @ColumnInfo(defaultValue = "0") val lastModifiedMs: Long = 0L,
    @ColumnInfo(defaultValue = "''") val normalizedTitle: String = "",
    @ColumnInfo(defaultValue = "''") val normalizedArtist: String = "",
    @ColumnInfo(defaultValue = "''") val normalizedAlbum: String = "",
    @ColumnInfo(defaultValue = "'MUSIC'") val contentType: String = "MUSIC",
    @ColumnInfo(defaultValue = "'local_library'") val importSource: String = "local_library",
    @ColumnInfo(defaultValue = "0") val importedAt: Long = 0L,
)

@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val isSystem: Boolean,
    val customArtworkPath: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "collection_episodes",
    primaryKeys = ["collectionId", "episodeId"],
)
data class CollectionEpisodeEntity(
    val collectionId: String,
    val episodeId: String,
    val position: Int,
)

@Entity(tableName = "listening_history")
data class ListeningHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val episodeId: String,
    val episodeTitle: String,
    val podcastId: String,
    val podcastTitle: String,
    val hostId: String,
    val hostName: String,
    val categoryName: String,
    val artworkAsset: String?,
    val listenedSeconds: Int,
    val completed: Boolean,
    val skipped: Boolean,
    val playedAt: Long,
)

@Entity(tableName = "music_folders")
data class MusicFolderEntity(
    @PrimaryKey val treeUri: String,
    val displayName: String,
    val storageType: String,
    val isDefault: Boolean,
    val includeSubfolders: Boolean,
    val lastScannedAt: Long,
    val trackCount: Int,
    val isAvailable: Boolean,
    val isEnabled: Boolean = true,
    val initialIndexingCompleted: Boolean = false,
    val lastSyncAt: Long = 0L,
    val lastMediaStoreGeneration: Long = 0L,
)

@Entity(
    tableName = "transcripts",
    indices = [Index(value = ["episodeId", "language"], unique = true)],
)
data class TranscriptEntity(
    @PrimaryKey val id: String,
    val episodeId: String,
    val language: String,
    val format: String,
    val source: String,
    val rawContent: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "transcript_cues",
    indices = [Index(value = ["transcriptId", "cueIndex"]), Index(value = ["episodeId"])],
)
data class TranscriptCueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val transcriptId: String,
    val episodeId: String,
    val cueIndex: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val speaker: String?,
)

@Entity(
    tableName = "bookmarks",
    indices = [Index(value = ["episodeId"])],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val episodeId: String,
    val positionMs: Long,
    val label: String,
    val createdAt: Long,
)

@Entity(tableName = "raw_pcm_configs")
data class RawPcmConfigEntity(
    @PrimaryKey val sourceUri: String,
    val sampleRateHz: Int,
    val bitDepth: Int,
    val channelCount: Int,
    val bigEndian: Boolean,
    val signedPcm: Boolean,
)

@Entity(
    tableName = "live_captions",
    indices = [
        Index(value = ["episodeId"]),
        Index(value = ["episodeId", "startMs"]),
    ],
)
data class LiveCaptionEntity(
    @PrimaryKey val id: String,
    val episodeId: String,
    val sessionId: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val language: String,
    val isEstimatedTiming: Boolean,
    val createdAt: Long,
)

@Entity(tableName = "excluded_folders")
data class ExcludedFolderEntity(
    @PrimaryKey val folderKey: String,
    val displayName: String,
    val createdAt: Long,
)

@Entity(tableName = "music_queues")
data class MusicQueueEntity(
    @PrimaryKey val id: String,
    val name: String,
    val isActive: Boolean,
    val shuffle: Boolean = false,
    val repeatMode: String = "Off",
    val currentEpisodeId: String? = null,
    val currentPositionMs: Long = 0L,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "music_queue_items",
    primaryKeys = ["queueId", "episodeId"],
    indices = [
        Index(value = ["queueId", "position"]),
        Index(value = ["episodeId"]),
    ],
)
data class MusicQueueItemEntity(
    val queueId: String,
    val episodeId: String,
    val position: Int,
)

/**
 * Lightweight list projection omitting large `transcriptLrc` blobs so 5k-25k+ libraries
 * never pin megabytes of lyric text in RAM across ViewModels or UI lists.
 */
data class EpisodeSummaryTuple(
    val id: String,
    val podcastId: String,
    val podcastTitle: String,
    val hostId: String,
    val hostName: String,
    val categoryId: String,
    val categoryName: String,
    val episodeNumber: Int,
    val title: String,
    val summary: String,
    val artworkAsset: String?,
    val audioAsset: String,
    val durationMs: Long,
    val playbackPositionMs: Long,
    val isPlayed: Boolean,
    val isSaved: Boolean,
    val isDownloaded: Boolean,
    val fileSizeBytes: Long,
    val playCount: Int,
    val publishedAt: Long,
    val lastPlayedAt: Long?,
    val sourceType: String,
    val sourceUri: String?,
    val youtubeUrl: String?,
    val linkedAudioUri: String?,
    val folderUri: String?,
    val containerFormat: String?,
    val audioCodec: String?,
    val sampleRateHz: Int,
    val bitDepth: Int,
    val bitrateKbps: Int,
    val channels: Int,
    val isHighRes: Boolean,
    val year: Int,
    val trackNumber: Int,
    val discNumber: Int,
    val albumArtist: String?,
    val composer: String?,
    val comment: String?,
    val transcriptLanguage: String,
    val relativeFolderPath: String,
    val lastModifiedMs: Long,
    val contentType: String,
    val importSource: String,
    val importedAt: Long,
)

data class ExistingTrackStamp(
    val id: String,
    val fileSizeBytes: Long,
    val lastModifiedMs: Long,
    val durationMs: Long,
    val artworkAsset: String?,
    val audioAsset: String,
    val relativeFolderPath: String,
    val hasLyrics: Boolean,
)

data class TrackDedupSignature(
    val id: String,
    val fileSizeBytes: Long,
    val audioAsset: String,
)

data class LinearFolderRow(
    val folderUri: String,
    val relativeFolderPath: String,
    val trackCount: Int,
    val totalDurationMs: Long,
    val latestAddedAt: Long,
)

data class AlbumRow(
    val albumId: String,
    val albumTitle: String,
    val artistName: String,
    val trackCount: Int,
    val totalDurationMs: Long,
    val artworkAsset: String?,
)

data class ArtistRow(
    val artistId: String,
    val artistName: String,
    val albumCount: Int,
    val trackCount: Int,
    val artworkAsset: String?,
)

@Entity(
    tableName = "scan_sessions",
    indices = [
        Index(value = ["folderUri", "startedAt"]),
        Index(value = ["status"]),
    ],
)
data class ScanSessionEntity(
    @PrimaryKey val scanId: String,
    val folderUri: String,
    val startedAt: Long,
    val completedAt: Long? = null,
    val status: String,
    val discoveredCount: Int = 0,
    val indexedCount: Int = 0,
    val failedCount: Int = 0,
    val lastCheckpointPath: String? = null,
    val errorMessage: String? = null,
)

@Entity(tableName = "equalizer_presets")
data class EqualizerPresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val bandLevelsMbCsv: String,
    val bandFrequenciesHzCsv: String,
    val preampDb: Float = 0f,
    val bassBoostStrength: Int = 0,
    val virtualizerStrength: Int = 0,
    val balance: Float = 0f,
    val monoAudio: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)
