/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        HostEntity::class,
        CategoryEntity::class,
        PodcastEntity::class,
        EpisodeEntity::class,
        CollectionEntity::class,
        CollectionEpisodeEntity::class,
        ListeningHistoryEntity::class,
        MusicFolderEntity::class,
        TranscriptEntity::class,
        TranscriptCueEntity::class,
        BookmarkEntity::class,
        RawPcmConfigEntity::class,
        LiveCaptionEntity::class,
        ExcludedFolderEntity::class,
        MusicQueueEntity::class,
        MusicQueueItemEntity::class,
        ScanSessionEntity::class,
        EqualizerPresetEntity::class,
    ],
    version = 9,
    exportSchema = false,
)
abstract class PodcastyDatabase : RoomDatabase() {
    abstract fun dao(): PodcastyDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE episodes ADD COLUMN sourceType TEXT NOT NULL DEFAULT 'demo'")
                db.execSQL("ALTER TABLE episodes ADD COLUMN sourceUri TEXT")
                db.execSQL("ALTER TABLE episodes ADD COLUMN youtubeUrl TEXT")
                db.execSQL("ALTER TABLE episodes ADD COLUMN linkedAudioUri TEXT")
                db.execSQL("ALTER TABLE episodes ADD COLUMN folderUri TEXT")
                db.execSQL("ALTER TABLE episodes ADD COLUMN containerFormat TEXT")
                db.execSQL("ALTER TABLE episodes ADD COLUMN audioCodec TEXT")
                db.execSQL("ALTER TABLE episodes ADD COLUMN sampleRateHz INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE episodes ADD COLUMN bitDepth INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE episodes ADD COLUMN bitrateKbps INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE episodes ADD COLUMN channels INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE episodes ADD COLUMN isHighRes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE episodes ADD COLUMN year INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE episodes ADD COLUMN trackNumber INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE episodes ADD COLUMN discNumber INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE episodes ADD COLUMN albumArtist TEXT")
                db.execSQL("ALTER TABLE episodes ADD COLUMN composer TEXT")
                db.execSQL("ALTER TABLE episodes ADD COLUMN comment TEXT")
                db.execSQL("ALTER TABLE episodes ADD COLUMN transcriptLanguage TEXT NOT NULL DEFAULT 'en'")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `music_folders` (
                        `treeUri` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `storageType` TEXT NOT NULL,
                        `isDefault` INTEGER NOT NULL,
                        `includeSubfolders` INTEGER NOT NULL,
                        `lastScannedAt` INTEGER NOT NULL,
                        `trackCount` INTEGER NOT NULL,
                        `isAvailable` INTEGER NOT NULL,
                        PRIMARY KEY(`treeUri`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `transcripts` (
                        `id` TEXT NOT NULL,
                        `episodeId` TEXT NOT NULL,
                        `language` TEXT NOT NULL,
                        `format` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `rawContent` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_transcripts_episodeId_language` ON `transcripts` (`episodeId`, `language`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `transcript_cues` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `transcriptId` TEXT NOT NULL,
                        `episodeId` TEXT NOT NULL,
                        `cueIndex` INTEGER NOT NULL,
                        `startMs` INTEGER NOT NULL,
                        `endMs` INTEGER NOT NULL,
                        `text` TEXT NOT NULL,
                        `speaker` TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_transcript_cues_transcriptId_cueIndex` ON `transcript_cues` (`transcriptId`, `cueIndex`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_transcript_cues_episodeId` ON `transcript_cues` (`episodeId`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `bookmarks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `episodeId` TEXT NOT NULL,
                        `positionMs` INTEGER NOT NULL,
                        `label` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_bookmarks_episodeId` ON `bookmarks` (`episodeId`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `raw_pcm_configs` (
                        `sourceUri` TEXT NOT NULL,
                        `sampleRateHz` INTEGER NOT NULL,
                        `bitDepth` INTEGER NOT NULL,
                        `channelCount` INTEGER NOT NULL,
                        `bigEndian` INTEGER NOT NULL,
                        `signedPcm` INTEGER NOT NULL,
                        PRIMARY KEY(`sourceUri`)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE podcasts ADD COLUMN feedUrl TEXT")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN sourceType TEXT NOT NULL DEFAULT 'demo'")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN youtubeVideoId TEXT")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN preferredCaptionLanguage TEXT NOT NULL DEFAULT 'en-US'")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `live_captions` (
                        `id` TEXT NOT NULL,
                        `episodeId` TEXT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `startMs` INTEGER NOT NULL,
                        `endMs` INTEGER NOT NULL,
                        `text` TEXT NOT NULL,
                        `language` TEXT NOT NULL,
                        `isEstimatedTiming` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_live_captions_episodeId` ON `live_captions` (`episodeId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_live_captions_episodeId_startMs` ON `live_captions` (`episodeId`, `startMs`)")
            }
        }

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE episodes ADD COLUMN relativeFolderPath TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE episodes ADD COLUMN lastModifiedMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE episodes ADD COLUMN normalizedTitle TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE episodes ADD COLUMN normalizedArtist TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE episodes ADD COLUMN normalizedAlbum TEXT NOT NULL DEFAULT ''")

                db.execSQL("UPDATE episodes SET normalizedTitle = LOWER(TRIM(title)), normalizedArtist = LOWER(TRIM(hostName)), normalizedAlbum = LOWER(TRIM(podcastTitle))")

                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_sourceType` ON `episodes` (`sourceType`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_folderUri` ON `episodes` (`folderUri`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_folderUri_relativeFolderPath` ON `episodes` (`folderUri`, `relativeFolderPath`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_podcastId` ON `episodes` (`podcastId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_hostId` ON `episodes` (`hostId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_publishedAt` ON `episodes` (`publishedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_lastPlayedAt` ON `episodes` (`lastPlayedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_normalizedTitle` ON `episodes` (`normalizedTitle`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_normalizedArtist` ON `episodes` (`normalizedArtist`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_normalizedAlbum` ON `episodes` (`normalizedAlbum`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `excluded_folders` (
                        `folderKey` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`folderKey`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `music_queues` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `isActive` INTEGER NOT NULL,
                        `shuffle` INTEGER NOT NULL,
                        `repeatMode` TEXT NOT NULL,
                        `currentEpisodeId` TEXT,
                        `currentPositionMs` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `music_queue_items` (
                        `queueId` TEXT NOT NULL,
                        `episodeId` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        PRIMARY KEY(`queueId`, `episodeId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_music_queue_items_queueId_position` ON `music_queue_items` (`queueId`, `position`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_music_queue_items_episodeId` ON `music_queue_items` (`episodeId`)")
            }
        }

        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `scan_sessions` (
                        `scanId` TEXT NOT NULL,
                        `folderUri` TEXT NOT NULL,
                        `startedAt` INTEGER NOT NULL,
                        `completedAt` INTEGER,
                        `status` TEXT NOT NULL,
                        `discoveredCount` INTEGER NOT NULL,
                        `indexedCount` INTEGER NOT NULL,
                        `failedCount` INTEGER NOT NULL,
                        `lastCheckpointPath` TEXT,
                        `errorMessage` TEXT,
                        PRIMARY KEY(`scanId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_scan_sessions_folderUri_startedAt` ON `scan_sessions` (`folderUri`, `startedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_scan_sessions_status` ON `scan_sessions` (`status`)")
            }
        }

        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE music_folders ADD COLUMN isEnabled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE music_folders ADD COLUMN initialIndexingCompleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE music_folders ADD COLUMN lastSyncAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE music_folders ADD COLUMN lastMediaStoreGeneration INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE music_folders SET initialIndexingCompleted = CASE WHEN lastScannedAt > 0 THEN 1 ELSE 0 END, lastSyncAt = lastScannedAt")
            }
        }

        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_sourceType_publishedAt` ON `episodes` (`sourceType`, `publishedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_sourceType_lastPlayedAt` ON `episodes` (`sourceType`, `lastPlayedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_sourceType_normalizedTitle` ON `episodes` (`sourceType`, `normalizedTitle`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_sourceType_normalizedArtist_normalizedTitle` ON `episodes` (`sourceType`, `normalizedArtist`, `normalizedTitle`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_sourceType_normalizedAlbum_normalizedTitle` ON `episodes` (`sourceType`, `normalizedAlbum`, `normalizedTitle`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_sourceType_durationMs` ON `episodes` (`sourceType`, `durationMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_isSaved_publishedAt` ON `episodes` (`isSaved`, `publishedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_isDownloaded_publishedAt` ON `episodes` (`isDownloaded`, `publishedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_playCount` ON `episodes` (`playCount`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_isHighRes_sampleRateHz` ON `episodes` (`isHighRes`, `sampleRateHz`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_playCount_isPlayed_publishedAt` ON `episodes` (`playCount`, `isPlayed`, `publishedAt`)")
            }
        }

        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `equalizer_presets` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `bandLevelsMbCsv` TEXT NOT NULL,
                        `bandFrequenciesHzCsv` TEXT NOT NULL,
                        `preampDb` REAL NOT NULL,
                        `bassBoostStrength` INTEGER NOT NULL,
                        `virtualizerStrength` INTEGER NOT NULL,
                        `balance` REAL NOT NULL,
                        `monoAudio` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Non-destructive persistent content classification for Podcasts vs Music Albums
                db.execSQL("ALTER TABLE podcasts ADD COLUMN contentType TEXT NOT NULL DEFAULT 'MUSIC'")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN importSource TEXT NOT NULL DEFAULT 'local_library'")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN importedAt INTEGER NOT NULL DEFAULT 0")

                db.execSQL("ALTER TABLE episodes ADD COLUMN contentType TEXT NOT NULL DEFAULT 'MUSIC'")
                db.execSQL("ALTER TABLE episodes ADD COLUMN importSource TEXT NOT NULL DEFAULT 'local_library'")
                db.execSQL("ALTER TABLE episodes ADD COLUMN importedAt INTEGER NOT NULL DEFAULT 0")

                // Deterministically backfill podcasts table without deleting or moving any rows
                db.execSQL(
                    """
                    UPDATE podcasts
                    SET contentType = CASE
                            WHEN sourceType = 'local_music' OR id LIKE 'local_album_%' THEN 'MUSIC'
                            ELSE 'PODCAST'
                        END,
                        importSource = CASE
                            WHEN sourceType = 'local_music' OR id LIKE 'local_album_%' THEN 'local_library'
                            WHEN sourceType = 'youtube_ref' OR id LIKE 'yt_show_%' THEN 'youtube'
                            WHEN id LIKE 'direct_show_%' THEN 'direct_audio'
                            WHEN sourceType = 'rss_podcast' OR id LIKE 'rss_show_%' THEN 'rss'
                            ELSE 'seed'
                        END,
                        importedAt = addedAt
                    """.trimIndent(),
                )

                // Deterministically backfill episodes table preserving all IDs, history, progress, and playlists
                db.execSQL(
                    """
                    UPDATE episodes
                    SET contentType = CASE
                            WHEN sourceType = 'local_music' OR id LIKE 'local_%' THEN 'MUSIC'
                            ELSE 'PODCAST'
                        END,
                        importSource = CASE
                            WHEN sourceType = 'local_music' OR id LIKE 'local_%' THEN 'local_library'
                            WHEN sourceType = 'youtube_ref' OR id LIKE 'yt_ep_%' THEN 'youtube'
                            WHEN id LIKE 'direct_ep_%' THEN 'direct_audio'
                            WHEN sourceType = 'rss_podcast' OR id LIKE 'rss_ep_%' THEN 'rss'
                            ELSE 'seed'
                        END,
                        importedAt = publishedAt
                    """.trimIndent(),
                )

                // Indexed separation queries for Podcasts vs Music Albums on large libraries
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_podcasts_contentType_importedAt` ON `podcasts` (`contentType`, `importedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_podcasts_contentType_title` ON `podcasts` (`contentType`, `title`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_contentType_importedAt` ON `episodes` (`contentType`, `importedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_contentType_publishedAt` ON `episodes` (`contentType`, `publishedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_contentType_podcastId` ON `episodes` (`contentType`, `podcastId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_contentType_normalizedAlbum` ON `episodes` (`contentType`, `normalizedAlbum`)")
            }
        }

        @Volatile
        private var instance: PodcastyDatabase? = null

        fun getInstance(context: Context): PodcastyDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PodcastyDatabase::class.java,
                    "podcasty_library.db",
                ).addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                )
                    .build()
                    .also { instance = it }
            }
    }
}
