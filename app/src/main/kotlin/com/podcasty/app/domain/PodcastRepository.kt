/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.domain

import com.podcasty.app.asr.LiveCaptionSegment
import com.podcasty.app.podcast.ExtractedYouTubeTranscript
import com.podcasty.app.podcast.ParsedPodcastFeed
import com.podcasty.app.podcast.RssFeedImportResult
import com.podcasty.app.podcast.YouTubeReferenceMetadata
import com.podcasty.app.podcast.YouTubeVideoMetadata
import com.podcasty.app.tags.EditableAudioTags
import com.podcasty.app.transcript.TranscriptDocument
import com.podcasty.app.transcript.TranscriptSource
import kotlinx.coroutines.flow.Flow

interface PodcastRepository {
    val podcasts: Flow<List<Podcast>>
    val episodes: Flow<List<Episode>>
    val hosts: Flow<List<Host>>
    val categories: Flow<List<Category>>
    val collections: Flow<List<PodcastCollection>>
    val listeningHistory: Flow<List<ListeningHistoryEntry>>
    val musicFolders: Flow<List<MusicFolder>>

    suspend fun ensureSeeded()

    suspend fun getEpisodeById(episodeId: String): Episode?
    suspend fun getEpisodesByIds(episodeIds: List<String>): List<Episode>

    suspend fun setPodcastSubscribed(podcastId: String, subscribed: Boolean)
    suspend fun setEpisodeSaved(episodeId: String, saved: Boolean)
    suspend fun setEpisodeDownloaded(episodeId: String, downloaded: Boolean)
    suspend fun setMusicFolderEnabled(treeUri: String, enabled: Boolean)
    suspend fun clearAllDownloads()
    suspend fun restoreDemoDownloads()

    suspend fun updatePlaybackProgress(
        episodeId: String,
        positionMs: Long,
        durationMs: Long,
        markPlayed: Boolean = false,
    )

    suspend fun recordListeningSession(
        episode: Episode,
        listenedSeconds: Int,
        completed: Boolean,
        skipped: Boolean,
    )

    suspend fun clearListeningHistory()

    suspend fun createCollection(name: String, initialEpisodeIds: List<String> = emptyList()): String
    suspend fun renameCollection(collectionId: String, newName: String)
    suspend fun deleteCollection(collectionId: String)
    suspend fun setCollectionMembership(collectionId: String, episodeIds: List<String>, add: Boolean)
    suspend fun reorderCollectionEpisodes(collectionId: String, orderedEpisodeIds: List<String>)

    // YouTube reference & RSS podcast imports (policy-compliant)
    suspend fun saveYouTubeReferenceEpisode(
        metadata: YouTubeReferenceMetadata,
        linkedAudioUri: String? = null,
        initialTranscriptText: String? = null,
        transcriptFileName: String? = null,
    ): String

    suspend fun linkAudioToEpisode(episodeId: String, audioUri: String?)

    suspend fun saveImportedRssFeed(feed: RssFeedImportResult): String

    suspend fun saveParsedPodcastFeed(
        feed: ParsedPodcastFeed,
        preferredCaptionLanguage: String = "en-US",
    ): String

    suspend fun saveDirectAudioPodcast(
        audioUrl: String,
        title: String,
        creator: String,
        preferredCaptionLanguage: String = "en-US",
    ): Pair<String, String>

    suspend fun saveYouTubeVideoImport(
        metadata: YouTubeVideoMetadata,
        preferredCaptionLanguage: String = "en-US",
        linkedAudioUri: String? = null,
        extractedTranscript: ExtractedYouTubeTranscript? = null,
        isDownloadedFile: Boolean = false,
        downloadedFileSizeBytes: Long = 0L,
        localArtworkPath: String? = null,
        bitrateKbps: Int = 0,
        audioContainerFormat: String? = null,
        audioCodecLabel: String? = null,
        audioLanguageCode: String? = null,
    ): Pair<String, String>

    suspend fun deleteImportedPodcast(podcastId: String)

    // Real-time AI caption history
    suspend fun getLiveCaptions(episodeId: String): List<LiveCaptionSegment>
    suspend fun saveLiveCaptions(episodeId: String, segments: List<LiveCaptionSegment>)
    suspend fun clearLiveCaptions(episodeId: String)

    // Transcripts (multi-format, multi-language)
    fun observeTranscriptDocuments(episodeId: String): Flow<List<TranscriptDocument>>
    suspend fun getTranscriptDocuments(episodeId: String): List<TranscriptDocument>
    suspend fun saveTranscript(
        episodeId: String,
        rawContent: String,
        fileName: String? = null,
        language: String = "en",
        source: TranscriptSource = TranscriptSource.AuthorizedImport,
    ): TranscriptDocument

    // Bookmarks & Raw PCM parameters
    fun observeBookmarks(episodeId: String): Flow<List<Bookmark>>
    suspend fun addBookmark(episodeId: String, positionMs: Long, label: String): Long
    suspend fun deleteBookmark(bookmarkId: Long)

