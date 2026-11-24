/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.data

import com.podcasty.app.asr.CaptionTimingPrecision
import com.podcasty.app.asr.LiveCaptionSegment
import com.podcasty.app.asr.LiveCaptionStatus
import com.podcasty.app.core.UnicodeSortUtil
import com.podcasty.app.database.BookmarkEntity
import com.podcasty.app.database.CategoryEntity
import com.podcasty.app.database.CollectionEntity
import com.podcasty.app.database.CollectionEpisodeEntity
import com.podcasty.app.database.EpisodeEntity
import com.podcasty.app.database.EpisodeSummaryTuple
import com.podcasty.app.database.ExcludedFolderEntity
import com.podcasty.app.database.HostEntity
import com.podcasty.app.database.ListeningHistoryEntity
import com.podcasty.app.database.LiveCaptionEntity
import com.podcasty.app.database.MusicFolderEntity
import com.podcasty.app.database.MusicQueueEntity
import com.podcasty.app.database.MusicQueueItemEntity
import com.podcasty.app.database.PodcastEntity
import com.podcasty.app.database.PodcastyDao
import com.podcasty.app.database.RawPcmConfigEntity
import com.podcasty.app.database.SeedPodcastLibrary
import com.podcasty.app.database.TranscriptCueEntity
import com.podcasty.app.database.TranscriptEntity
import com.podcasty.app.domain.AlbumSortOption
import com.podcasty.app.domain.AlbumSummary
import com.podcasty.app.domain.ArtistSummary
import com.podcasty.app.domain.Bookmark
import com.podcasty.app.domain.Category
import com.podcasty.app.domain.Episode
import com.podcasty.app.domain.ExcludedFolder
import com.podcasty.app.domain.Host
import com.podcasty.app.domain.LinearFolderSummary
import com.podcasty.app.domain.ListeningHistoryEntry
import com.podcasty.app.domain.MediaContentType
import com.podcasty.app.domain.MediaImportSource
import com.podcasty.app.domain.MediaSourceType
import com.podcasty.app.domain.MusicFolder
import com.podcasty.app.domain.MusicQueue
import com.podcasty.app.domain.Podcast
import com.podcasty.app.domain.PodcastCollection
import com.podcasty.app.domain.PodcastRepository
import com.podcasty.app.domain.RawPcmConfig
import com.podcasty.app.domain.RepeatMode
import com.podcasty.app.domain.SongSortOption
import com.podcasty.app.domain.SortOrder
import com.podcasty.app.domain.StorageProviderType
import com.podcasty.app.podcast.ExtractedYouTubeTranscript
import com.podcasty.app.podcast.ParsedPodcastFeed
import com.podcasty.app.podcast.RssFeedImportResult
import com.podcasty.app.podcast.YouTubeReferenceMetadata
import com.podcasty.app.podcast.YouTubeVideoMetadata
import com.podcasty.app.tags.EditableAudioTags
import com.podcasty.app.transcript.TranscriptCue
import com.podcasty.app.transcript.TranscriptDocument
import com.podcasty.app.transcript.TranscriptFormat
import com.podcasty.app.transcript.TranscriptParser
import com.podcasty.app.transcript.TranscriptSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.UUID

