/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PodcastyDao {
    @Query("SELECT COUNT(*) FROM podcasts")
    suspend fun podcastCount(): Int

    @Query(
        """
        SELECT * FROM hosts
        ORDER BY CASE WHEN id LIKE 'local_host_%' THEN 1 ELSE 0 END ASC, name COLLATE NOCASE ASC
        LIMIT 500
        """,
    )
    fun observeHosts(): Flow<List<HostEntity>>

    @Query("SELECT * FROM categories ORDER BY name COLLATE NOCASE ASC LIMIT 500")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Query(
        """
        SELECT * FROM podcasts
        ORDER BY CASE WHEN contentType = 'PODCAST' THEN 0 ELSE 1 END ASC, title COLLATE NOCASE ASC
        LIMIT 500
        """,
    )
    fun observePodcasts(): Flow<List<PodcastEntity>>

    @Query(
        """
        SELECT * FROM podcasts
        WHERE contentType = 'PODCAST'
        ORDER BY importedAt DESC, title COLLATE NOCASE ASC
        LIMIT 500
        """,
    )
    fun observePodcastShows(): Flow<List<PodcastEntity>>

    @Query("SELECT * FROM podcasts WHERE id = :podcastId LIMIT 1")
    fun observePodcastById(podcastId: String): Flow<PodcastEntity?>

    @Query(
        """
        SELECT id, podcastId, podcastTitle, hostId, hostName, categoryId, categoryName,
               episodeNumber, title, '' AS summary, artworkAsset, audioAsset, durationMs,
               playbackPositionMs, isPlayed, isSaved, isDownloaded, fileSizeBytes, playCount,
               publishedAt, lastPlayedAt, sourceType, sourceUri, youtubeUrl, linkedAudioUri,
               folderUri, containerFormat, audioCodec, sampleRateHz, bitDepth, bitrateKbps,
               channels, isHighRes, year, trackNumber, discNumber, albumArtist, composer,
               comment, transcriptLanguage, relativeFolderPath, lastModifiedMs, contentType, importSource, importedAt
        FROM episodes
        WHERE contentType = 'PODCAST'
        ORDER BY importedAt DESC, publishedAt DESC
        LIMIT :limit
        """,
    )
    fun observePodcastEpisodes(limit: Int): Flow<List<EpisodeSummaryTuple>>

    @Query("SELECT COUNT(*) FROM episodes WHERE contentType = 'PODCAST'")
    fun observePodcastEpisodeCount(): Flow<Int>

    @Query(
        """
        SELECT id, podcastId, podcastTitle, hostId, hostName, categoryId, categoryName,
               episodeNumber, title, '' AS summary, artworkAsset, audioAsset, durationMs,
               playbackPositionMs, isPlayed, isSaved, isDownloaded, fileSizeBytes, playCount,
               publishedAt, lastPlayedAt, sourceType, sourceUri, youtubeUrl, linkedAudioUri,
               folderUri, containerFormat, audioCodec, sampleRateHz, bitDepth, bitrateKbps,
               channels, isHighRes, year, trackNumber, discNumber, albumArtist, composer,
               comment, transcriptLanguage, relativeFolderPath, lastModifiedMs, contentType, importSource, importedAt
        FROM episodes
        WHERE podcastId = :podcastId
        ORDER BY importedAt DESC, episodeNumber DESC, publishedAt DESC
        LIMIT 1000
        """,
    )
    fun observeEpisodesForPodcast(podcastId: String): Flow<List<EpisodeSummaryTuple>>

    @Query(
        """
        SELECT id, podcastId, podcastTitle, hostId, hostName, categoryId, categoryName,
               episodeNumber, title, '' AS summary, artworkAsset, audioAsset, durationMs,
               playbackPositionMs, isPlayed, isSaved, isDownloaded, fileSizeBytes, playCount,
               publishedAt, lastPlayedAt, sourceType, sourceUri, youtubeUrl, linkedAudioUri,
               folderUri, containerFormat, audioCodec, sampleRateHz, bitDepth, bitrateKbps,
               channels, isHighRes, year, trackNumber, discNumber, albumArtist, composer,
               comment, transcriptLanguage, relativeFolderPath, lastModifiedMs, contentType, importSource, importedAt
        FROM episodes
        WHERE contentType = 'MUSIC'
        ORDER BY publishedAt DESC
        LIMIT :limit
        """,
    )
    fun observeRecentMusicEpisodes(limit: Int): Flow<List<EpisodeSummaryTuple>>

    @Query(
        """
        SELECT id, podcastId, podcastTitle, hostId, hostName, categoryId, categoryName,
               episodeNumber, title, '' AS summary, artworkAsset, audioAsset, durationMs,
               playbackPositionMs, isPlayed, isSaved, isDownloaded, fileSizeBytes, playCount,
               publishedAt, lastPlayedAt, sourceType, sourceUri, youtubeUrl, linkedAudioUri,
               folderUri, containerFormat, audioCodec, sampleRateHz, bitDepth, bitrateKbps,
               channels, isHighRes, year, trackNumber, discNumber, albumArtist, composer,
               comment, transcriptLanguage, relativeFolderPath, lastModifiedMs, contentType, importSource, importedAt
        FROM episodes
        WHERE contentType = 'MUSIC' AND (isSaved = 1 OR playCount > 0)
        ORDER BY isSaved DESC, playCount DESC, COALESCE(lastPlayedAt, publishedAt) DESC
        LIMIT :limit
        """,
    )
    fun observeFavouriteMusicEpisodes(limit: Int): Flow<List<EpisodeSummaryTuple>>

    @Query(
        """
        SELECT id, podcastId, podcastTitle, hostId, hostName, categoryId, categoryName,
               episodeNumber, title, '' AS summary, artworkAsset, audioAsset, durationMs,
               playbackPositionMs, isPlayed, isSaved, isDownloaded, fileSizeBytes, playCount,
               publishedAt, lastPlayedAt, sourceType, sourceUri, youtubeUrl, linkedAudioUri,
               folderUri, containerFormat, audioCodec, sampleRateHz, bitDepth, bitrateKbps,
               channels, isHighRes, year, trackNumber, discNumber, albumArtist, composer,
               comment, transcriptLanguage, relativeFolderPath, lastModifiedMs, contentType, importSource, importedAt
        FROM episodes
        WHERE lastPlayedAt IS NOT NULL OR (isPlayed = 0 AND playbackPositionMs > 3000)
        ORDER BY COALESCE(lastPlayedAt, publishedAt) DESC
        LIMIT :limit
        """,
    )
    fun observeRecentlyPlayedEpisodes(limit: Int): Flow<List<EpisodeSummaryTuple>>

    @Query(
        """
        SELECT id, podcastId, podcastTitle, hostId, hostName, categoryId, categoryName,
               episodeNumber, title, '' AS summary, artworkAsset, audioAsset, durationMs,
               playbackPositionMs, isPlayed, isSaved, isDownloaded, fileSizeBytes, playCount,
               publishedAt, lastPlayedAt, sourceType, sourceUri, youtubeUrl, linkedAudioUri,
               folderUri, containerFormat, audioCodec, sampleRateHz, bitDepth, bitrateKbps,
               channels, isHighRes, year, trackNumber, discNumber, albumArtist, composer,
               comment, transcriptLanguage, relativeFolderPath, lastModifiedMs, contentType, importSource, importedAt
        FROM (
            SELECT * FROM (
                SELECT id, podcastId, podcastTitle, hostId, hostName, categoryId, categoryName,
                       episodeNumber, title, '' AS summary, artworkAsset, audioAsset, durationMs,
                       playbackPositionMs, isPlayed, isSaved, isDownloaded, fileSizeBytes, playCount,
                       publishedAt, lastPlayedAt, sourceType, sourceUri, youtubeUrl, linkedAudioUri,
                       folderUri, containerFormat, audioCodec, sampleRateHz, bitDepth, bitrateKbps,
                       channels, isHighRes, year, trackNumber, discNumber, albumArtist, composer,
                       comment, transcriptLanguage, relativeFolderPath, lastModifiedMs, contentType, importSource, importedAt,
                       0 AS __rank, COALESCE(lastPlayedAt, publishedAt) AS __sortAt
                FROM episodes
                WHERE sourceType != 'local_music'
                ORDER BY __sortAt DESC
                LIMIT 500
            )
            UNION ALL
            SELECT * FROM (
                SELECT id, podcastId, podcastTitle, hostId, hostName, categoryId, categoryName,
                       episodeNumber, title, '' AS summary, artworkAsset, audioAsset, durationMs,
                       playbackPositionMs, isPlayed, isSaved, isDownloaded, fileSizeBytes, playCount,
                       publishedAt, lastPlayedAt, sourceType, sourceUri, youtubeUrl, linkedAudioUri,
                       folderUri, containerFormat, audioCodec, sampleRateHz, bitDepth, bitrateKbps,
                       channels, isHighRes, year, trackNumber, discNumber, albumArtist, composer,
                       comment, transcriptLanguage, relativeFolderPath, lastModifiedMs, contentType, importSource, importedAt,
                       1 AS __rank, lastPlayedAt AS __sortAt
                FROM episodes
                WHERE sourceType = 'local_music' AND lastPlayedAt IS NOT NULL
                ORDER BY __sortAt DESC
                LIMIT 500
            )
            UNION ALL
            SELECT * FROM (
                SELECT id, podcastId, podcastTitle, hostId, hostName, categoryId, categoryName,
                       episodeNumber, title, '' AS summary, artworkAsset, audioAsset, durationMs,
                       playbackPositionMs, isPlayed, isSaved, isDownloaded, fileSizeBytes, playCount,
                       publishedAt, lastPlayedAt, sourceType, sourceUri, youtubeUrl, linkedAudioUri,
                       folderUri, containerFormat, audioCodec, sampleRateHz, bitDepth, bitrateKbps,
                       channels, isHighRes, year, trackNumber, discNumber, albumArtist, composer,
                       comment, transcriptLanguage, relativeFolderPath, lastModifiedMs, contentType, importSource, importedAt,
                       1 AS __rank, publishedAt AS __sortAt
                FROM episodes
                WHERE sourceType = 'local_music' AND lastPlayedAt IS NULL AND isDownloaded = 1
                ORDER BY __sortAt DESC
                LIMIT 500
            )
            UNION ALL
            SELECT * FROM (
                SELECT id, podcastId, podcastTitle, hostId, hostName, categoryId, categoryName,
                       episodeNumber, title, '' AS summary, artworkAsset, audioAsset, durationMs,
                       playbackPositionMs, isPlayed, isSaved, isDownloaded, fileSizeBytes, playCount,
                       publishedAt, lastPlayedAt, sourceType, sourceUri, youtubeUrl, linkedAudioUri,
                       folderUri, containerFormat, audioCodec, sampleRateHz, bitDepth, bitrateKbps,
                       channels, isHighRes, year, trackNumber, discNumber, albumArtist, composer,
                       comment, transcriptLanguage, relativeFolderPath, lastModifiedMs, contentType, importSource, importedAt,
                       1 AS __rank, publishedAt AS __sortAt
                FROM episodes
                WHERE sourceType = 'local_music' AND lastPlayedAt IS NULL
                  AND isSaved = 1 AND isDownloaded = 0
                ORDER BY __sortAt DESC
                LIMIT 500
            )
            UNION ALL
            SELECT * FROM (
                SELECT id, podcastId, podcastTitle, hostId, hostName, categoryId, categoryName,
                       episodeNumber, title, '' AS summary, artworkAsset, audioAsset, durationMs,
                       playbackPositionMs, isPlayed, isSaved, isDownloaded, fileSizeBytes, playCount,
                       publishedAt, lastPlayedAt, sourceType, sourceUri, youtubeUrl, linkedAudioUri,
                       folderUri, containerFormat, audioCodec, sampleRateHz, bitDepth, bitrateKbps,
                       channels, isHighRes, year, trackNumber, discNumber, albumArtist, composer,
                       comment, transcriptLanguage, relativeFolderPath, lastModifiedMs, contentType, importSource, importedAt,
                       2 AS __rank, publishedAt AS __sortAt
                FROM episodes
                WHERE sourceType = 'local_music' AND lastPlayedAt IS NULL
                  AND isSaved = 0 AND isDownloaded = 0
                ORDER BY __sortAt DESC
                LIMIT 500
            )
        )
        ORDER BY __rank ASC, __sortAt DESC
        LIMIT 500
        """,
    )
    fun observeEpisodeSummaries(): Flow<List<EpisodeSummaryTuple>>

    // Smart collection ID queries computed directly inside SQLite (avoids sorting 5k-25k+ rows in Kotlin)
    @Query("SELECT id FROM episodes WHERE isSaved = 1 ORDER BY publishedAt DESC LIMIT 200")
    fun observeSavedEpisodeIds(): Flow<List<String>>

    @Query("SELECT id FROM episodes WHERE lastPlayedAt IS NOT NULL ORDER BY lastPlayedAt DESC LIMIT 25")
    fun observeRecentlyPlayedEpisodeIds(): Flow<List<String>>

    @Query("SELECT id FROM episodes WHERE playCount > 0 ORDER BY playCount DESC LIMIT 25")
    fun observeMostPlayedEpisodeIds(): Flow<List<String>>

    @Query("SELECT id FROM episodes ORDER BY publishedAt DESC LIMIT 25")
    fun observeRecentlyAddedEpisodeIds(): Flow<List<String>>

    @Query("SELECT id FROM episodes WHERE playCount = 0 AND isPlayed = 0 ORDER BY publishedAt DESC LIMIT 100")
    fun observeNeverPlayedEpisodeIds(): Flow<List<String>>

    @Query("SELECT id FROM episodes WHERE isDownloaded = 1 ORDER BY publishedAt DESC LIMIT 100")
    fun observeRecentlyDownloadedEpisodeIds(): Flow<List<String>>

    @Query("SELECT id FROM episodes WHERE isHighRes = 1 ORDER BY sampleRateHz DESC LIMIT 100")
    fun observeHighResEpisodeIds(): Flow<List<String>>

    @Query("SELECT id FROM episodes WHERE sourceType = 'local_music' ORDER BY publishedAt DESC LIMIT 100")
    fun observeLocalMusicCollectionIds(): Flow<List<String>>

    @Query(
        """
        SELECT id, fileSizeBytes, lastModifiedMs, durationMs, artworkAsset,
               audioAsset, relativeFolderPath,
               CASE WHEN transcriptLrc IS NOT NULL AND transcriptLrc != '' THEN 1 ELSE 0 END AS hasLyrics
        FROM episodes
        WHERE folderUri = :folderUri
        """,
    )
    suspend fun getExistingTrackStampsForFolder(folderUri: String): List<ExistingTrackStamp>

    @Query(
        """
        SELECT id, fileSizeBytes, audioAsset
        FROM episodes
        WHERE sourceType = 'local_music' AND fileSizeBytes > 0 AND folderUri != :folderUri
        """,
    )
    suspend fun getDedupSignaturesExcludingFolder(folderUri: String): List<TrackDedupSignature>

    @Query(
        """
        SELECT COUNT(*) FROM episodes WHERE sourceType = 'local_music'
        """,
    )
    fun observeLocalMusicCount(): Flow<Int>

    @Query(
        """
        SELECT id, podcastId, podcastTitle, hostId, hostName, categoryId, categoryName,
               episodeNumber, title, '' AS summary, artworkAsset, audioAsset, durationMs,
               playbackPositionMs, isPlayed, isSaved, isDownloaded, fileSizeBytes, playCount,
               publishedAt, lastPlayedAt, sourceType, sourceUri, youtubeUrl, linkedAudioUri,
               folderUri, containerFormat, audioCodec, sampleRateHz, bitDepth, bitrateKbps,
               channels, isHighRes, year, trackNumber, discNumber, albumArtist, composer,
               comment, transcriptLanguage, relativeFolderPath, lastModifiedMs, contentType, importSource, importedAt
        FROM episodes
        WHERE sourceType = 'local_music'
          AND (:category != 'favorites' OR isSaved = 1)
          AND (:category != 'never' OR (playCount = 0 AND lastPlayedAt IS NULL))
          AND (:category != 'most' OR playCount > 0)
        ORDER BY
          CASE WHEN :category = 'most' THEN playCount END DESC,
          CASE WHEN :category = 'longest' THEN durationMs END DESC,
          CASE WHEN :category = 'recent' THEN lastPlayedAt END DESC,
          publishedAt DESC, normalizedTitle ASC
        LIMIT 16
        """,
    )
    fun observeSmartMusicCategory(category: String): Flow<List<EpisodeSummaryTuple>>

    @Query("""
        SELECT id FROM episodes
        WHERE sourceType = 'local_music' AND fileSizeBytes > 0 AND sourceUri IS NOT NULL
          AND (fileSizeBytes, durationMs, normalizedTitle, normalizedArtist) IN (
            SELECT fileSizeBytes, durationMs, normalizedTitle, normalizedArtist FROM episodes
            WHERE sourceType = 'local_music' AND fileSizeBytes > 0 AND sourceUri IS NOT NULL
            GROUP BY fileSizeBytes, durationMs, normalizedTitle, normalizedArtist
            HAVING COUNT(DISTINCT sourceUri) > 1
          )
        ORDER BY fileSizeBytes, durationMs, normalizedTitle, normalizedArtist, sourceUri
        LIMIT 1001
    """)
    suspend fun getDuplicateMusicCandidateIds(): List<String>

    @Query(
        """
        SELECT id, podcastId, podcastTitle, hostId, hostName, categoryId, categoryName,
               episodeNumber, title, '' AS summary, artworkAsset, audioAsset, durationMs,
               playbackPositionMs, isPlayed, isSaved, isDownloaded, fileSizeBytes, playCount,
               publishedAt, lastPlayedAt, sourceType, sourceUri, youtubeUrl, linkedAudioUri,
               folderUri, containerFormat, audioCodec, sampleRateHz, bitDepth, bitrateKbps,
               channels, isHighRes, year, trackNumber, discNumber, albumArtist, composer,
               comment, transcriptLanguage, relativeFolderPath, lastModifiedMs, contentType, importSource, importedAt
        FROM episodes
        WHERE sourceType = 'local_music'
          AND (:query = '' OR title LIKE '%' || :query || '%' OR hostName LIKE '%' || :query || '%' OR podcastTitle LIKE '%' || :query || '%' OR audioAsset LIKE '%' || :query || '%' OR relativeFolderPath LIKE '%' || :query || '%')
          AND durationMs >= :minDurationMs AND durationMs <= :maxDurationMs
          AND (:format = '' OR LOWER(containerFormat) = :format)
        ORDER BY
            CASE WHEN :sortBy = 'title' AND :asc = 1 THEN normalizedTitle END ASC,
            CASE WHEN :sortBy = 'title' AND :asc = 0 THEN normalizedTitle END DESC,
            CASE WHEN :sortBy = 'artist' AND :asc = 1 THEN normalizedArtist END ASC,
            CASE WHEN :sortBy = 'artist' AND :asc = 0 THEN normalizedArtist END DESC,
            CASE WHEN :sortBy = 'album' AND :asc = 1 THEN normalizedAlbum END ASC,
            CASE WHEN :sortBy = 'album' AND :asc = 0 THEN normalizedAlbum END DESC,
            CASE WHEN :sortBy = 'duration' AND :asc = 1 THEN durationMs END ASC,
            CASE WHEN :sortBy = 'duration' AND :asc = 0 THEN durationMs END DESC,
            CASE WHEN :sortBy = 'date' AND :asc = 1 THEN publishedAt END ASC,
            CASE WHEN :sortBy = 'date' AND :asc = 0 THEN publishedAt END DESC,
            normalizedTitle ASC
        LIMIT :limit
        """,
    )
    fun observeLocalSongsPaged(
        query: String,
        sortBy: String,
        asc: Boolean,
        limit: Int,
        minDurationMs: Long = 0L,
        maxDurationMs: Long = Long.MAX_VALUE,
        format: String = "",
    ): Flow<List<EpisodeSummaryTuple>>

    @Query(
        """
        SELECT id
        FROM episodes
        WHERE sourceType = 'local_music'
          AND (:query = '' OR title LIKE '%' || :query || '%' OR hostName LIKE '%' || :query || '%' OR podcastTitle LIKE '%' || :query || '%' OR audioAsset LIKE '%' || :query || '%')
        ORDER BY
            CASE WHEN :sortBy = 'title' AND :asc = 1 THEN normalizedTitle END ASC,
            CASE WHEN :sortBy = 'title' AND :asc = 0 THEN normalizedTitle END DESC,
            CASE WHEN :sortBy = 'artist' AND :asc = 1 THEN normalizedArtist END ASC,
            CASE WHEN :sortBy = 'artist' AND :asc = 0 THEN normalizedArtist END DESC,
            CASE WHEN :sortBy = 'album' AND :asc = 1 THEN normalizedAlbum END ASC,
            CASE WHEN :sortBy = 'album' AND :asc = 0 THEN normalizedAlbum END DESC,
            CASE WHEN :sortBy = 'duration' AND :asc = 1 THEN durationMs END ASC,
            CASE WHEN :sortBy = 'duration' AND :asc = 0 THEN durationMs END DESC,
            CASE WHEN :sortBy = 'date' AND :asc = 1 THEN publishedAt END ASC,
            CASE WHEN :sortBy = 'date' AND :asc = 0 THEN publishedAt END DESC,
            normalizedTitle ASC
        LIMIT :limit
        """,
    )
    suspend fun getLocalSongIdsSorted(
        query: String,
        sortBy: String,
        asc: Boolean,
        limit: Int,
    ): List<String>

    @Query(
        """
        SELECT COALESCE(folderUri, '') AS folderUri,
               relativeFolderPath,
               COUNT(*) AS trackCount,
               SUM(durationMs) AS totalDurationMs,
               MAX(publishedAt) AS latestAddedAt
        FROM episodes
        WHERE sourceType = 'local_music'
        GROUP BY folderUri, relativeFolderPath
        """,
    )
    fun observeLinearFolders(): Flow<List<LinearFolderRow>>

    @Query(
        """
        SELECT COALESCE(folderUri, '') AS folderUri,
               relativeFolderPath,
               COUNT(*) AS trackCount,
               SUM(durationMs) AS totalDurationMs,
               MAX(publishedAt) AS latestAddedAt
        FROM episodes
        WHERE sourceType = 'local_music'
        GROUP BY folderUri, relativeFolderPath
        """,
    )
    suspend fun getLinearFolders(): List<LinearFolderRow>

    @Query(
        """
        SELECT podcastId AS albumId,
               podcastTitle AS albumTitle,
               MAX(COALESCE(NULLIF(albumArtist, ''), hostName)) AS artistName,
               COUNT(*) AS trackCount,
               SUM(durationMs) AS totalDurationMs,
               MAX(artworkAsset) AS artworkAsset
        FROM episodes
        WHERE contentType = 'MUSIC'
          AND (:query = '' OR podcastTitle LIKE '%' || :query || '%' OR hostName LIKE '%' || :query || '%' OR albumArtist LIKE '%' || :query || '%')
        GROUP BY podcastId
        ORDER BY normalizedAlbum ASC
        LIMIT :limit
        """,
    )
    fun observeLocalAlbums(query: String, limit: Int): Flow<List<AlbumRow>>

    @Query(
        """
        SELECT podcastId AS albumId,
               podcastTitle AS albumTitle,
               MAX(COALESCE(NULLIF(albumArtist, ''), hostName)) AS artistName,
               COUNT(*) AS trackCount,
               SUM(durationMs) AS totalDurationMs,
               MAX(artworkAsset) AS artworkAsset
        FROM episodes
        WHERE contentType = 'MUSIC'
          AND (:query = '' OR podcastTitle LIKE '%' || :query || '%' OR hostName LIKE '%' || :query || '%' OR albumArtist LIKE '%' || :query || '%')
        GROUP BY podcastId
        ORDER BY normalizedAlbum ASC
        LIMIT :limit
        """,
    )
    suspend fun getLocalAlbums(query: String, limit: Int): List<AlbumRow>

    @Query(
        """
        SELECT podcastId AS albumId,
               podcastTitle AS albumTitle,
               MAX(COALESCE(NULLIF(albumArtist, ''), hostName)) AS artistName,
               COUNT(*) AS trackCount,
               SUM(durationMs) AS totalDurationMs,
               MAX(artworkAsset) AS artworkAsset
        FROM episodes
        WHERE contentType = 'MUSIC'
          AND (:query = '' OR podcastTitle LIKE '%' || :query || '%' OR hostName LIKE '%' || :query || '%' OR albumArtist LIKE '%' || :query || '%')
        GROUP BY podcastId
        ORDER BY
            CASE WHEN :sortBy = 'title' AND :asc = 1 THEN normalizedAlbum END ASC,
            CASE WHEN :sortBy = 'title' AND :asc = 0 THEN normalizedAlbum END DESC,
            CASE WHEN :sortBy = 'artist' AND :asc = 1 THEN MAX(normalizedArtist) END ASC,
            CASE WHEN :sortBy = 'artist' AND :asc = 0 THEN MAX(normalizedArtist) END DESC,
            CASE WHEN :sortBy = 'tracks' AND :asc = 1 THEN COUNT(*) END ASC,
            CASE WHEN :sortBy = 'tracks' AND :asc = 0 THEN COUNT(*) END DESC,
            normalizedAlbum ASC
        LIMIT :limit
        """,
    )
    suspend fun getLocalAlbumsSorted(query: String, sortBy: String, asc: Boolean, limit: Int): List<AlbumRow>

    @Query(
        """
        SELECT podcastId AS albumId,
               podcastTitle AS albumTitle,
               MAX(COALESCE(NULLIF(albumArtist, ''), hostName)) AS artistName,
               COUNT(*) AS trackCount,
               SUM(durationMs) AS totalDurationMs,
               MAX(artworkAsset) AS artworkAsset
        FROM episodes
        WHERE contentType = 'MUSIC' AND podcastId = :albumId
        GROUP BY podcastId
        LIMIT 1
        """,
    )
    fun observeMusicAlbumById(albumId: String): Flow<AlbumRow?>

    @Query("SELECT COUNT(DISTINCT podcastId) FROM episodes WHERE contentType = 'MUSIC'")
    fun observeMusicAlbumCount(): Flow<Int>

    @Query(
        """
        SELECT hostId AS artistId,
               MAX(hostName) AS artistName,
               COUNT(DISTINCT podcastId) AS albumCount,
               COUNT(*) AS trackCount,
               MAX(artworkAsset) AS artworkAsset
        FROM episodes
        WHERE sourceType = 'local_music'
          AND (:query = '' OR hostName LIKE '%' || :query || '%')
        GROUP BY hostId
        ORDER BY normalizedArtist ASC
        LIMIT :limit
        """,
    )
    fun observeLocalArtists(query: String, limit: Int): Flow<List<ArtistRow>>

    @Query(
        """
        SELECT hostId AS artistId,
               MAX(hostName) AS artistName,
               COUNT(DISTINCT podcastId) AS albumCount,
               COUNT(*) AS trackCount,
               MAX(artworkAsset) AS artworkAsset
        FROM episodes
        WHERE sourceType = 'local_music'
          AND (:query = '' OR hostName LIKE '%' || :query || '%')
        GROUP BY hostId
        ORDER BY normalizedArtist ASC
        LIMIT :limit
        """,
    )
    suspend fun getLocalArtists(query: String, limit: Int): List<ArtistRow>

    @Query(
        """
        SELECT id, podcastId, podcastTitle, hostId, hostName, categoryId, categoryName,
               episodeNumber, title, '' AS summary, artworkAsset, audioAsset, durationMs,
               playbackPositionMs, isPlayed, isSaved, isDownloaded, fileSizeBytes, playCount,
               publishedAt, lastPlayedAt, sourceType, sourceUri, youtubeUrl, linkedAudioUri,
               folderUri, containerFormat, audioCodec, sampleRateHz, bitDepth, bitrateKbps,
               channels, isHighRes, year, trackNumber, discNumber, albumArtist, composer,
               comment, transcriptLanguage, relativeFolderPath, lastModifiedMs, contentType, importSource, importedAt
        FROM episodes
        WHERE sourceType = 'local_music'
        ORDER BY normalizedTitle ASC
        LIMIT :limit
        """,
    )
    fun observeLocalSongsByTitleAsc(limit: Int): Flow<List<EpisodeSummaryTuple>>

    @Query(
        """
        SELECT id, podcastId, podcastTitle, hostId, hostName, categoryId, categoryName,
               episodeNumber, title, '' AS summary, artworkAsset, audioAsset, durationMs,
               playbackPositionMs, isPlayed, isSaved, isDownloaded, fileSizeBytes, playCount,
               publishedAt, lastPlayedAt, sourceType, sourceUri, youtubeUrl, linkedAudioUri,
               folderUri, containerFormat, audioCodec, sampleRateHz, bitDepth, bitrateKbps,
               channels, isHighRes, year, trackNumber, discNumber, albumArtist, composer,
               comment, transcriptLanguage, relativeFolderPath, lastModifiedMs, contentType, importSource, importedAt
        FROM episodes
        WHERE sourceType = 'local_music'
        ORDER BY normalizedTitle DESC
        LIMIT :limit
        """,
    )
    fun observeLocalSongsByTitleDesc(limit: Int): Flow<List<EpisodeSummaryTuple>>

    @Query(
        """
        SELECT id, podcastId, podcastTitle, hostId, hostName, categoryId, categoryName,
               episodeNumber, title, '' AS summary, artworkAsset, audioAsset, durationMs,
               playbackPositionMs, isPlayed, isSaved, isDownloaded, fileSizeBytes, playCount,
               publishedAt, lastPlayedAt, sourceType, sourceUri, youtubeUrl, linkedAudioUri,
               folderUri, containerFormat, audioCodec, sampleRateHz, bitDepth, bitrateKbps,
               channels, isHighRes, year, trackNumber, discNumber, albumArtist, composer,
               comment, transcriptLanguage, relativeFolderPath, lastModifiedMs, contentType, importSource, importedAt
        FROM episodes
        WHERE sourceType = 'local_music'
        ORDER BY normalizedArtist ASC, normalizedTitle ASC
        LIMIT :limit
        """,
    )
    fun observeLocalSongsByArtistAsc(limit: Int): Flow<List<EpisodeSummaryTuple>>

    @Query(
        """
        SELECT id, podcastId, podcastTitle, hostId, hostName, categoryId, categoryName,
               episodeNumber, title, '' AS summary, artworkAsset, audioAsset, durationMs,
               playbackPositionMs, isPlayed, isSaved, isDownloaded, fileSizeBytes, playCount,
               publishedAt, lastPlayedAt, sourceType, sourceUri, youtubeUrl, linkedAudioUri,
               folderUri, containerFormat, audioCodec, sampleRateHz, bitDepth, bitrateKbps,
               channels, isHighRes, year, trackNumber, discNumber, albumArtist, composer,
               comment, transcriptLanguage, relativeFolderPath, lastModifiedMs, contentType, importSource, importedAt
        FROM episodes
        WHERE sourceType = 'local_music'
        ORDER BY normalizedAlbum ASC, normalizedTitle ASC
        LIMIT :limit
        """,
    )
    fun observeLocalSongsByAlbumAsc(limit: Int): Flow<List<EpisodeSummaryTuple>>

    @Query(
        """
        SELECT id, podcastId, podcastTitle, hostId, hostName, categoryId, categoryName,
               episodeNumber, title, '' AS summary, artworkAsset, audioAsset, durationMs,
               playbackPositionMs, isPlayed, isSaved, isDownloaded, fileSizeBytes, playCount,
               publishedAt, lastPlayedAt, sourceType, sourceUri, youtubeUrl, linkedAudioUri,
               folderUri, containerFormat, audioCodec, sampleRateHz, bitDepth, bitrateKbps,
               channels, isHighRes, year, trackNumber, discNumber, albumArtist, composer,
               comment, transcriptLanguage, relativeFolderPath, lastModifiedMs, contentType, importSource, importedAt
        FROM episodes
        WHERE sourceType = 'local_music'
        ORDER BY publishedAt DESC
        LIMIT :limit
        """,
    )
    fun observeLocalSongsByDateDesc(limit: Int): Flow<List<EpisodeSummaryTuple>>

    @Query(
        """
        SELECT id, podcastId, podcastTitle, hostId, hostName, categoryId, categoryName,
               episodeNumber, title, '' AS summary, artworkAsset, audioAsset, durationMs,
               playbackPositionMs, isPlayed, isSaved, isDownloaded, fileSizeBytes, playCount,
               publishedAt, lastPlayedAt, sourceType, sourceUri, youtubeUrl, linkedAudioUri,
               folderUri, containerFormat, audioCodec, sampleRateHz, bitDepth, bitrateKbps,
               channels, isHighRes, year, trackNumber, discNumber, albumArtist, composer,
               comment, transcriptLanguage, relativeFolderPath, lastModifiedMs, contentType, importSource, importedAt
        FROM episodes
        WHERE sourceType = 'local_music'
        ORDER BY normalizedArtist DESC, normalizedTitle DESC
        LIMIT :limit
        """,
    )
    fun observeLocalSongsByArtistDesc(limit: Int): Flow<List<EpisodeSummaryTuple>>

    @Query(
        """
        SELECT id, podcastId, podcastTitle, hostId, hostName, categoryId, categoryName,
               episodeNumber, title, '' AS summary, artworkAsset, audioAsset, durationMs,
               playbackPositionMs, isPlayed, isSaved, isDownloaded, fileSizeBytes, playCount,
               publishedAt, lastPlayedAt, sourceType, sourceUri, youtubeUrl, linkedAudioUri,
               folderUri, containerFormat, audioCodec, sampleRateHz, bitDepth, bitrateKbps,
               channels, isHighRes, year, trackNumber, discNumber, albumArtist, composer,
               comment, transcriptLanguage, relativeFolderPath, lastModifiedMs, contentType, importSource, importedAt
        FROM episodes
        WHERE sourceType = 'local_music'
        ORDER BY normalizedAlbum DESC, normalizedTitle DESC
        LIMIT :limit
        """,
    )
    fun observeLocalSongsByAlbumDesc(limit: Int): Flow<List<EpisodeSummaryTuple>>

    @Query(
        """
        SELECT id, podcastId, podcastTitle, hostId, hostName, categoryId, categoryName,
               episodeNumber, title, '' AS summary, artworkAsset, audioAsset, durationMs,
               playbackPositionMs, isPlayed, isSaved, isDownloaded, fileSizeBytes, playCount,
               publishedAt, lastPlayedAt, sourceType, sourceUri, youtubeUrl, linkedAudioUri,
               folderUri, containerFormat, audioCodec, sampleRateHz, bitDepth, bitrateKbps,
               channels, isHighRes, year, trackNumber, discNumber, albumArtist, composer,
               comment, transcriptLanguage, relativeFolderPath, lastModifiedMs, contentType, importSource, importedAt
        FROM episodes
        WHERE sourceType = 'local_music'
        ORDER BY publishedAt ASC
        LIMIT :limit
        """,
    )
    fun observeLocalSongsByDateAsc(limit: Int): Flow<List<EpisodeSummaryTuple>>

    @Query(
        """
        SELECT id, podcastId, podcastTitle, hostId, hostName, categoryId, categoryName,
               episodeNumber, title, '' AS summary, artworkAsset, audioAsset, durationMs,
               playbackPositionMs, isPlayed, isSaved, isDownloaded, fileSizeBytes, playCount,
               publishedAt, lastPlayedAt, sourceType, sourceUri, youtubeUrl, linkedAudioUri,
               folderUri, containerFormat, audioCodec, sampleRateHz, bitDepth, bitrateKbps,
               channels, isHighRes, year, trackNumber, discNumber, albumArtist, composer,
               comment, transcriptLanguage, relativeFolderPath, lastModifiedMs, contentType, importSource, importedAt
        FROM episodes
        WHERE sourceType = 'local_music'
        ORDER BY durationMs ASC, normalizedTitle ASC
        LIMIT :limit
        """,
    )
    fun observeLocalSongsByDurationAsc(limit: Int): Flow<List<EpisodeSummaryTuple>>

    @Query(
        """
        SELECT id, podcastId, podcastTitle, hostId, hostName, categoryId, categoryName,
               episodeNumber, title, '' AS summary, artworkAsset, audioAsset, durationMs,
               playbackPositionMs, isPlayed, isSaved, isDownloaded, fileSizeBytes, playCount,
               publishedAt, lastPlayedAt, sourceType, sourceUri, youtubeUrl, linkedAudioUri,
               folderUri, containerFormat, audioCodec, sampleRateHz, bitDepth, bitrateKbps,
               channels, isHighRes, year, trackNumber, discNumber, albumArtist, composer,
               comment, transcriptLanguage, relativeFolderPath, lastModifiedMs, contentType, importSource, importedAt
        FROM episodes
        WHERE sourceType = 'local_music'
        ORDER BY durationMs DESC, normalizedTitle ASC
        LIMIT :limit
        """,
    )
    fun observeLocalSongsByDurationDesc(limit: Int): Flow<List<EpisodeSummaryTuple>>

    @Query(
        """
        SELECT id, podcastId, podcastTitle, hostId, hostName, categoryId, categoryName,
               episodeNumber, title, '' AS summary, artworkAsset, audioAsset, durationMs,
               playbackPositionMs, isPlayed, isSaved, isDownloaded, fileSizeBytes, playCount,
               publishedAt, lastPlayedAt, sourceType, sourceUri, youtubeUrl, linkedAudioUri,
               folderUri, containerFormat, audioCodec, sampleRateHz, bitDepth, bitrateKbps,
               channels, isHighRes, year, trackNumber, discNumber, albumArtist, composer,
               comment, transcriptLanguage, relativeFolderPath, lastModifiedMs, contentType, importSource, importedAt
        FROM episodes
        WHERE contentType = 'MUSIC' AND podcastId = :albumId
        ORDER BY discNumber ASC, trackNumber ASC, normalizedTitle ASC
        LIMIT 1000
        """,
    )
    fun observeSongsForAlbum(albumId: String): Flow<List<EpisodeSummaryTuple>>

    @Query(
        """
        SELECT id, podcastId, podcastTitle, hostId, hostName, categoryId, categoryName,
               episodeNumber, title, '' AS summary, artworkAsset, audioAsset, durationMs,
               playbackPositionMs, isPlayed, isSaved, isDownloaded, fileSizeBytes, playCount,
               publishedAt, lastPlayedAt, sourceType, sourceUri, youtubeUrl, linkedAudioUri,
               folderUri, containerFormat, audioCodec, sampleRateHz, bitDepth, bitrateKbps,
               channels, isHighRes, year, trackNumber, discNumber, albumArtist, composer,
               comment, transcriptLanguage, relativeFolderPath, lastModifiedMs, contentType, importSource, importedAt
        FROM episodes
        WHERE sourceType = 'local_music' AND hostId = :artistId
        ORDER BY normalizedAlbum ASC, discNumber ASC, trackNumber ASC, normalizedTitle ASC
        LIMIT 1000
        """,
    )
    fun observeSongsForArtist(artistId: String): Flow<List<EpisodeSummaryTuple>>

    @Query(
        """
        SELECT id, podcastId, podcastTitle, hostId, hostName, categoryId, categoryName,
               episodeNumber, title, '' AS summary, artworkAsset, audioAsset, durationMs,
               playbackPositionMs, isPlayed, isSaved, isDownloaded, fileSizeBytes, playCount,
               publishedAt, lastPlayedAt, sourceType, sourceUri, youtubeUrl, linkedAudioUri,
               folderUri, containerFormat, audioCodec, sampleRateHz, bitDepth, bitrateKbps,
               channels, isHighRes, year, trackNumber, discNumber, albumArtist, composer,
               comment, transcriptLanguage, relativeFolderPath, lastModifiedMs, contentType, importSource, importedAt
        FROM episodes
        WHERE sourceType = 'local_music'
          AND folderUri = :folderUri
          AND relativeFolderPath = :relativeFolderPath
          AND (:query = '' OR title LIKE '%' || :query || '%' OR hostName LIKE '%' || :query || '%' OR audioAsset LIKE '%' || :query || '%')
        ORDER BY normalizedTitle ASC
        LIMIT :limit
        """,
    )
    fun observeDirectFolderSongs(
        folderUri: String,
        relativeFolderPath: String,
        query: String,
        limit: Int,
    ): Flow<List<EpisodeSummaryTuple>>

    @Query(
        """
        SELECT id
        FROM episodes
        WHERE sourceType = 'local_music'
          AND folderUri = :folderUri
          AND (:relativeFolderPath = '' OR relativeFolderPath = :relativeFolderPath OR relativeFolderPath LIKE :relativeFolderPath || '/%')
        ORDER BY relativeFolderPath ASC, discNumber ASC, trackNumber ASC, normalizedTitle ASC
        LIMIT :limit
        """,
    )
    suspend fun getRecursiveFolderSongIds(folderUri: String, relativeFolderPath: String, limit: Int): List<String>

    @Query(
        """
        SELECT id
        FROM episodes
        WHERE sourceType = 'local_music'
          AND folderUri = :folderUri
          AND relativeFolderPath = :relativeFolderPath
        ORDER BY discNumber ASC, trackNumber ASC, normalizedTitle ASC
        LIMIT :limit
        """,
    )
    suspend fun getDirectFolderSongIds(folderUri: String, relativeFolderPath: String, limit: Int): List<String>

    @Query(
        """
        SELECT id FROM episodes
        WHERE sourceType = 'local_music'
          AND folderUri = :folderUri
          AND (:relativePath = '' OR relativeFolderPath = :relativePath OR relativeFolderPath LIKE :relativePath || '/%')
        """,
    )
    suspend fun getEpisodeIdsInFolderSubtree(folderUri: String, relativePath: String): List<String>

    // Excluded folders
    @Query("SELECT * FROM excluded_folders ORDER BY createdAt DESC")
    fun observeExcludedFolders(): Flow<List<ExcludedFolderEntity>>

    @Query("SELECT * FROM excluded_folders")
    suspend fun getExcludedFolders(): List<ExcludedFolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExcludedFolder(folder: ExcludedFolderEntity)

    @Query("DELETE FROM excluded_folders WHERE folderKey = :folderKey")
    suspend fun deleteExcludedFolder(folderKey: String)

    // Multiple Persistent Music Queues
    @Query("SELECT * FROM music_queues ORDER BY isActive DESC, createdAt ASC")
    fun observeMusicQueues(): Flow<List<MusicQueueEntity>>

    @Query("SELECT * FROM music_queues ORDER BY isActive DESC, createdAt ASC")
    suspend fun getMusicQueues(): List<MusicQueueEntity>

    @Query("SELECT * FROM music_queue_items ORDER BY queueId ASC, position ASC")
    fun observeAllMusicQueueItems(): Flow<List<MusicQueueItemEntity>>

    @Query("SELECT episodeId FROM music_queue_items WHERE queueId = :queueId ORDER BY position ASC")
    suspend fun getQueueEpisodeIds(queueId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMusicQueue(queue: MusicQueueEntity)

    @Query("UPDATE music_queues SET isActive = CASE WHEN id = :queueId THEN 1 ELSE 0 END")
    suspend fun setActiveMusicQueueFlag(queueId: String)

    @Query("UPDATE music_queues SET name = :name, updatedAt = :updatedAt WHERE id = :queueId")
    suspend fun renameMusicQueue(queueId: String, name: String, updatedAt: Long)

    @Query("DELETE FROM music_queues WHERE id = :queueId")
    suspend fun deleteMusicQueue(queueId: String)

    @Query("DELETE FROM music_queue_items WHERE queueId = :queueId")
    suspend fun deleteAllItemsFromMusicQueue(queueId: String)

    @Query("DELETE FROM music_queue_items WHERE queueId = :queueId AND episodeId IN (:episodeIds)")
    suspend fun removeEpisodesFromMusicQueue(queueId: String, episodeIds: List<String>)

    @Query("DELETE FROM music_queue_items WHERE episodeId IN (:episodeIds)")
    suspend fun deleteMusicQueueEntriesForEpisodes(episodeIds: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMusicQueueItems(items: List<MusicQueueItemEntity>)

    @Transaction
    suspend fun replaceMusicQueueItems(queueId: String, orderedEpisodeIds: List<String>, updatedAt: Long) {
        deleteAllItemsFromMusicQueue(queueId)
        val rows = orderedEpisodeIds.distinct().take(2_000).mapIndexed { index, episodeId ->
            MusicQueueItemEntity(queueId = queueId, episodeId = episodeId, position = index)
        }
        for (chunk in rows.chunked(500)) {
            insertMusicQueueItems(chunk)
        }
        touchMusicQueue(queueId, updatedAt)
    }

    @Query("UPDATE music_queues SET updatedAt = :updatedAt WHERE id = :queueId")
    suspend fun touchMusicQueue(queueId: String, updatedAt: Long)

    @Query(
        """
        UPDATE music_queues
        SET shuffle = :shuffle,
            repeatMode = :repeatMode,
            currentEpisodeId = :currentEpisodeId,
            currentPositionMs = :currentPositionMs,
            updatedAt = :updatedAt
        WHERE id = :queueId
        """,
    )
    suspend fun updateMusicQueuePlaybackState(
        queueId: String,
        shuffle: Boolean,
        repeatMode: String,
        currentEpisodeId: String?,
        currentPositionMs: Long,
        updatedAt: Long,
    )

    @Query("SELECT * FROM collections ORDER BY isSystem DESC, createdAt ASC")
    fun observeCollections(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collection_episodes ORDER BY collectionId ASC, position ASC")
    fun observeCollectionEpisodes(): Flow<List<CollectionEpisodeEntity>>

    @Query("SELECT * FROM listening_history ORDER BY playedAt DESC LIMIT 1000")
    fun observeListeningHistory(): Flow<List<ListeningHistoryEntity>>

    @Query("SELECT * FROM music_folders ORDER BY isDefault DESC, displayName COLLATE NOCASE ASC")
    fun observeMusicFolders(): Flow<List<MusicFolderEntity>>

    @Query("SELECT * FROM music_folders")
    suspend fun getAllMusicFolders(): List<MusicFolderEntity>

    // Scan Sessions (Storage Engine 2.0)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertScanSession(session: ScanSessionEntity)

    @Query("SELECT * FROM scan_sessions WHERE folderUri = :folderUri ORDER BY startedAt DESC LIMIT 1")
    suspend fun getLatestScanSession(folderUri: String): ScanSessionEntity?

    @Query("SELECT * FROM scan_sessions WHERE status = 'RUNNING'")
    suspend fun getInterruptedScanSessions(): List<ScanSessionEntity>

    @Query("UPDATE scan_sessions SET status = :newStatus, completedAt = :completedAt, errorMessage = :errorMessage WHERE status = 'RUNNING'")
    suspend fun markInterruptedScanSessions(newStatus: String, completedAt: Long, errorMessage: String)

    @Query("SELECT * FROM scan_sessions ORDER BY startedAt DESC LIMIT 20")
    fun observeRecentScanSessions(): Flow<List<ScanSessionEntity>>

    @Query("DELETE FROM scan_sessions WHERE startedAt < :cutoffMs")
    suspend fun pruneOldScanSessions(cutoffMs: Long)

    @Query("SELECT * FROM transcripts WHERE episodeId = :episodeId ORDER BY updatedAt DESC")
    fun observeTranscriptsForEpisode(episodeId: String): Flow<List<TranscriptEntity>>

    @Query("SELECT * FROM transcripts WHERE episodeId = :episodeId ORDER BY updatedAt DESC")
    suspend fun getTranscriptsForEpisode(episodeId: String): List<TranscriptEntity>

    @Query("SELECT * FROM transcript_cues WHERE transcriptId = :transcriptId ORDER BY cueIndex ASC")
    suspend fun getCuesForTranscript(transcriptId: String): List<TranscriptCueEntity>

    @Query("SELECT * FROM bookmarks WHERE episodeId = :episodeId ORDER BY positionMs ASC")
    fun observeBookmarks(episodeId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM raw_pcm_configs WHERE sourceUri = :sourceUri LIMIT 1")
    suspend fun getRawPcmConfig(sourceUri: String): RawPcmConfigEntity?

    @Query("SELECT * FROM raw_pcm_configs")
    fun observeRawPcmConfigs(): Flow<List<RawPcmConfigEntity>>

    @Query("SELECT * FROM episodes WHERE id = :episodeId LIMIT 1")
    suspend fun getEpisodeById(episodeId: String): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE id IN (:episodeIds)")
    suspend fun getEpisodesByIds(episodeIds: List<String>): List<EpisodeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHosts(items: List<HostEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(items: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPodcasts(items: List<PodcastEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpisodes(items: List<EpisodeEntity>)

    @Transaction
    suspend fun insertScanBatch(
        hosts: List<HostEntity>,
        categories: List<CategoryEntity>,
        podcasts: List<PodcastEntity>,
        episodes: List<EpisodeEntity>,
    ) {
        if (hosts.isNotEmpty()) insertHosts(hosts)
        if (categories.isNotEmpty()) insertCategories(categories)
        if (podcasts.isNotEmpty()) insertPodcasts(podcasts)
        if (episodes.isNotEmpty()) insertEpisodes(episodes)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEpisode(item: EpisodeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCollections(items: List<CollectionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCollectionEpisodes(items: List<CollectionEpisodeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertListeningHistory(items: List<ListeningHistoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertListeningHistoryEntry(entry: ListeningHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMusicFolder(folder: MusicFolderEntity)

    @Query("UPDATE music_folders SET isDefault = 0")
    suspend fun clearDefaultMusicFolderFlag()

    @Query("UPDATE music_folders SET isAvailable = :available WHERE treeUri = :treeUri")
    suspend fun setMusicFolderAvailable(treeUri: String, available: Boolean)

    @Query("UPDATE music_folders SET isEnabled = :enabled WHERE treeUri = :treeUri")
    suspend fun setMusicFolderEnabled(treeUri: String, enabled: Boolean)

    @Query("UPDATE music_folders SET initialIndexingCompleted = 1, lastScannedAt = :completedAt, lastSyncAt = :completedAt, trackCount = :trackCount, isAvailable = 1 WHERE treeUri = :treeUri")
    suspend fun markFolderInitialIndexingCompleted(treeUri: String, completedAt: Long, trackCount: Int)

    @Query("UPDATE music_folders SET lastSyncAt = :syncAt, lastMediaStoreGeneration = :generation WHERE treeUri = :treeUri")
    suspend fun updateFolderSyncMetadata(treeUri: String, syncAt: Long, generation: Long)

    @Query("DELETE FROM music_folders WHERE treeUri = :treeUri")
    suspend fun deleteMusicFolder(treeUri: String)

    @Query("DELETE FROM episodes WHERE id IN (:episodeIds)")
    suspend fun deleteEpisodesByIds(episodeIds: List<String>)

    @Query("DELETE FROM episodes WHERE folderUri = :folderUri")
    suspend fun deleteEpisodesByFolderUri(folderUri: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTranscript(transcript: TranscriptEntity)

    @Query("DELETE FROM transcript_cues WHERE transcriptId = :transcriptId")
    suspend fun deleteCuesForTranscript(transcriptId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTranscriptCues(cues: List<TranscriptCueEntity>)

    @Transaction
    suspend fun saveTranscriptWithCues(transcript: TranscriptEntity, cues: List<TranscriptCueEntity>) {
        upsertTranscript(transcript)
        deleteCuesForTranscript(transcript.id)
        if (cues.isNotEmpty()) insertTranscriptCues(cues)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :bookmarkId")
    suspend fun deleteBookmark(bookmarkId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRawPcmConfig(config: RawPcmConfigEntity)

    @Query("UPDATE podcasts SET isSubscribed = :subscribed WHERE id = :podcastId")
    suspend fun setPodcastSubscribed(podcastId: String, subscribed: Boolean)

    @Query("UPDATE episodes SET isSaved = :saved WHERE id = :episodeId")
    suspend fun setEpisodeSaved(episodeId: String, saved: Boolean)

    @Query("UPDATE episodes SET isDownloaded = :downloaded WHERE id = :episodeId")
    suspend fun setEpisodeDownloaded(episodeId: String, downloaded: Boolean)

    @Query("UPDATE episodes SET isDownloaded = 0, fileSizeBytes = 0 WHERE sourceType != 'local_music'")
    suspend fun clearAllDownloads()

    @Query("SELECT * FROM episodes WHERE sourceType = 'youtube_ref' AND linkedAudioUri LIKE 'file://%'")
    suspend fun getDownloadedYouTubeEpisodes(): List<EpisodeEntity>

    @Query("UPDATE episodes SET linkedAudioUri = NULL WHERE sourceType = 'youtube_ref' AND linkedAudioUri LIKE 'file://%'")
    suspend fun clearDownloadedYouTubeFileLinks()

    @Query("UPDATE episodes SET linkedAudioUri = NULL, fileSizeBytes = 0 WHERE id = :episodeId AND sourceType = 'youtube_ref' AND linkedAudioUri LIKE 'file://%'")
    suspend fun clearDownloadedYouTubeFileLinkForEpisode(episodeId: String)

    @Query("UPDATE episodes SET isDownloaded = 1 WHERE id IN (:episodeIds)")
    suspend fun markEpisodesDownloaded(episodeIds: List<String>)

    @Query("UPDATE episodes SET transcriptLrc = :transcriptLrc, transcriptLanguage = :language WHERE id = :episodeId")
    suspend fun updateEpisodeTranscript(episodeId: String, transcriptLrc: String?, language: String)

    @Query("UPDATE episodes SET linkedAudioUri = :audioUri WHERE id = :episodeId")
    suspend fun updateEpisodeLinkedAudio(episodeId: String, audioUri: String?)

    @Query(
        """
        UPDATE episodes
        SET playbackPositionMs = :positionMs,
            durationMs = CASE WHEN :durationMs > 0 THEN :durationMs ELSE durationMs END,
            isPlayed = CASE WHEN :markPlayed THEN 1 ELSE isPlayed END,
            lastPlayedAt = :timestampMs
        WHERE id = :episodeId
        """,
    )
    suspend fun updatePlaybackProgress(
        episodeId: String,
        positionMs: Long,
        durationMs: Long,
        markPlayed: Boolean,
        timestampMs: Long,
    )

    @Query(
        """
        UPDATE episodes
        SET playCount = playCount + 1,
            lastPlayedAt = :timestampMs,
            isPlayed = CASE WHEN :completed THEN 1 ELSE isPlayed END
        WHERE id = :episodeId
        """,
    )
    suspend fun incrementPlayCount(episodeId: String, completed: Boolean, timestampMs: Long)

    @Query("DELETE FROM listening_history")
    suspend fun clearListeningHistory()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCollection(collection: CollectionEntity)

    @Query("UPDATE collections SET name = :newName, updatedAt = :updatedAt WHERE id = :collectionId AND isSystem = 0")
    suspend fun renameCollection(collectionId: String, newName: String, updatedAt: Long)

    @Query("DELETE FROM collections WHERE id = :collectionId AND isSystem = 0")
    suspend fun deleteCollection(collectionId: String)

    @Query("DELETE FROM collection_episodes WHERE collectionId = :collectionId")
    suspend fun deleteAllEpisodesFromCollection(collectionId: String)

    @Query("DELETE FROM collection_episodes WHERE collectionId = :collectionId AND episodeId IN (:episodeIds)")
    suspend fun removeEpisodesFromCollection(collectionId: String, episodeIds: List<String>)

    @Query("SELECT COALESCE(MAX(position), -1) FROM collection_episodes WHERE collectionId = :collectionId")
    suspend fun maxCollectionPosition(collectionId: String): Int

    @Query("SELECT episodeId FROM collection_episodes WHERE collectionId = :collectionId ORDER BY position ASC")
    suspend fun getCollectionEpisodeIds(collectionId: String): List<String>

    @Transaction
    suspend fun replaceCollectionOrder(collectionId: String, orderedEpisodeIds: List<String>, updatedAt: Long) {
        deleteAllEpisodesFromCollection(collectionId)
        val rows = orderedEpisodeIds.distinct().mapIndexed { index, episodeId ->
            CollectionEpisodeEntity(collectionId = collectionId, episodeId = episodeId, position = index)
        }
        for (chunk in rows.chunked(500)) {
            insertCollectionEpisodes(chunk)
        }
        touchCollection(collectionId, updatedAt)
    }

    @Query("UPDATE collections SET updatedAt = :updatedAt WHERE id = :collectionId")
    suspend fun touchCollection(collectionId: String, updatedAt: Long)

    // Live AI caption history
    @Query("SELECT * FROM live_captions WHERE episodeId = :episodeId ORDER BY startMs ASC")
    suspend fun getLiveCaptionsForEpisode(episodeId: String): List<LiveCaptionEntity>

    @Query("SELECT * FROM live_captions WHERE episodeId = :episodeId ORDER BY startMs ASC")
    fun observeLiveCaptionsForEpisode(episodeId: String): Flow<List<LiveCaptionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLiveCaptions(captions: List<LiveCaptionEntity>)

    @Query("DELETE FROM live_captions WHERE episodeId = :episodeId")
    suspend fun deleteLiveCaptionsForEpisode(episodeId: String)

    @Query("DELETE FROM live_captions WHERE episodeId IN (:episodeIds)")
    suspend fun deleteLiveCaptionsForEpisodes(episodeIds: List<String>)

    @Query("DELETE FROM transcripts WHERE episodeId IN (:episodeIds)")
    suspend fun deleteTranscriptsForEpisodes(episodeIds: List<String>)

    @Query("DELETE FROM transcript_cues WHERE episodeId IN (:episodeIds)")
    suspend fun deleteTranscriptCuesForEpisodes(episodeIds: List<String>)

    @Query("DELETE FROM collection_episodes WHERE episodeId IN (:episodeIds)")
    suspend fun deleteCollectionEntriesForEpisodes(episodeIds: List<String>)

    @Query("SELECT id FROM episodes WHERE podcastId = :podcastId")
    suspend fun getEpisodeIdsByPodcast(podcastId: String): List<String>

    @Query("DELETE FROM episodes WHERE podcastId = :podcastId")
    suspend fun deleteEpisodesByPodcast(podcastId: String)

    @Query("DELETE FROM podcasts WHERE id = :podcastId")
    suspend fun deletePodcastById(podcastId: String)

    @Transaction
    suspend fun deleteImportedPodcastCascade(podcastId: String) {
        val episodeIds = getEpisodeIdsByPodcast(podcastId)
        for (chunk in episodeIds.chunked(500)) {
            deleteLiveCaptionsForEpisodes(chunk)
            deleteTranscriptCuesForEpisodes(chunk)
            deleteTranscriptsForEpisodes(chunk)
            deleteCollectionEntriesForEpisodes(chunk)
            deleteMusicQueueEntriesForEpisodes(chunk)
        }
        deleteEpisodesByPodcast(podcastId)
        deletePodcastById(podcastId)
    }

    @Transaction
    suspend fun deleteEpisodesCascade(episodeIds: List<String>) {
        if (episodeIds.isEmpty()) return
        for (chunk in episodeIds.chunked(500)) {
            deleteLiveCaptionsForEpisodes(chunk)
            deleteTranscriptCuesForEpisodes(chunk)
            deleteTranscriptsForEpisodes(chunk)
            deleteCollectionEntriesForEpisodes(chunk)
            deleteMusicQueueEntriesForEpisodes(chunk)
            deleteEpisodesByIds(chunk)
        }
        deleteOrphanPodcasts()
        deleteOrphanHosts()
        deleteOrphanCategories()
    }

    @Query(
        """
        UPDATE episodes
        SET sourceUri = :sourceUri,
            audioAsset = :audioAsset,
            folderUri = :folderUri,
            relativeFolderPath = :relativeFolderPath,
            title = :title,
            normalizedTitle = :normalizedTitle,
            lastModifiedMs = :lastModifiedMs
        WHERE id = :episodeId
        """,
    )
    suspend fun updateEpisodeFileLocation(
        episodeId: String,
        sourceUri: String,
        audioAsset: String,
        folderUri: String?,
        relativeFolderPath: String,
        title: String,
        normalizedTitle: String,
        lastModifiedMs: Long,
    )

    @Query("DELETE FROM episodes WHERE sourceType = 'demo'")
    suspend fun deleteAllDemoEpisodes()

    @Query("DELETE FROM podcasts WHERE sourceType = 'demo' OR id LIKE 'pod_%'")
    suspend fun deleteAllDemoPodcasts()

    @Query("DELETE FROM hosts WHERE id LIKE 'host_%'")
    suspend fun deleteAllDemoHosts()

    @Query("DELETE FROM categories WHERE id LIKE 'cat_%' AND id NOT IN ('cat_youtube_podcasts', 'cat_online_podcasts')")
    suspend fun deleteAllDemoCategories()

    @Query("DELETE FROM collections WHERE id IN ('col_morning_commute', 'col_deep_tech', 'col_weekend_science')")
    suspend fun deleteAllDemoCollections()

    @Query("DELETE FROM collection_episodes WHERE collectionId IN ('col_morning_commute', 'col_deep_tech', 'col_weekend_science') OR episodeId LIKE 'ep_%'")
    suspend fun deleteAllDemoCollectionEpisodes()

    @Query("DELETE FROM listening_history WHERE episodeId LIKE 'ep_%'")
    suspend fun deleteAllDemoHistory()

    @Query("DELETE FROM podcasts WHERE id NOT IN (SELECT DISTINCT podcastId FROM episodes)")
    suspend fun deleteOrphanPodcasts()

    @Query("DELETE FROM hosts WHERE id NOT IN (SELECT DISTINCT hostId FROM podcasts) AND id NOT IN (SELECT DISTINCT hostId FROM episodes)")
    suspend fun deleteOrphanHosts()

    @Query("DELETE FROM categories WHERE id NOT IN (SELECT DISTINCT categoryId FROM podcasts) AND id NOT IN (SELECT DISTINCT categoryId FROM episodes)")
    suspend fun deleteOrphanCategories()

    // Equalizer custom presets
    @Query("SELECT * FROM equalizer_presets ORDER BY updatedAt DESC, name ASC")
    fun observeEqualizerPresets(): Flow<List<EqualizerPresetEntity>>

    @Query("SELECT * FROM equalizer_presets ORDER BY updatedAt DESC, name ASC")
    suspend fun getAllEqualizerPresets(): List<EqualizerPresetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEqualizerPreset(preset: EqualizerPresetEntity)

    @Query("UPDATE equalizer_presets SET name = :newName, updatedAt = :updatedAt WHERE id = :presetId")
    suspend fun renameEqualizerPreset(presetId: String, newName: String, updatedAt: Long)

    @Query("DELETE FROM equalizer_presets WHERE id = :presetId")
    suspend fun deleteEqualizerPreset(presetId: String)

    @Query("DELETE FROM equalizer_presets")
    suspend fun deleteAllCustomEqualizerPresets()

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM episodes WHERE sourceType = 'demo'
            UNION ALL SELECT 1 FROM podcasts WHERE sourceType = 'demo' OR id LIKE 'pod_%'
            UNION ALL SELECT 1 FROM hosts WHERE id LIKE 'host_%'
            UNION ALL SELECT 1 FROM collections WHERE id IN ('col_morning_commute', 'col_deep_tech', 'col_weekend_science')
            LIMIT 1
        )
        """,
    )
    suspend fun hasSampleData(): Boolean

    @Transaction
    suspend fun purgeSampleData() {
        deleteAllDemoCollectionEpisodes()
        deleteAllDemoCollections()
        deleteAllDemoHistory()
        deleteAllDemoEpisodes()
        deleteAllDemoPodcasts()
        deleteAllDemoHosts()
        deleteAllDemoCategories()
    }
}
