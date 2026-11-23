/*
 * Podcasty — Android Podcast & Music Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.domain

import kotlinx.serialization.Serializable

enum class SortOrder { Ascending, Descending }

enum class EpisodeSortOption { Title, Podcast, Duration, DatePublished, PlayCount }

enum class PodcastSortOption { Title, Host, DateAdded }

enum class AlbumSortOption { Title, Artist, TrackCount }

enum class PodcastLayoutMode { List, Grid }

enum class HostSortOption { Name, DateAdded }

enum class CategorySortOption { Name, DateAdded }

enum class FolderViewMode { Linear, Hierarchical }

enum class FolderSortOption { Name, TrackCount, RecentlyAdded }

enum class SongSortOption { Title, Artist, Album, Duration, DateAdded }

enum class MusicLibraryCategory(val label: String) {
    Folders("Folders"),
    Albums("Albums"),
    Artists("Artists"),
    Songs("Songs"),
    Playlists("Playlists"),
}

data class LinearFolderSummary(
    val folderKey: String,
    val treeUri: String,
    val relativePath: String,
    val folderName: String,
    val displayPath: String,
    val trackCount: Int,
    val totalDurationMs: Long,
    val latestAddedAt: Long,
    val storageType: StorageProviderType,
)

data class AlbumSummary(
    val albumId: String,
    val albumTitle: String,
    val artistName: String,
    val trackCount: Int,
    val totalDurationMs: Long,
    val artworkAsset: String?,
)

data class ArtistSummary(
    val artistId: String,
    val artistName: String,
    val albumCount: Int,
    val trackCount: Int,
    val artworkAsset: String?,
)

data class ExcludedFolder(
    val folderKey: String,
    val displayName: String,
    val createdAt: Long,
)

data class MusicQueue(
    val id: String,
    val name: String,
    val isActive: Boolean,
    val shuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.Off,
    val currentEpisodeId: String? = null,
    val currentPositionMs: Long = 0L,
    val episodeIds: List<String> = emptyList(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    companion object {
        const val DefaultQueueId = "queue_main"
        const val DefaultQueueName = "Main Queue"
    }
}

enum class PlaybackStatus { Unplayed, InProgress, Played }

enum class DownloadStatus { NotDownloaded, Downloaded }

enum class MediaSourceType(val key: String) {
    Demo("demo"),
    LocalMusic("local_music"),
    YouTubeReference("youtube_ref"),
    RssPodcast("rss_podcast"),
    ;

    companion object {
        fun fromKey(key: String?): MediaSourceType =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: Demo
    }
}

enum class MediaContentType(val key: String) {
    Music("MUSIC"),
    Podcast("PODCAST"),
    ;

    companion object {
        fun fromKey(key: String?): MediaContentType =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: Music

        fun inferFromSourceType(sourceType: MediaSourceType): MediaContentType = when (sourceType) {
            MediaSourceType.LocalMusic -> Music
            MediaSourceType.YouTubeReference,
            MediaSourceType.RssPodcast,
            MediaSourceType.Demo,
            -> Podcast
        }

        fun inferFromSourceTypeKey(sourceTypeKey: String?): MediaContentType =
            inferFromSourceType(MediaSourceType.fromKey(sourceTypeKey))
    }
}

enum class MediaImportSource(val key: String) {
    LocalLibrary("local_library"),
    YouTube("youtube"),
    Rss("rss"),
    DirectAudio("direct_audio"),
    GoogleTranslation("google_translation"),
    Seed("seed"),
    ;

    companion object {
        fun fromKey(key: String?): MediaImportSource =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: LocalLibrary
    }
}

enum class StorageProviderType(val key: String, val label: String) {
    Internal("internal", "Internal storage"),
    SdCard("sd_card", "External SD card"),
    UsbOtg("usb_otg", "USB OTG storage"),
    MediaStore("mediastore", "Device media store"),
    ;

    companion object {
        fun fromKey(key: String?): StorageProviderType =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: Internal
    }
}

data class MusicFolder(
    val treeUri: String,
    val displayName: String,
    val storageType: StorageProviderType = StorageProviderType.Internal,
    val isDefault: Boolean = false,
    val includeSubfolders: Boolean = true,
    val lastScannedAt: Long = 0L,
    val trackCount: Int = 0,
    val isAvailable: Boolean = true,
    val isEnabled: Boolean = true,
    val initialIndexingCompleted: Boolean = false,
    val lastSyncAt: Long = 0L,
    val lastMediaStoreGeneration: Long = 0L,
)

data class RawPcmConfig(
    val sourceUri: String,
    val sampleRateHz: Int = 44_100,
    val bitDepth: Int = 16,
    val channelCount: Int = 2,
    val bigEndian: Boolean = false,
    val signedPcm: Boolean = true,
)

data class Bookmark(
    val id: Long = 0L,
    val episodeId: String,
    val positionMs: Long,
    val label: String,
    val createdAt: Long,
)

data class Host(
    val id: String,
    val name: String,
    val bio: String,
    val artworkAsset: String?,
    val createdAt: Long = 0L,
)

data class Category(
    val id: String,
    val name: String,
    val createdAt: Long = 0L,
)

data class Podcast(
    val id: String,
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
    val sourceType: MediaSourceType = MediaSourceType.Demo,
    val youtubeVideoId: String? = null,
    val preferredCaptionLanguage: String = "en-US",
    val contentType: MediaContentType = MediaContentType.inferFromSourceType(sourceType),
    val importSource: String = if (sourceType == MediaSourceType.LocalMusic) MediaImportSource.LocalLibrary.key else MediaImportSource.Seed.key,
    val importedAt: Long = addedAt,
)

data class Episode(
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
    val transcriptLrc: String?,
    val sourceType: MediaSourceType = MediaSourceType.Demo,
    val sourceUri: String? = null,
    val youtubeUrl: String? = null,
    val linkedAudioUri: String? = null,
    val folderUri: String? = null,
    val containerFormat: String? = null,
    val audioCodec: String? = null,
    val sampleRateHz: Int = 0,
    val bitDepth: Int = 0,
    val bitrateKbps: Int = 0,
    val channels: Int = 0,
    val isHighRes: Boolean = false,
    val year: Int = 0,
    val trackNumber: Int = 0,
    val discNumber: Int = 0,
    val albumArtist: String? = null,
    val composer: String? = null,
    val comment: String? = null,
    val transcriptLanguage: String = "en",
    val contentType: MediaContentType = MediaContentType.inferFromSourceType(sourceType),
    val importSource: String = if (sourceType == MediaSourceType.LocalMusic) MediaImportSource.LocalLibrary.key else MediaImportSource.Seed.key,
    val importedAt: Long = publishedAt,
) {
    val isPodcastContent: Boolean
        get() = contentType == MediaContentType.Podcast

    val isMusicContent: Boolean
        get() = contentType == MediaContentType.Music
    val playbackStatus: PlaybackStatus
        get() = when {
            isPlayed -> PlaybackStatus.Played
            playbackPositionMs > 3_000L -> PlaybackStatus.InProgress
            else -> PlaybackStatus.Unplayed
        }

    val remainingMs: Long
        get() = (durationMs - playbackPositionMs).coerceAtLeast(0L)

    val progressFraction: Float
        get() = if (durationMs > 0L) (playbackPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    val isLocalAudioFile: Boolean
        get() = (sourceType == MediaSourceType.LocalMusic && !sourceUri.isNullOrBlank()) ||
            (linkedAudioUri?.startsWith("file://") == true)

    val editableAudioUri: String?
        get() = when {
            linkedAudioUri?.startsWith("file://") == true -> linkedAudioUri
            sourceType == MediaSourceType.LocalMusic -> sourceUri
            else -> null
        }

    val isYouTubeReference: Boolean
        get() = sourceType == MediaSourceType.YouTubeReference

    val effectivePlaybackUri: String?
        get() = when (sourceType) {
            MediaSourceType.Demo -> "asset:///$audioAsset"
            MediaSourceType.LocalMusic -> sourceUri
            MediaSourceType.RssPodcast -> sourceUri
            MediaSourceType.YouTubeReference -> linkedAudioUri?.takeIf { it.isNotBlank() }
        }

    val formatSummary: String
        get() {
            val parts = buildList {
                containerFormat?.takeIf { it.isNotBlank() }?.let(::add)
                audioCodec?.takeIf { it.isNotBlank() && !it.equals(containerFormat, ignoreCase = true) }?.let(::add)
                if (sampleRateHz > 0) add("${sampleRateHz / 1000f} kHz".replace(".0 ", " "))
                if (bitDepth > 0) add("${bitDepth}-bit")
                if (bitrateKbps > 0) add("$bitrateKbps kbps")
            }
            return if (parts.isNotEmpty()) {
                parts.joinToString(" · ")
            } else {
                when (sourceType) {
                    MediaSourceType.Demo -> "AAC · 24 kbps (Local demo)"
                    MediaSourceType.YouTubeReference -> if (linkedAudioUri != null) "YouTube + Linked RSS Audio" else "YouTube Audio Stream"
                    MediaSourceType.RssPodcast -> "RSS Audio Stream"
                    MediaSourceType.LocalMusic -> "Local Audio"
                }
            }
        }
}

data class PodcastCollection(
    val id: String,
    val name: String,
    val isSystem: Boolean,
    val episodeIds: List<String>,
    val customArtworkPath: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val subtitleOverride: String? = null,
) {
    companion object {
        const val SavedCollectionId = "system_saved_episodes"
        const val RecentlyPlayedCollectionId = "system_recently_played"
        const val MostPlayedCollectionId = "smart_most_played"
        const val RecentlyAddedCollectionId = "smart_recently_added"
        const val NeverPlayedCollectionId = "smart_never_played"
        const val RecentlyDownloadedCollectionId = "smart_recently_downloaded"
        const val HighResAudioCollectionId = "smart_high_res_audio"
        const val TopArtistsCollectionId = "smart_top_artists"
        const val LocalMusicCollectionId = "smart_local_music"
    }
}

data class ListeningHistoryEntry(
    val id: Long = 0L,
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

@Serializable
data class TranscriptLine(
    val timestampSeconds: Float?,
    val speaker: String? = null,
    val text: String,
)