@OptIn(FlowPreview::class)
class PodcastRepositoryImpl(
    private val dao: PodcastyDao,
) : PodcastRepository {
    private val seedMutex = Mutex()
    @Volatile
    private var sampleDataChecked = false
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val libraryRevision = MutableStateFlow(0L)

    fun notifyLibraryMutated() {
        libraryRevision.update { it + 1 }
    }

    override val hosts: Flow<List<Host>> = dao.observeHosts()
        .map { list -> list.map { it.toDomain() } }
        .distinctUntilChanged()
        .shareIn(repoScope, SharingStarted.WhileSubscribed(5_000L), replay = 1)

    override val categories: Flow<List<Category>> = dao.observeCategories()
        .map { list -> list.map { Category(id = it.id, name = it.name, createdAt = it.createdAt) } }
        .distinctUntilChanged()
        .shareIn(repoScope, SharingStarted.WhileSubscribed(5_000L), replay = 1)

    override val podcasts: Flow<List<Podcast>> = dao.observePodcasts()
        .map { list -> list.map { it.toDomain() } }
        .distinctUntilChanged()
        .shareIn(repoScope, SharingStarted.WhileSubscribed(5_000L), replay = 1)

    override val episodes: Flow<List<Episode>> = dao.observeEpisodeSummaries()
        .map { list -> list.map { it.toDomain() } }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .shareIn(repoScope, SharingStarted.WhileSubscribed(5_000L), replay = 1)

    override val listeningHistory: Flow<List<ListeningHistoryEntry>> = dao.observeListeningHistory()
        .map { list -> list.map { it.toDomain() } }
        .distinctUntilChanged()
        .shareIn(repoScope, SharingStarted.WhileSubscribed(5_000L), replay = 1)

    override val musicFolders: Flow<List<MusicFolder>> = dao.observeMusicFolders()
        .map { list -> list.map { it.toDomain() } }
        .distinctUntilChanged()
        .shareIn(repoScope, SharingStarted.WhileSubscribed(5_000L), replay = 1)

    private val smartPlaylistIdsFlow = combine(
        dao.observeSavedEpisodeIds().distinctUntilChanged(),
        dao.observeRecentlyPlayedEpisodeIds().distinctUntilChanged(),
        dao.observeMostPlayedEpisodeIds().distinctUntilChanged(),
        dao.observeRecentlyAddedEpisodeIds().distinctUntilChanged(),
        dao.observeNeverPlayedEpisodeIds().distinctUntilChanged(),
    ) { saved, recent, mostPlayed, recentlyAdded, neverPlayed ->
        listOf(saved, recent, mostPlayed, recentlyAdded, neverPlayed)
    }

    private val smartExtraIdsFlow = combine(
        dao.observeRecentlyDownloadedEpisodeIds().distinctUntilChanged(),
        dao.observeHighResEpisodeIds().distinctUntilChanged(),
        dao.observeLocalMusicCollectionIds().distinctUntilChanged(),
    ) { downloaded, highRes, localMusic ->
        Triple(downloaded, highRes, localMusic)
    }

    /**
     * Combines manual Room playlists with SQL-computed offline Smart Playlists, avoiding
     * full-library Kotlin sorts or lowercase string allocations on 5k-25k+ song libraries.
     */
    override val collections: Flow<List<PodcastCollection>> = combine(
        dao.observeCollections().distinctUntilChanged(),
        dao.observeCollectionEpisodes().distinctUntilChanged(),
        smartPlaylistIdsFlow,
        smartExtraIdsFlow,
    ) { customEntities, mappings, coreLists, (recentlyDownloadedIds, highResIds, localMusicIds) ->
        val savedIds = coreLists[0]
        val recentIds = coreLists[1]
        val mostPlayedIds = coreLists[2]
        val recentlyAddedIds = coreLists[3]
        val neverPlayedIds = coreLists[4]
        val byCollection = mappings.groupBy { it.collectionId }

        val smartCollections = buildList {
            add(PodcastCollection(id = PodcastCollection.SavedCollectionId, name = "Saved & favorites", isSystem = true, episodeIds = savedIds))
            add(PodcastCollection(id = PodcastCollection.RecentlyPlayedCollectionId, name = "Recently played", isSystem = true, episodeIds = recentIds))
            add(PodcastCollection(id = PodcastCollection.MostPlayedCollectionId, name = "Most played", isSystem = true, episodeIds = mostPlayedIds))
            add(PodcastCollection(id = PodcastCollection.RecentlyAddedCollectionId, name = "Recently added", isSystem = true, episodeIds = recentlyAddedIds))
            add(PodcastCollection(id = PodcastCollection.NeverPlayedCollectionId, name = "Never played", isSystem = true, episodeIds = neverPlayedIds))
            add(PodcastCollection(id = PodcastCollection.RecentlyDownloadedCollectionId, name = "Recently downloaded", isSystem = true, episodeIds = recentlyDownloadedIds))
            if (highResIds.isNotEmpty()) {
                add(PodcastCollection(id = PodcastCollection.HighResAudioCollectionId, name = "High-resolution audio", isSystem = true, episodeIds = highResIds))
            }
            if (localMusicIds.isNotEmpty()) {
                add(PodcastCollection(id = PodcastCollection.LocalMusicCollectionId, name = "Local music library", isSystem = true, episodeIds = localMusicIds))
            }
        }

        val customCollections = customEntities.filter { !it.isSystem }.map { entity ->
            PodcastCollection(
                id = entity.id,
                name = entity.name,
                isSystem = false,
                episodeIds = byCollection[entity.id].orEmpty().map { it.episodeId },
                customArtworkPath = entity.customArtworkPath,
                createdAt = entity.createdAt,
                updatedAt = entity.updatedAt,
            )
        }
        smartCollections + customCollections
    }.distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .shareIn(repoScope, SharingStarted.WhileSubscribed(5_000L), replay = 1)

    override suspend fun ensureSeeded() {
        if (sampleDataChecked) return
        seedMutex.withLock {
            if (sampleDataChecked) return
            if (dao.hasSampleData()) {
                dao.purgeSampleData()
            }
            sampleDataChecked = true
        }
    }

    override suspend fun getEpisodeById(episodeId: String): Episode? =
        dao.getEpisodeById(episodeId)?.toDomain()

    override suspend fun getEpisodesByIds(episodeIds: List<String>): List<Episode> {
        if (episodeIds.isEmpty()) return emptyList()
        val byId = HashMap<String, EpisodeEntity>(episodeIds.size)
        for (chunk in episodeIds.distinct().chunked(500)) {
            for (entity in dao.getEpisodesByIds(chunk)) {
                byId[entity.id] = entity
            }
        }
        return episodeIds.mapNotNull { byId[it]?.toDomain() }
    }

    override suspend fun setPodcastSubscribed(podcastId: String, subscribed: Boolean) {
        dao.setPodcastSubscribed(podcastId, subscribed)
    }

    override suspend fun setEpisodeSaved(episodeId: String, saved: Boolean) {
        dao.setEpisodeSaved(episodeId, saved)
    }

    override suspend fun setMusicFolderEnabled(treeUri: String, enabled: Boolean) {
        dao.setMusicFolderEnabled(treeUri, enabled)
    }

    override suspend fun setEpisodeDownloaded(episodeId: String, downloaded: Boolean) {
        if (!downloaded) {
            val current = dao.getEpisodeById(episodeId)
            val linked = current?.linkedAudioUri
            if (current?.sourceType == MediaSourceType.YouTubeReference.key && linked?.startsWith("file://") == true) {
                runCatching {
                    val path = android.net.Uri.parse(linked).path
                    if (!path.isNullOrBlank()) {
                        val audioFile = java.io.File(path)
                        audioFile.delete()
                        java.io.File(audioFile.parentFile, "${audioFile.nameWithoutExtension}.lrc").delete()
                    }
                }
                dao.clearDownloadedYouTubeFileLinkForEpisode(episodeId)
            }
        }
        dao.setEpisodeDownloaded(episodeId, downloaded)
    }

    override suspend fun clearAllDownloads() {
        val all = dao.getDownloadedYouTubeEpisodes()
        for (ep in all) {
            val linked = ep.linkedAudioUri
            if (linked?.startsWith("file://") == true) {
                runCatching {
                    val path = android.net.Uri.parse(linked).path
                    if (!path.isNullOrBlank()) {
                        val audioFile = java.io.File(path)
                        audioFile.delete()
                        java.io.File(audioFile.parentFile, "${audioFile.nameWithoutExtension}.lrc").delete()
                    }
                }
            }
        }
        dao.clearDownloadedYouTubeFileLinks()
        dao.clearAllDownloads()
    }

    override suspend fun restoreDemoDownloads() {
        // No-op: bundled sample episodes have been removed.
    }

    override suspend fun updatePlaybackProgress(
        episodeId: String,
        positionMs: Long,
        durationMs: Long,
        markPlayed: Boolean,
    ) {
        dao.updatePlaybackProgress(
            episodeId = episodeId,
            positionMs = positionMs.coerceAtLeast(0L),
            durationMs = durationMs.coerceAtLeast(0L),
            markPlayed = markPlayed,
            timestampMs = System.currentTimeMillis(),
        )
    }

    override suspend fun recordListeningSession(
        episode: Episode,
        listenedSeconds: Int,
        completed: Boolean,
        skipped: Boolean,
    ) {
        val now = System.currentTimeMillis()
        dao.incrementPlayCount(episode.id, completed = completed, timestampMs = now)
        dao.insertListeningHistoryEntry(
            ListeningHistoryEntity(
                episodeId = episode.id,
                episodeTitle = episode.title,
                podcastId = episode.podcastId,
                podcastTitle = episode.podcastTitle,
                hostId = episode.hostId,
                hostName = episode.hostName,
                categoryName = episode.categoryName,
                artworkAsset = episode.artworkAsset,
                listenedSeconds = listenedSeconds.coerceAtLeast(1),
                completed = completed,
                skipped = skipped,
                playedAt = now,
            ),
        )
    }

    override suspend fun clearListeningHistory() {
        dao.clearListeningHistory()
    }

    override suspend fun createCollection(name: String, initialEpisodeIds: List<String>): String {
        val id = "col_${UUID.randomUUID().toString().take(8)}"
        val now = System.currentTimeMillis()
        dao.upsertCollection(
            CollectionEntity(
                id = id,
                name = name.trim(),
                isSystem = false,
                customArtworkPath = null,
                createdAt = now,
                updatedAt = now,
            ),
        )
        if (initialEpisodeIds.isNotEmpty()) {
            val rows = initialEpisodeIds.distinct().mapIndexed { index, episodeId ->
                CollectionEpisodeEntity(collectionId = id, episodeId = episodeId, position = index)
            }
            dao.insertCollectionEpisodes(rows)
        }
        return id
    }

    override suspend fun renameCollection(collectionId: String, newName: String) {
        dao.renameCollection(collectionId, newName.trim(), System.currentTimeMillis())
    }

    override suspend fun deleteCollection(collectionId: String) {
        dao.deleteAllEpisodesFromCollection(collectionId)
        dao.deleteCollection(collectionId)
    }

    override suspend fun setCollectionMembership(
        collectionId: String,
        episodeIds: List<String>,
        add: Boolean,
    ) {
        if (episodeIds.isEmpty()) return
        if (add) {
            val existing = dao.getCollectionEpisodeIds(collectionId).toSet()
            var nextPos = dao.maxCollectionPosition(collectionId) + 1
            val toInsert = episodeIds.distinct().filter { it !in existing }.map { episodeId ->
                CollectionEpisodeEntity(collectionId = collectionId, episodeId = episodeId, position = nextPos++)
            }
            if (toInsert.isNotEmpty()) {
                dao.insertCollectionEpisodes(toInsert)
                dao.touchCollection(collectionId, System.currentTimeMillis())
            }
        } else {
            for (chunk in episodeIds.chunked(500)) {
                dao.removeEpisodesFromCollection(collectionId, chunk)
            }
            dao.touchCollection(collectionId, System.currentTimeMillis())
        }
    }

    override suspend fun reorderCollectionEpisodes(collectionId: String, orderedEpisodeIds: List<String>) {
        dao.replaceCollectionOrder(collectionId, orderedEpisodeIds, System.currentTimeMillis())
    }

    override suspend fun saveYouTubeReferenceEpisode(
        metadata: YouTubeReferenceMetadata,
        linkedAudioUri: String?,
        initialTranscriptText: String?,
        transcriptFileName: String?,
    ): String {
        val now = System.currentTimeMillis()
        val hostId = "yt_host_${shortHash(metadata.channelName.lowercase())}"
        val categoryId = "cat_youtube_podcasts"
        val podcastId = "yt_show_${shortHash(metadata.channelName.lowercase())}"
        val episodeId = "yt_ep_${metadata.videoId}"

        dao.insertCategories(listOf(CategoryEntity(categoryId, "YouTube Podcasts", now)))
        dao.insertHosts(
            listOf(
                HostEntity(
                    id = hostId,
                    name = metadata.channelName,
                    bio = "Imported YouTube channel. Audio streams in Podcasty, with an official YouTube fallback.",
                    artworkAsset = null,
                    createdAt = now,
                ),
            ),
        )
        dao.insertPodcasts(
            listOf(
                PodcastEntity(
                    id = podcastId,
                    title = metadata.channelName,
                    hostId = hostId,
                    hostName = metadata.channelName,
                    categoryId = categoryId,
                    categoryName = "YouTube Podcasts",
                    description = "Saved YouTube podcasts. Audio plays in Podcasty's native player; a creator-linked RSS/MP3 source can override the YouTube stream.",
                    publisher = "YouTube",
                    artworkAsset = null,
                    isSubscribed = true,
                    isRecommended = false,
                    isPopular = false,
                    addedAt = now,
                    feedUrl = metadata.canonicalUrl,
                    sourceType = MediaSourceType.YouTubeReference.key,
                    youtubeVideoId = metadata.videoId,
                    contentType = MediaContentType.Podcast.key,
                    importSource = MediaImportSource.YouTube.key,
                    importedAt = now,
                ),
            ),
        )

        val existingEpisode = dao.getEpisodeById(episodeId)
        val lrcMirror = initialTranscriptText?.takeIf { it.isNotBlank() }?.let { raw ->
            val doc = TranscriptParser.parse(episodeId, raw, transcriptFileName, source = TranscriptSource.AuthorizedImport)
            TranscriptParser.toLrcString(doc.cues)
        }

        dao.upsertEpisode(
            EpisodeEntity(
                id = episodeId,
                podcastId = podcastId,
                podcastTitle = metadata.channelName,
                hostId = hostId,
                hostName = metadata.channelName,
                categoryId = categoryId,
                categoryName = "YouTube Podcasts",
                episodeNumber = 1,
                title = metadata.title,
                summary = "YouTube audio: ${metadata.canonicalUrl}\nPlays audio-only in Podcasty. A creator-linked RSS/MP3 source and SRT/VTT/LRC transcript can be used instead.",
                artworkAsset = existingEpisode?.artworkAsset,
                audioAsset = existingEpisode?.audioAsset ?: "",
                durationMs = existingEpisode?.durationMs ?: 0L,
                playbackPositionMs = existingEpisode?.playbackPositionMs ?: 0L,
                isPlayed = existingEpisode?.isPlayed ?: false,
                isSaved = existingEpisode?.isSaved ?: true,
                isDownloaded = existingEpisode?.isDownloaded ?: false,
                fileSizeBytes = existingEpisode?.fileSizeBytes ?: 0L,
                playCount = existingEpisode?.playCount ?: 0,
                publishedAt = now,
                lastPlayedAt = existingEpisode?.lastPlayedAt,
                transcriptLrc = lrcMirror ?: existingEpisode?.transcriptLrc,
                sourceType = MediaSourceType.YouTubeReference.key,
                sourceUri = metadata.canonicalUrl,
                youtubeUrl = metadata.canonicalUrl,
                linkedAudioUri = linkedAudioUri?.takeIf { it.isNotBlank() } ?: existingEpisode?.linkedAudioUri,
                containerFormat = if (linkedAudioUri.isNullOrBlank()) "YouTube Audio" else "RSS Audio",
                audioCodec = if (linkedAudioUri.isNullOrBlank()) "Streamed Audio" else "Linked Audio",
                normalizedTitle = UnicodeSortUtil.normalizeKey(metadata.title),
                normalizedArtist = UnicodeSortUtil.normalizeKey(metadata.channelName),
                normalizedAlbum = UnicodeSortUtil.normalizeKey(metadata.channelName),
                contentType = MediaContentType.Podcast.key,
                importSource = MediaImportSource.YouTube.key,
                importedAt = now,
            ),
        )
        if (!initialTranscriptText.isNullOrBlank()) {
            saveTranscript(
                episodeId = episodeId,
                rawContent = initialTranscriptText,
                fileName = transcriptFileName,
                source = TranscriptSource.AuthorizedImport,
            )
        }
        notifyLibraryMutated()
        return episodeId
    }

    override suspend fun linkAudioToEpisode(episodeId: String, audioUri: String?) {
        dao.updateEpisodeLinkedAudio(episodeId, audioUri?.trim()?.takeIf { it.isNotEmpty() })
    }

    override suspend fun saveImportedRssFeed(feed: RssFeedImportResult): String {
        val now = System.currentTimeMillis()
        val hostId = "rss_host_${shortHash(feed.hostName.lowercase())}"
        val categoryId = "rss_cat_${shortHash(feed.categoryName.lowercase())}"
        val podcastId = "rss_show_${shortHash(feed.podcastTitle.lowercase())}"

        dao.insertCategories(listOf(CategoryEntity(categoryId, feed.categoryName, now)))
        dao.insertHosts(
            listOf(
                HostEntity(
                    id = hostId,
                    name = feed.hostName,
                    bio = feed.description.ifBlank { "RSS podcast host" },
                    artworkAsset = null,
                    createdAt = now,
                ),
            ),
        )
        dao.insertPodcasts(
            listOf(
                PodcastEntity(
                    id = podcastId,
                    title = feed.podcastTitle,
                    hostId = hostId,
                    hostName = feed.hostName,
                    categoryId = categoryId,
                    categoryName = feed.categoryName,
                    description = feed.description,
                    publisher = feed.hostName,
                    artworkAsset = null,
                    isSubscribed = true,
                    isRecommended = false,
                    isPopular = false,
                    addedAt = now,
                    sourceType = MediaSourceType.RssPodcast.key,
                    contentType = MediaContentType.Podcast.key,
                    importSource = MediaImportSource.Rss.key,
                    importedAt = now,
                ),
            ),
        )
        val entities = feed.episodes.mapIndexed { idx, ep ->
            EpisodeEntity(
                id = "rss_ep_${shortHash("${podcastId}::${ep.guid}")}",
                podcastId = podcastId,
                podcastTitle = feed.podcastTitle,
                hostId = hostId,
                hostName = feed.hostName,
                categoryId = categoryId,
                categoryName = feed.categoryName,
                episodeNumber = feed.episodes.size - idx,
                title = ep.title,
                summary = ep.summary,
                artworkAsset = null,
                audioAsset = "",
                durationMs = ep.durationMs,
                playbackPositionMs = 0L,
                isPlayed = false,
                isSaved = false,
                isDownloaded = false,
                fileSizeBytes = 0L,
                playCount = 0,
                publishedAt = ep.publishedAt,
                lastPlayedAt = null,
                transcriptLrc = null,
                sourceType = MediaSourceType.RssPodcast.key,
                sourceUri = ep.audioUrl,
                containerFormat = "RSS",
                audioCodec = ep.audioUrl.substringAfterLast('.', "MP3").substringBefore('?').uppercase().take(6),
                transcriptLanguage = ep.transcriptLanguage,
                normalizedTitle = UnicodeSortUtil.normalizeKey(ep.title),
                normalizedArtist = UnicodeSortUtil.normalizeKey(feed.hostName),
                normalizedAlbum = UnicodeSortUtil.normalizeKey(feed.podcastTitle),
                contentType = MediaContentType.Podcast.key,
                importSource = MediaImportSource.Rss.key,
                importedAt = now,
            )
        }
        if (entities.isNotEmpty()) dao.insertEpisodes(entities)
        notifyLibraryMutated()
        return podcastId
    }

    override suspend fun saveParsedPodcastFeed(
        feed: ParsedPodcastFeed,
        preferredCaptionLanguage: String,
    ): String {
        val now = System.currentTimeMillis()
        val hostId = "rss_host_${shortHash(feed.author.lowercase())}"
        val categoryId = "rss_cat_${shortHash(feed.categoryName.lowercase())}"
        val podcastKey = feed.feedUrl.ifBlank { feed.title }.lowercase()
        val podcastId = "rss_show_${shortHash(podcastKey)}"

        dao.insertCategories(listOf(CategoryEntity(categoryId, feed.categoryName, now)))
        dao.insertHosts(
            listOf(
                HostEntity(
                    id = hostId,
                    name = feed.author,
                    bio = feed.description.ifBlank { "RSS podcast creator" },
                    artworkAsset = feed.artworkUrl,
                    createdAt = now,
                ),
            ),
        )
        dao.insertPodcasts(
            listOf(
                PodcastEntity(
                    id = podcastId,
                    title = feed.title,
                    hostId = hostId,
                    hostName = feed.author,
                    categoryId = categoryId,
                    categoryName = feed.categoryName,
                    description = feed.description,
                    publisher = feed.publisher,
                    artworkAsset = feed.artworkUrl,
                    isSubscribed = true,
                    isRecommended = false,
                    isPopular = false,
                    addedAt = now,
                    feedUrl = feed.feedUrl.takeIf { it.isNotBlank() },
                    sourceType = MediaSourceType.RssPodcast.key,
                    preferredCaptionLanguage = preferredCaptionLanguage,
                    contentType = MediaContentType.Podcast.key,
                    importSource = MediaImportSource.Rss.key,
                    importedAt = now,
                ),
            ),
        )
        val existingById = dao.getEpisodesByIds(
            feed.episodes.map { ep -> "rss_ep_${shortHash("$podcastId::${ep.guid}")}" },
        ).associateBy { it.id }
        val entities = feed.episodes.mapIndexed { idx, ep ->
            val epId = "rss_ep_${shortHash("$podcastId::${ep.guid}")}"
            val existing = existingById[epId]
            EpisodeEntity(
                id = epId,
                podcastId = podcastId,
                podcastTitle = feed.title,
                hostId = hostId,
                hostName = feed.author,
                categoryId = categoryId,
                categoryName = feed.categoryName,
                episodeNumber = feed.episodes.size - idx,
                title = ep.title,
                summary = ep.description,
                artworkAsset = ep.artworkUrl ?: feed.artworkUrl,
                audioAsset = existing?.audioAsset ?: "",
                durationMs = ep.durationMs.takeIf { it > 0L } ?: (existing?.durationMs ?: 0L),
                playbackPositionMs = existing?.playbackPositionMs ?: 0L,
                isPlayed = existing?.isPlayed ?: false,
                isSaved = existing?.isSaved ?: false,
                isDownloaded = existing?.isDownloaded ?: false,
                fileSizeBytes = existing?.fileSizeBytes ?: 0L,
                playCount = existing?.playCount ?: 0,
                publishedAt = ep.publishedAtMs,
                lastPlayedAt = existing?.lastPlayedAt,
                transcriptLrc = existing?.transcriptLrc,
                sourceType = MediaSourceType.RssPodcast.key,
                sourceUri = ep.audioUrl,
                linkedAudioUri = existing?.linkedAudioUri,
                containerFormat = "RSS Stream",
                audioCodec = ep.audioUrl.substringAfterLast('.', "MP3").substringBefore('?').uppercase().take(6),
                transcriptLanguage = preferredCaptionLanguage,
                normalizedTitle = UnicodeSortUtil.normalizeKey(ep.title),
                normalizedArtist = UnicodeSortUtil.normalizeKey(feed.author),
                normalizedAlbum = UnicodeSortUtil.normalizeKey(feed.title),
                contentType = MediaContentType.Podcast.key,
                importSource = MediaImportSource.Rss.key,
                importedAt = existing?.importedAt?.takeIf { it > 0L } ?: now,
            )
        }
        if (entities.isNotEmpty()) dao.insertEpisodes(entities)
        notifyLibraryMutated()
        return podcastId
    }

    override suspend fun saveDirectAudioPodcast(
        audioUrl: String,
        title: String,
        creator: String,
        preferredCaptionLanguage: String,
    ): Pair<String, String> {
        val now = System.currentTimeMillis()
        val hostId = "direct_host_${shortHash(creator.lowercase())}"
        val categoryId = "cat_online_podcasts"
        val podcastId = "direct_show_${shortHash(audioUrl.lowercase())}"
        val episodeId = "direct_ep_${shortHash(audioUrl.lowercase())}"

        dao.insertCategories(listOf(CategoryEntity(categoryId, "Online Podcasts", now)))
        dao.insertHosts(listOf(HostEntity(hostId, creator, "Direct online podcast audio stream", null, now)))
        dao.insertPodcasts(
            listOf(
                PodcastEntity(
                    id = podcastId,
                    title = title,
                    hostId = hostId,
                    hostName = creator,
                    categoryId = categoryId,
                    categoryName = "Online Podcasts",
                    description = "Direct HTTPS podcast audio stream: $audioUrl",
                    publisher = creator,
                    artworkAsset = null,
                    isSubscribed = true,
                    isRecommended = false,
                    isPopular = false,
                    addedAt = now,
                    feedUrl = audioUrl,
                    sourceType = MediaSourceType.RssPodcast.key,
                    preferredCaptionLanguage = preferredCaptionLanguage,
                    contentType = MediaContentType.Podcast.key,
                    importSource = MediaImportSource.DirectAudio.key,
                    importedAt = now,
                ),
            ),
        )
        val existingDirect = dao.getEpisodeById(episodeId)
        dao.upsertEpisode(
            EpisodeEntity(
                id = episodeId,
                podcastId = podcastId,
                podcastTitle = title,
                hostId = hostId,
                hostName = creator,
                categoryId = categoryId,
                categoryName = "Online Podcasts",
                episodeNumber = 1,
                title = title,
                summary = "Direct online audio stream ($audioUrl). Streams online without saving audio files.",
                artworkAsset = existingDirect?.artworkAsset,
                audioAsset = existingDirect?.audioAsset ?: "",
                durationMs = existingDirect?.durationMs ?: 0L,
                playbackPositionMs = existingDirect?.playbackPositionMs ?: 0L,
                isPlayed = existingDirect?.isPlayed ?: false,
                isSaved = existingDirect?.isSaved ?: true,
                isDownloaded = existingDirect?.isDownloaded ?: false,
                fileSizeBytes = existingDirect?.fileSizeBytes ?: 0L,
                playCount = existingDirect?.playCount ?: 0,
                publishedAt = now,
                lastPlayedAt = existingDirect?.lastPlayedAt,
                transcriptLrc = existingDirect?.transcriptLrc,
                sourceType = MediaSourceType.RssPodcast.key,
                sourceUri = audioUrl,
                containerFormat = "Direct Stream",
                audioCodec = audioUrl.substringAfterLast('.', "MP3").substringBefore('?').uppercase().take(6),
                transcriptLanguage = preferredCaptionLanguage,
                normalizedTitle = UnicodeSortUtil.normalizeKey(title),
                normalizedArtist = UnicodeSortUtil.normalizeKey(creator),
                normalizedAlbum = UnicodeSortUtil.normalizeKey(title),
                contentType = MediaContentType.Podcast.key,
                importSource = MediaImportSource.DirectAudio.key,
                importedAt = now,
            ),
        )
        notifyLibraryMutated()
        return podcastId to episodeId
    }

    override suspend fun saveYouTubeVideoImport(
        metadata: YouTubeVideoMetadata,
        preferredCaptionLanguage: String,
        linkedAudioUri: String?,
        extractedTranscript: ExtractedYouTubeTranscript?,
        isDownloadedFile: Boolean,
        downloadedFileSizeBytes: Long,
        localArtworkPath: String?,
        bitrateKbps: Int,
        audioContainerFormat: String?,
        audioCodecLabel: String?,
        audioLanguageCode: String?,
    ): Pair<String, String> {
        val now = System.currentTimeMillis()
        val hostId = "yt_host_${shortHash(metadata.channelName.lowercase())}"
        val categoryId = "cat_youtube_podcasts"
        val podcastId = "yt_show_${shortHash(metadata.channelName.lowercase())}"
        val episodeId = "yt_ep_${metadata.videoId}"
        val cleanedLinkedAudio = linkedAudioUri?.trim()?.takeIf { it.isNotEmpty() }
        val resolvedTitle = metadata.title.ifBlank {
            extractedTranscript?.title?.ifBlank { "YouTube Video (${metadata.videoId})" }
                ?: "YouTube Video (${metadata.videoId})"
        }
        val resolvedDescription = metadata.description.ifBlank { extractedTranscript?.description.orEmpty() }
        val resolvedLang = extractedTranscript?.language?.ifBlank { preferredCaptionLanguage } ?: preferredCaptionLanguage
        val lrcMirror = extractedTranscript?.takeIf { it.cues.isNotEmpty() }?.toLrcContent()
        val resolvedArtwork = localArtworkPath?.takeIf { it.isNotBlank() } ?: metadata.thumbnailUrl

        dao.insertCategories(listOf(CategoryEntity(categoryId, "YouTube Podcasts", now)))
        dao.insertHosts(
            listOf(
                HostEntity(
                    id = hostId,
                    name = metadata.channelName,
                    bio = "Saved YouTube channel. Audio downloaded with embedded synced captions.",
                    artworkAsset = resolvedArtwork,
                    createdAt = now,
                ),
            ),
        )
        dao.insertPodcasts(
            listOf(
                PodcastEntity(
                    id = podcastId,
                    title = metadata.channelName,
                    hostId = hostId,
                    hostName = metadata.channelName,
                    categoryId = categoryId,
                    categoryName = "YouTube Podcasts",
                    description = resolvedDescription.ifBlank {
                        "Saved YouTube podcast. Downloaded as an audio file with embedded synchronized captions."
                    },
                    publisher = "YouTube",
                    artworkAsset = resolvedArtwork,
                    isSubscribed = true,
                    isRecommended = false,
                    isPopular = false,
                    addedAt = now,
                    feedUrl = metadata.canonicalUrl,
                    sourceType = MediaSourceType.YouTubeReference.key,
                    youtubeVideoId = metadata.videoId,
                    preferredCaptionLanguage = resolvedLang,
                    contentType = MediaContentType.Podcast.key,
                    importSource = MediaImportSource.YouTube.key,
                    importedAt = now,
                ),
            ),
        )
        val existingEp = dao.getEpisodeById(episodeId)
        val resolvedDuration = metadata.durationMs.takeIf { it > 0L } ?: (existingEp?.durationMs ?: 0L)
        val resolvedLinked = cleanedLinkedAudio ?: existingEp?.linkedAudioUri
        val resolvedDownloaded = isDownloadedFile || (resolvedLinked?.startsWith("file://") == true) || (existingEp?.isDownloaded == true)
        val resolvedSize = downloadedFileSizeBytes.takeIf { it > 0L } ?: (existingEp?.fileSizeBytes ?: 0L)
        dao.upsertEpisode(
            EpisodeEntity(
                id = episodeId,
                podcastId = podcastId,
                podcastTitle = metadata.channelName,
                hostId = hostId,
                hostName = metadata.channelName,
                categoryId = categoryId,
                categoryName = "YouTube Podcasts",
                episodeNumber = 1,
                title = resolvedTitle,
                summary = resolvedDescription.ifBlank {
                    "YouTube audio (${metadata.canonicalUrl})."
                },
                artworkAsset = resolvedArtwork ?: existingEp?.artworkAsset,
                audioAsset = resolvedLinked?.substringAfterLast('/') ?: (existingEp?.audioAsset ?: ""),
                durationMs = resolvedDuration,
                playbackPositionMs = existingEp?.playbackPositionMs ?: 0L,
                isPlayed = existingEp?.isPlayed ?: false,
                isSaved = existingEp?.isSaved ?: true,
                isDownloaded = resolvedDownloaded,
                fileSizeBytes = resolvedSize.coerceAtLeast(0L),
                playCount = existingEp?.playCount ?: 0,
                publishedAt = now,
                lastPlayedAt = existingEp?.lastPlayedAt,
                transcriptLrc = lrcMirror ?: existingEp?.transcriptLrc,
                sourceType = MediaSourceType.YouTubeReference.key,
                sourceUri = metadata.canonicalUrl,
                youtubeUrl = metadata.canonicalUrl,
                linkedAudioUri = resolvedLinked,
                containerFormat = audioContainerFormat?.takeIf { it.isNotBlank() } ?: existingEp?.containerFormat ?: when {
                    resolvedDownloaded -> "Downloaded Audio"
                    resolvedLinked != null -> "Linked Audio"
                    else -> "YouTube Audio"
                },
                audioCodec = audioCodecLabel?.takeIf { it.isNotBlank() } ?: existingEp?.audioCodec ?: when {
                    resolvedDownloaded && lrcMirror != null -> "Embedded Synced Captions"
                    resolvedLinked != null -> "Synced Captions"
                    else -> "Streamed Audio"
                },
                bitrateKbps = bitrateKbps.takeIf { it > 0 } ?: (existingEp?.bitrateKbps ?: 0),
                transcriptLanguage = audioLanguageCode?.takeIf { it.isNotBlank() } ?: resolvedLang,
                normalizedTitle = UnicodeSortUtil.normalizeKey(resolvedTitle),
                normalizedArtist = UnicodeSortUtil.normalizeKey(metadata.channelName),
                normalizedAlbum = UnicodeSortUtil.normalizeKey(metadata.channelName),
                contentType = MediaContentType.Podcast.key,
                importSource = MediaImportSource.YouTube.key,
                importedAt = now,
            ),
        )
        if (extractedTranscript != null && lrcMirror != null) {
            saveTranscript(
                episodeId = episodeId,
                rawContent = lrcMirror,
                fileName = "${metadata.videoId}_${resolvedLang}.lrc",
                language = resolvedLang,
                source = TranscriptSource.AuthorizedImport,
            )
        }
        notifyLibraryMutated()
        return podcastId to episodeId
    }

    override suspend fun deleteImportedPodcast(podcastId: String) {
        dao.deleteImportedPodcastCascade(podcastId)
        notifyLibraryMutated()
    }

    override suspend fun getLiveCaptions(episodeId: String): List<LiveCaptionSegment> =
        dao.getLiveCaptionsForEpisode(episodeId).map { row ->
            LiveCaptionSegment(
                id = row.id,
                sessionId = row.sessionId,
                startMs = row.startMs,
                endMs = row.endMs,
                text = row.text,
                language = row.language,
                status = LiveCaptionStatus.FINAL,
                timingPrecision = if (row.isEstimatedTiming) CaptionTimingPrecision.ESTIMATED else CaptionTimingPrecision.EXACT,
            )
        }

    override suspend fun saveLiveCaptions(episodeId: String, segments: List<LiveCaptionSegment>) {
        if (segments.isEmpty()) return
        val now = System.currentTimeMillis()
        val entities = segments
            .filter { it.status == LiveCaptionStatus.FINAL && it.text.isNotBlank() }
            .map { seg ->
                LiveCaptionEntity(
                    id = seg.id,
                    episodeId = episodeId,
                    sessionId = seg.sessionId,
                    startMs = seg.startMs,
                    endMs = seg.endMs,
                    text = seg.text,
                    language = seg.language,
                    isEstimatedTiming = seg.timingPrecision == CaptionTimingPrecision.ESTIMATED,
                    createdAt = now,
                )
            }
        if (entities.isNotEmpty()) {
            dao.upsertLiveCaptions(entities)
        }
    }

    override suspend fun clearLiveCaptions(episodeId: String) {
        dao.deleteLiveCaptionsForEpisode(episodeId)
    }

    override fun observeTranscriptDocuments(episodeId: String): Flow<List<TranscriptDocument>> =
        dao.observeTranscriptsForEpisode(episodeId).map { entities ->
            entities.map { entity ->
                TranscriptParser.parse(
                    episodeId = entity.episodeId,
                    content = entity.rawContent,
                    fileName = "transcript.${entity.format}",
                    language = entity.language,
                    source = TranscriptSource.fromKey(entity.source),
                )
            }
        }

    override suspend fun getTranscriptDocuments(episodeId: String): List<TranscriptDocument> {
        val entities = dao.getTranscriptsForEpisode(episodeId)
        return entities.map { entity ->
            val cueRows = dao.getCuesForTranscript(entity.id)
            val cues = if (cueRows.isNotEmpty()) {
                cueRows.map {
                    TranscriptCue(
                        id = it.id,
                        startMs = it.startMs,
                        endMs = it.endMs,
                        text = it.text,
                        speaker = it.speaker,
                    )
                }
            } else {
                TranscriptParser.parse(
                    episodeId = entity.episodeId,
                    content = entity.rawContent,
                    fileName = "transcript.${entity.format}",
                    language = entity.language,
                    source = TranscriptSource.fromKey(entity.source),
                ).cues
            }
            TranscriptDocument(
                episodeId = entity.episodeId,
                language = entity.language,
                format = TranscriptFormat.fromKey(entity.format),
                source = TranscriptSource.fromKey(entity.source),
                cues = cues,
            )
        }
    }

    override suspend fun saveTranscript(
        episodeId: String,
        rawContent: String,
        fileName: String?,
        language: String,
        source: TranscriptSource,
    ): TranscriptDocument {
        val doc = TranscriptParser.parse(
            episodeId = episodeId,
            content = rawContent,
            fileName = fileName,
            language = language,
            source = source,
        )
        val transcriptId = "${episodeId}_${doc.language}"
        val now = System.currentTimeMillis()
        val entity = TranscriptEntity(
            id = transcriptId,
            episodeId = episodeId,
            language = doc.language,
            format = doc.format.key,
            source = doc.source.key,
            rawContent = rawContent,
            updatedAt = now,
        )
        val cueEntities = doc.cues.mapIndexed { idx, cue ->
            TranscriptCueEntity(
                transcriptId = transcriptId,
                episodeId = episodeId,
                cueIndex = idx,
                startMs = cue.startMs,
                endMs = cue.endMs,
                text = cue.text,
                speaker = cue.speaker,
            )
        }
        dao.saveTranscriptWithCues(entity, cueEntities)
        dao.updateEpisodeTranscript(episodeId, TranscriptParser.toLrcString(doc.cues), doc.language)
        return doc
    }

    override fun observeBookmarks(episodeId: String): Flow<List<Bookmark>> =
        dao.observeBookmarks(episodeId).map { list ->
            list.map {
                Bookmark(
                    id = it.id,
                    episodeId = it.episodeId,
                    positionMs = it.positionMs,
                    label = it.label,
                    createdAt = it.createdAt,
                )
            }
        }

    override suspend fun addBookmark(episodeId: String, positionMs: Long, label: String): Long =
        dao.insertBookmark(
            BookmarkEntity(
                episodeId = episodeId,
                positionMs = positionMs.coerceAtLeast(0L),
                label = label.trim().ifBlank { "Bookmark" },
                createdAt = System.currentTimeMillis(),
            ),
        )

    override suspend fun deleteBookmark(bookmarkId: Long) {
        dao.deleteBookmark(bookmarkId)
    }

    override suspend fun getRawPcmConfig(sourceUri: String): RawPcmConfig? =
        dao.getRawPcmConfig(sourceUri)?.let {
            RawPcmConfig(
                sourceUri = it.sourceUri,
                sampleRateHz = it.sampleRateHz,
                bitDepth = it.bitDepth,
                channelCount = it.channelCount,
                bigEndian = it.bigEndian,
                signedPcm = it.signedPcm,
            )
        }

    override suspend fun saveRawPcmConfig(config: RawPcmConfig) {
        dao.upsertRawPcmConfig(
            RawPcmConfigEntity(
                sourceUri = config.sourceUri,
                sampleRateHz = config.sampleRateHz,
                bitDepth = config.bitDepth,
                channelCount = config.channelCount,
                bigEndian = config.bigEndian,
                signedPcm = config.signedPcm,
            ),
        )
    }

    override suspend fun updateEpisodeAfterTagEdit(
        episodeId: String,
        tags: EditableAudioTags,
        updatedArtworkPath: String?,
    ) {
        val current = dao.getEpisodeById(episodeId) ?: return
        val nextTitle = tags.title.ifBlank { current.title }
        val nextArtist = tags.artist.ifBlank { current.hostName }
        val nextAlbum = tags.album.ifBlank { current.podcastTitle }
        val nextArtwork = when {
            tags.removeArtwork -> null
            updatedArtworkPath != null -> updatedArtworkPath
            else -> current.artworkAsset
        }
        val updated = current.copy(
            title = nextTitle,
            hostName = nextArtist,
            podcastTitle = nextAlbum,
            albumArtist = tags.albumArtist.ifBlank { current.albumArtist },
            categoryName = tags.genre.ifBlank { current.categoryName },
            year = tags.year.take(4).toIntOrNull() ?: current.year,
            trackNumber = tags.trackNumber.substringBefore('/').toIntOrNull() ?: current.trackNumber,
            discNumber = tags.discNumber.substringBefore('/').toIntOrNull() ?: current.discNumber,
            composer = tags.composer.ifBlank { current.composer },
            comment = tags.comment.ifBlank { current.comment },
            transcriptLrc = tags.lyrics.ifBlank { current.transcriptLrc },
            normalizedTitle = UnicodeSortUtil.normalizeKey(nextTitle),
            normalizedArtist = UnicodeSortUtil.normalizeKey(nextArtist),
            normalizedAlbum = UnicodeSortUtil.normalizeKey(nextAlbum),
            artworkAsset = nextArtwork,
        )
        dao.upsertEpisode(updated)
        if (current.artworkAsset != nextArtwork) {
            com.podcasty.app.ui.components.invalidateArtworkCache(current.artworkAsset)
            com.podcasty.app.ui.components.invalidateArtworkCache(nextArtwork)
            dao.getPodcastById(current.podcastId)?.let { album ->
                if (album.artworkAsset == current.artworkAsset || album.artworkAsset == null) {
                    dao.insertPodcasts(listOf(album.copy(artworkAsset = nextArtwork)))
                }
            }
        }
        notifyLibraryMutated()
    }

    // --- Strict Podcasts vs Music Albums Queries ---

    override fun observePodcastShows(): Flow<List<Podcast>> = dao.observePodcastShows()
        .map { list -> list.map { it.toDomain() } }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    override fun observePodcastById(podcastId: String): Flow<Podcast?> = dao.observePodcastById(podcastId)
        .map { it?.toDomain() }
        .distinctUntilChanged()

    override fun observePodcastEpisodes(limit: Int): Flow<List<Episode>> = dao.observePodcastEpisodes(limit)
        .map { list -> list.map { it.toDomain() } }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    override fun observePodcastEpisodeCount(): Flow<Int> = dao.observePodcastEpisodeCount()
        .distinctUntilChanged()

    override fun observeEpisodesForPodcast(podcastId: String): Flow<List<Episode>> =
        dao.observeEpisodesForPodcast(podcastId)
            .map { list -> list.map { it.toDomain() } }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    override fun observeRecentMusicEpisodes(limit: Int): Flow<List<Episode>> =
        dao.observeRecentMusicEpisodes(limit)
            .map { list -> list.map { it.toDomain() } }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    override fun observeFavouriteMusicEpisodes(limit: Int): Flow<List<Episode>> =
        dao.observeFavouriteMusicEpisodes(limit)
            .map { list -> list.map { it.toDomain() } }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    override fun observeRecentlyPlayedEpisodes(limit: Int): Flow<List<Episode>> =
        dao.observeRecentlyPlayedEpisodes(limit)
            .map { list -> list.map { it.toDomain() } }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    override fun observeLocalAlbumsSorted(
        query: String,
        sortOption: AlbumSortOption,
        sortOrder: SortOrder,
        limit: Int,
    ): Flow<List<AlbumSummary>> {
        val sortKey = when (sortOption) {
            AlbumSortOption.Title -> "title"
            AlbumSortOption.Artist -> "artist"
            AlbumSortOption.TrackCount -> "tracks"
        }
        val asc = sortOrder == SortOrder.Ascending
        return libraryRevision.debounce(150L)
            .mapLatest { dao.getLocalAlbumsSorted(query.trim(), sortKey, asc, limit) }
            .map { rows ->
                rows.map { row ->
                    AlbumSummary(
                        albumId = row.albumId,
                        albumTitle = row.albumTitle,
                        artistName = row.artistName,
                        trackCount = row.trackCount,
                        totalDurationMs = row.totalDurationMs,
                        artworkAsset = row.artworkAsset,
                    )
                }
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
    }

    override fun observeMusicAlbumById(albumId: String): Flow<AlbumSummary?> =
        dao.observeMusicAlbumById(albumId).map { row ->
            row?.let {
                AlbumSummary(
                    albumId = it.albumId,
                    albumTitle = it.albumTitle,
                    artistName = it.artistName,
                    trackCount = it.trackCount,
                    totalDurationMs = it.totalDurationMs,
                    artworkAsset = it.artworkAsset,
                )
            }
        }.distinctUntilChanged()

    override fun observeMusicAlbumCount(): Flow<Int> = dao.observeMusicAlbumCount()
        .distinctUntilChanged()

    // --- Offline Local Music Queries & Management ---

    override fun observeLocalMusicCount(): Flow<Int> = dao.observeLocalMusicCount()

    override fun observeLinearFolderSummaries(): Flow<List<LinearFolderSummary>> = combine(
        libraryRevision.debounce(300L).mapLatest { dao.getLinearFolders() },
        dao.observeMusicFolders(),
    ) { rows, folders ->
        val folderByUri = folders.associateBy { it.treeUri }
        rows.map { row ->
            val parentFolder = folderByUri[row.folderUri]
            val rootName = parentFolder?.displayName ?: when (row.folderUri) {
                StorageProviderType.MediaStore.key -> "Device Audio"
                else -> "Music"
            }
            val storageType = parentFolder?.let { StorageProviderType.fromKey(it.storageType) }
                ?: if (row.folderUri == StorageProviderType.MediaStore.key) StorageProviderType.MediaStore else StorageProviderType.Internal
            val rel = row.relativeFolderPath.trim('/')
            val folderName = if (rel.isEmpty()) rootName else rel.substringAfterLast('/')
            val displayPath = if (rel.isEmpty()) rootName else "$rootName/$rel"
            val key = "${row.folderUri}::$rel"
            LinearFolderSummary(
                folderKey = key,
                treeUri = row.folderUri,
                relativePath = rel,
                folderName = folderName,
                displayPath = displayPath,
                trackCount = row.trackCount,
                totalDurationMs = row.totalDurationMs,
                latestAddedAt = row.latestAddedAt,
                storageType = storageType,
            )
        }
    }.flowOn(Dispatchers.Default)

    override fun observeLocalAlbums(query: String, limit: Int): Flow<List<AlbumSummary>> =
        libraryRevision.debounce(150L).mapLatest { dao.getLocalAlbums(query.trim(), limit) }.map { rows ->
            rows.map { row ->
                AlbumSummary(
                    albumId = row.albumId,
                    albumTitle = row.albumTitle,
                    artistName = row.artistName,
                    trackCount = row.trackCount,
                    totalDurationMs = row.totalDurationMs,
                    artworkAsset = row.artworkAsset,
                )
            }
        }.flowOn(Dispatchers.Default)

    override fun observeLocalArtists(query: String, limit: Int): Flow<List<ArtistSummary>> =
        libraryRevision.debounce(150L).mapLatest { dao.getLocalArtists(query.trim(), limit) }.map { rows ->
            rows.map { row ->
                ArtistSummary(
                    artistId = row.artistId,
                    artistName = row.artistName,
                    albumCount = row.albumCount,
                    trackCount = row.trackCount,
                    artworkAsset = row.artworkAsset,
                )
            }
        }.flowOn(Dispatchers.Default)

    override fun observeSmartMusicCategory(category: String): Flow<List<Episode>> =
        dao.observeSmartMusicCategory(category).map { rows -> rows.map { it.toDomain() } }.flowOn(Dispatchers.Default)

    override fun observeLocalSongsPaged(
        query: String,
        sortOption: SongSortOption,
        sortOrder: SortOrder,
        limit: Int,
        minDurationMs: Long,
        maxDurationMs: Long,
        format: String,
    ): Flow<List<Episode>> {
        val trimmed = query.trim()
        val asc = sortOrder == SortOrder.Ascending
        val rawFlow = if (trimmed.isEmpty() && minDurationMs == 0L && maxDurationMs == Long.MAX_VALUE && format.isBlank()) {
            when {
                sortOption == SongSortOption.Title && asc -> dao.observeLocalSongsByTitleAsc(limit)
                sortOption == SongSortOption.Title && !asc -> dao.observeLocalSongsByTitleDesc(limit)
                sortOption == SongSortOption.Artist && asc -> dao.observeLocalSongsByArtistAsc(limit)
                sortOption == SongSortOption.Artist && !asc -> dao.observeLocalSongsByArtistDesc(limit)
                sortOption == SongSortOption.Album && asc -> dao.observeLocalSongsByAlbumAsc(limit)
                sortOption == SongSortOption.Album && !asc -> dao.observeLocalSongsByAlbumDesc(limit)
                sortOption == SongSortOption.DateAdded && asc -> dao.observeLocalSongsByDateAsc(limit)
                sortOption == SongSortOption.DateAdded && !asc -> dao.observeLocalSongsByDateDesc(limit)
                sortOption == SongSortOption.Duration && asc -> dao.observeLocalSongsByDurationAsc(limit)
                sortOption == SongSortOption.Duration && !asc -> dao.observeLocalSongsByDurationDesc(limit)
                else -> dao.observeLocalSongsPaged(
                    query = "",
                    sortBy = sortOption.sqlKey(),
                    asc = asc,
                    limit = limit,
                )
            }
        } else {
            dao.observeLocalSongsPaged(
                query = trimmed,
                sortBy = sortOption.sqlKey(),
                asc = asc,
                limit = limit,
                minDurationMs = minDurationMs.coerceAtLeast(0L),
                maxDurationMs = maxDurationMs.coerceAtLeast(minDurationMs),
                format = format.lowercase(java.util.Locale.ROOT),
            )
        }
        return rawFlow.map { list -> list.map { it.toDomain() } }.flowOn(Dispatchers.Default)
    }

    override suspend fun getLocalSongIdsSorted(
        query: String,
        sortOption: SongSortOption,
        sortOrder: SortOrder,
        limit: Int,
    ): List<String> = dao.getLocalSongIdsSorted(
        query = query.trim(),
        sortBy = sortOption.sqlKey(),
        asc = sortOrder == SortOrder.Ascending,
        limit = limit,
    )

    override fun observeSongsForAlbum(albumId: String): Flow<List<Episode>> =
        dao.observeSongsForAlbum(albumId).map { list -> list.map { it.toDomain() } }.flowOn(Dispatchers.Default)

    override fun observeSongsForArtist(artistId: String): Flow<List<Episode>> =
        dao.observeSongsForArtist(artistId).map { list -> list.map { it.toDomain() } }.flowOn(Dispatchers.Default)

    override fun observeDirectFolderSongs(
        folderUri: String,
        relativeFolderPath: String,
        query: String,
        limit: Int,
    ): Flow<List<Episode>> = dao.observeDirectFolderSongs(
        folderUri = folderUri,
        relativeFolderPath = relativeFolderPath.trim('/'),
        query = query.trim(),
        limit = limit,
    ).map { list -> list.map { it.toDomain() } }.flowOn(Dispatchers.Default)

    override suspend fun getRecursiveFolderSongIds(
        folderUri: String,
        relativeFolderPath: String,
        limit: Int,
    ): List<String> = dao.getRecursiveFolderSongIds(folderUri, relativeFolderPath.trim('/'), limit)

    override suspend fun getDirectFolderSongIds(
        folderUri: String,
        relativeFolderPath: String,
        limit: Int,
    ): List<String> = dao.getDirectFolderSongIds(folderUri, relativeFolderPath.trim('/'), limit)

    override fun observeExcludedFolders(): Flow<List<ExcludedFolder>> =
        dao.observeExcludedFolders().map { rows ->
            rows.map { ExcludedFolder(it.folderKey, it.displayName, it.createdAt) }
        }

    override suspend fun excludeFolder(
        folderKey: String,
        displayName: String,
        treeUri: String,
        relativePath: String,
    ) {
        dao.upsertExcludedFolder(ExcludedFolderEntity(folderKey, displayName, System.currentTimeMillis()))
        val ids = dao.getEpisodeIdsInFolderSubtree(treeUri, relativePath.trim('/'))
        if (ids.isNotEmpty()) {
            dao.deleteEpisodesCascade(ids)
            notifyLibraryMutated()
        }
    }

    override suspend fun restoreExcludedFolder(folderKey: String) {
        dao.deleteExcludedFolder(folderKey)
    }

    override suspend fun updateLocalEpisodeFileLocation(
        episodeId: String,
        sourceUri: String,
        audioAsset: String,
        folderUri: String?,
        relativeFolderPath: String,
        title: String,
        lastModifiedMs: Long,
    ) {
        dao.updateEpisodeFileLocation(
            episodeId = episodeId,
            sourceUri = sourceUri,
            audioAsset = audioAsset,
            folderUri = folderUri,
            relativeFolderPath = relativeFolderPath.trim('/'),
            title = title,
            normalizedTitle = UnicodeSortUtil.normalizeKey(title),
            lastModifiedMs = lastModifiedMs,
        )
        dao.getEpisodeById(episodeId)?.artworkAsset?.let {
            com.podcasty.app.ui.components.invalidateArtworkCache(it)
        }
        notifyLibraryMutated()
    }

    override suspend fun deleteEpisodesCascade(episodeIds: List<String>) {
        dao.deleteEpisodesCascade(episodeIds)
        notifyLibraryMutated()
    }

    // --- Multi-Queue Implementation ---

    override fun observeMusicQueues(): Flow<List<MusicQueue>> = combine(
        dao.observeMusicQueues(),
        dao.observeAllMusicQueueItems(),
    ) { queueEntities, allItems ->
        val itemsByQueue = allItems.groupBy { it.queueId }
        queueEntities.map { q ->
            MusicQueue(
                id = q.id,
                name = q.name,
                isActive = q.isActive,
                shuffle = q.shuffle,
                repeatMode = runCatching { RepeatMode.valueOf(q.repeatMode) }.getOrDefault(RepeatMode.Off),
                currentEpisodeId = q.currentEpisodeId,
                currentPositionMs = q.currentPositionMs,
                episodeIds = itemsByQueue[q.id].orEmpty().map { it.episodeId },
                createdAt = q.createdAt,
                updatedAt = q.updatedAt,
            )
        }
    }.flowOn(Dispatchers.Default)

    override suspend fun getMusicQueues(): List<MusicQueue> {
        val entities = dao.getMusicQueues()
        return entities.map { q ->
            val items = dao.getQueueEpisodeIds(q.id)
            MusicQueue(
                id = q.id,
                name = q.name,
                isActive = q.isActive,
                shuffle = q.shuffle,
                repeatMode = runCatching { RepeatMode.valueOf(q.repeatMode) }.getOrDefault(RepeatMode.Off),
                currentEpisodeId = q.currentEpisodeId,
                currentPositionMs = q.currentPositionMs,
                episodeIds = items,
                createdAt = q.createdAt,
                updatedAt = q.updatedAt,
            )
        }
    }

    override suspend fun ensureDefaultMusicQueue(): MusicQueue {
        val existing = dao.getMusicQueues()
        val active = existing.firstOrNull { it.isActive } ?: existing.firstOrNull()
        if (active != null) {
            if (!active.isActive) dao.setActiveMusicQueueFlag(active.id)
            val ids = dao.getQueueEpisodeIds(active.id)
            return MusicQueue(
                id = active.id,
                name = active.name,
                isActive = true,
                shuffle = active.shuffle,
                repeatMode = runCatching { RepeatMode.valueOf(active.repeatMode) }.getOrDefault(RepeatMode.Off),
                currentEpisodeId = active.currentEpisodeId,
                currentPositionMs = active.currentPositionMs,
                episodeIds = ids,
                createdAt = active.createdAt,
                updatedAt = active.updatedAt,
            )
        }
        val now = System.currentTimeMillis()
        val created = MusicQueueEntity(
            id = MusicQueue.DefaultQueueId,
            name = MusicQueue.DefaultQueueName,
            isActive = true,
            shuffle = false,
            repeatMode = RepeatMode.Off.name,
            createdAt = now,
            updatedAt = now,
        )
        dao.upsertMusicQueue(created)
        return MusicQueue(
            id = created.id,
            name = created.name,
            isActive = true,
            createdAt = now,
            updatedAt = now,
        )
    }

    override suspend fun createMusicQueue(
        name: String,
        initialEpisodeIds: List<String>,
        activate: Boolean,
    ): String {
        val cleanName = name.trim().ifBlank { "Queue ${(dao.getMusicQueues().size + 1)}" }
        val now = System.currentTimeMillis()
        val id = "queue_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val distinctIds = initialEpisodeIds.distinct()
        dao.upsertMusicQueue(
            MusicQueueEntity(
                id = id,
                name = cleanName,
                isActive = activate,
                currentEpisodeId = distinctIds.firstOrNull(),
                createdAt = now,
                updatedAt = now,
            ),
        )
        if (activate) {
            dao.setActiveMusicQueueFlag(id)
        }
        if (distinctIds.isNotEmpty()) {
            dao.replaceMusicQueueItems(id, distinctIds, now)
        }
        return id
    }

    override suspend fun renameMusicQueue(queueId: String, newName: String) {
        val clean = newName.trim()
        if (clean.isEmpty()) return
        dao.renameMusicQueue(queueId, clean, System.currentTimeMillis())
    }

    override suspend fun deleteMusicQueue(queueId: String) {
        val all = dao.getMusicQueues()
        if (all.size <= 1) {
            dao.deleteAllItemsFromMusicQueue(queueId)
            return
        }
        val deleting = all.firstOrNull { it.id == queueId }
        dao.deleteAllItemsFromMusicQueue(queueId)
        dao.deleteMusicQueue(queueId)
        if (deleting?.isActive == true) {
            val next = all.firstOrNull { it.id != queueId }
            if (next != null) dao.setActiveMusicQueueFlag(next.id)
        }
    }

    override suspend fun setActiveMusicQueue(queueId: String) {
        dao.setActiveMusicQueueFlag(queueId)
    }

    override suspend fun replaceMusicQueueItems(queueId: String, orderedEpisodeIds: List<String>) {
        dao.replaceMusicQueueItems(queueId, orderedEpisodeIds, System.currentTimeMillis())
    }

    override suspend fun addEpisodesToMusicQueue(
        queueId: String,
        episodeIds: List<String>,
        insertNextAfterEpisodeId: String?,
    ) {
        if (episodeIds.isEmpty()) return
        val existing = dao.getQueueEpisodeIds(queueId).toMutableList()
        val incoming = episodeIds.distinct()
        if (insertNextAfterEpisodeId != null) {
            val cleanedIncoming = incoming.filter { it != insertNextAfterEpisodeId }
            existing.removeAll(cleanedIncoming.toSet())
            val anchorIndex = existing.indexOf(insertNextAfterEpisodeId)
            val insertAt = if (anchorIndex >= 0) anchorIndex + 1 else 0
            existing.addAll(insertAt.coerceIn(0, existing.size), cleanedIncoming)
        } else {
            val existingSet = existing.toHashSet()
            incoming.forEach { if (it !in existingSet) existing += it }
        }
        dao.replaceMusicQueueItems(queueId, existing, System.currentTimeMillis())
    }

    override suspend fun removeEpisodesFromMusicQueue(queueId: String, episodeIds: List<String>) {
        if (episodeIds.isEmpty()) return
        val remaining = dao.getQueueEpisodeIds(queueId).filterNot { it in episodeIds }
        dao.replaceMusicQueueItems(queueId, remaining, System.currentTimeMillis())
    }

    override suspend fun updateMusicQueuePlaybackState(
        queueId: String,
        shuffle: Boolean,
        repeatMode: RepeatMode,
        currentEpisodeId: String?,
        currentPositionMs: Long,
    ) {
        dao.updateMusicQueuePlaybackState(
            queueId = queueId,
            shuffle = shuffle,
            repeatMode = repeatMode.name,
            currentEpisodeId = currentEpisodeId,
            currentPositionMs = currentPositionMs.coerceAtLeast(0L),
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun SongSortOption.sqlKey(): String = when (this) {
        SongSortOption.Title -> "title"
        SongSortOption.Artist -> "artist"
        SongSortOption.Album -> "album"
        SongSortOption.Duration -> "duration"
        SongSortOption.DateAdded -> "date"
    }

    private fun shortHash(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}

private fun HostEntity.toDomain() = Host(
    id = id,
    name = name,
    bio = bio,
    artworkAsset = artworkAsset,
    createdAt = createdAt,
)

private fun PodcastEntity.toDomain() = Podcast(
    id = id,
    title = title,
    hostId = hostId,
    hostName = hostName,
    categoryId = categoryId,
    categoryName = categoryName,
    description = description,
    publisher = publisher,
    artworkAsset = artworkAsset,
    isSubscribed = isSubscribed,
    isRecommended = isRecommended,
    isPopular = isPopular,
    addedAt = addedAt,
    feedUrl = feedUrl,
    sourceType = MediaSourceType.fromKey(sourceType),
    youtubeVideoId = youtubeVideoId,
    preferredCaptionLanguage = preferredCaptionLanguage,
    contentType = MediaContentType.fromKey(contentType),
    importSource = importSource,
    importedAt = importedAt,
)

private fun EpisodeSummaryTuple.toDomain() = Episode(
    id = id,
    podcastId = podcastId,
    podcastTitle = podcastTitle,
    hostId = hostId,
    hostName = hostName,
    categoryId = categoryId,
    categoryName = categoryName,
    episodeNumber = episodeNumber,
    title = title,
    summary = summary,
    artworkAsset = artworkAsset,
    audioAsset = audioAsset,
    durationMs = durationMs,
    playbackPositionMs = playbackPositionMs,
    isPlayed = isPlayed,
    isSaved = isSaved,
    isDownloaded = isDownloaded,
    fileSizeBytes = fileSizeBytes,
    playCount = playCount,
    publishedAt = publishedAt,
    lastPlayedAt = lastPlayedAt,
    transcriptLrc = null,
    sourceType = MediaSourceType.fromKey(sourceType),
    sourceUri = sourceUri,
    youtubeUrl = youtubeUrl,
    linkedAudioUri = linkedAudioUri,
    folderUri = folderUri,
    containerFormat = containerFormat,
    audioCodec = audioCodec,
    sampleRateHz = sampleRateHz,
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
    transcriptLanguage = transcriptLanguage,
    contentType = MediaContentType.fromKey(contentType),
    importSource = importSource,
    importedAt = importedAt,
)

private fun EpisodeEntity.toDomain() = Episode(
    id = id,
    podcastId = podcastId,
    podcastTitle = podcastTitle,
    hostId = hostId,
    hostName = hostName,
    categoryId = categoryId,
    categoryName = categoryName,
    episodeNumber = episodeNumber,
    title = title,
    summary = summary,
    artworkAsset = artworkAsset,
    audioAsset = audioAsset,
    durationMs = durationMs,
    playbackPositionMs = playbackPositionMs,
    isPlayed = isPlayed,
    isSaved = isSaved,
    isDownloaded = isDownloaded,
    fileSizeBytes = fileSizeBytes,
    playCount = playCount,
    publishedAt = publishedAt,
    lastPlayedAt = lastPlayedAt,
    transcriptLrc = transcriptLrc,
    sourceType = MediaSourceType.fromKey(sourceType),
    sourceUri = sourceUri,
    youtubeUrl = youtubeUrl,
    linkedAudioUri = linkedAudioUri,
    folderUri = folderUri,
    containerFormat = containerFormat,
    audioCodec = audioCodec,
    sampleRateHz = sampleRateHz,
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
    transcriptLanguage = transcriptLanguage,
    contentType = MediaContentType.fromKey(contentType),
    importSource = importSource,
    importedAt = importedAt,
)

private fun ListeningHistoryEntity.toDomain() = ListeningHistoryEntry(
    id = id,
    episodeId = episodeId,
    episodeTitle = episodeTitle,
    podcastId = podcastId,
    podcastTitle = podcastTitle,
    hostId = hostId,
    hostName = hostName,
    categoryName = categoryName,
    artworkAsset = artworkAsset,
    listenedSeconds = listenedSeconds,
    completed = completed,
    skipped = skipped,
    playedAt = playedAt,
)

private fun MusicFolderEntity.toDomain() = MusicFolder(
    treeUri = treeUri,
    displayName = displayName,
    storageType = StorageProviderType.fromKey(storageType),
    isDefault = isDefault,
    includeSubfolders = includeSubfolders,
    lastScannedAt = lastScannedAt,
    trackCount = trackCount,
    isAvailable = isAvailable,
    isEnabled = isEnabled,
    initialIndexingCompleted = initialIndexingCompleted,
    lastSyncAt = lastSyncAt,
    lastMediaStoreGeneration = lastMediaStoreGeneration,
)