    suspend fun getRawPcmConfig(sourceUri: String): RawPcmConfig?
    suspend fun saveRawPcmConfig(config: RawPcmConfig)

    // Strict Podcasts vs Music Albums queries (contentType-separated & indexed)
    fun observePodcastShows(): Flow<List<Podcast>>
    fun observePodcastById(podcastId: String): Flow<Podcast?>
    fun observePodcastEpisodes(limit: Int = 200): Flow<List<Episode>>
    fun observePodcastEpisodeCount(): Flow<Int>
    fun observeEpisodesForPodcast(podcastId: String): Flow<List<Episode>>
    fun observeRecentMusicEpisodes(limit: Int = 16): Flow<List<Episode>>
    fun observeFavouriteMusicEpisodes(limit: Int = 16): Flow<List<Episode>>
    fun observeRecentlyPlayedEpisodes(limit: Int = 16): Flow<List<Episode>>
    fun observeLocalAlbumsSorted(
        query: String = "",
        sortOption: AlbumSortOption = AlbumSortOption.Title,
        sortOrder: SortOrder = SortOrder.Ascending,
        limit: Int = 200,
    ): Flow<List<AlbumSummary>>
    fun observeMusicAlbumById(albumId: String): Flow<AlbumSummary?>
    fun observeMusicAlbumCount(): Flow<Int>

    // Offline Local Music Library Queries (lightweight / SQL-sorted / paged)
    fun observeLocalMusicCount(): Flow<Int>
    fun observeLinearFolderSummaries(): Flow<List<LinearFolderSummary>>
    fun observeLocalAlbums(query: String = "", limit: Int = 200): Flow<List<AlbumSummary>>
    fun observeLocalArtists(query: String = "", limit: Int = 200): Flow<List<ArtistSummary>>
    fun observeSmartMusicCategory(category: String): Flow<List<Episode>>

    fun observeLocalSongsPaged(
        query: String = "",
        sortOption: SongSortOption = SongSortOption.Title,
        sortOrder: SortOrder = SortOrder.Ascending,
        limit: Int = 200,
        minDurationMs: Long = 0L,
        maxDurationMs: Long = Long.MAX_VALUE,
        format: String = "",
    ): Flow<List<Episode>>

    suspend fun getLocalSongIdsSorted(
        query: String = "",
        sortOption: SongSortOption = SongSortOption.Title,
        sortOrder: SortOrder = SortOrder.Ascending,
        limit: Int = 2_000,
    ): List<String>

    fun observeSongsForAlbum(albumId: String): Flow<List<Episode>>
    fun observeSongsForArtist(artistId: String): Flow<List<Episode>>
    fun observeDirectFolderSongs(
        folderUri: String,
        relativeFolderPath: String,
        query: String = "",
        limit: Int = 250,
    ): Flow<List<Episode>>

    suspend fun getRecursiveFolderSongIds(
        folderUri: String,
        relativeFolderPath: String,
        limit: Int = 2_000,
    ): List<String>
    suspend fun getDirectFolderSongIds(
        folderUri: String,
        relativeFolderPath: String,
        limit: Int = 2_000,
    ): List<String>

    // Excluded Folders
    fun observeExcludedFolders(): Flow<List<ExcludedFolder>>
    suspend fun excludeFolder(folderKey: String, displayName: String, treeUri: String, relativePath: String)
    suspend fun restoreExcludedFolder(folderKey: String)

    // File Management DB Sync
    suspend fun updateLocalEpisodeFileLocation(
        episodeId: String,
        sourceUri: String,
        audioAsset: String,
        folderUri: String?,
        relativeFolderPath: String,
        title: String,
        lastModifiedMs: Long,
    )

    suspend fun deleteEpisodesCascade(episodeIds: List<String>)

    // Multi-Queue Persistent Music Queues
    fun observeMusicQueues(): Flow<List<MusicQueue>>
    suspend fun getMusicQueues(): List<MusicQueue>
    suspend fun ensureDefaultMusicQueue(): MusicQueue
    suspend fun createMusicQueue(name: String, initialEpisodeIds: List<String> = emptyList(), activate: Boolean = false): String
    suspend fun renameMusicQueue(queueId: String, newName: String)
    suspend fun deleteMusicQueue(queueId: String)
    suspend fun setActiveMusicQueue(queueId: String)
    suspend fun replaceMusicQueueItems(queueId: String, orderedEpisodeIds: List<String>)
    suspend fun addEpisodesToMusicQueue(queueId: String, episodeIds: List<String>, insertNextAfterEpisodeId: String? = null)
    suspend fun removeEpisodesFromMusicQueue(queueId: String, episodeIds: List<String>)
    suspend fun updateMusicQueuePlaybackState(
        queueId: String,
        shuffle: Boolean,
        repeatMode: RepeatMode,
        currentEpisodeId: String?,
        currentPositionMs: Long,
    )

    // Metadata updates after TagLib editing
    suspend fun updateEpisodeAfterTagEdit(
        episodeId: String,
        tags: EditableAudioTags,
        updatedArtworkPath: String? = null,
    )
}
