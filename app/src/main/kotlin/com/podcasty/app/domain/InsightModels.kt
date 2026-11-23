package com.podcasty.app.domain

enum class InsightPeriod { SevenDays, ThirtyDays, All }

data class InsightPoint(
    val date: String,
    val value: Int,
)

data class InsightBreakdown(
    val name: String,
    val listenedSeconds: Int,
    val isOther: Boolean = false,
)

data class InsightHostStat(
    val id: String,
    val name: String,
    val artworkAsset: String?,
    val listenedSeconds: Int,
)

data class InsightEpisodeStat(
    val episode: Episode,
    val listenedSeconds: Int,
    val playCount: Int,
)

data class InsightStatusCount(
    val status: PlaybackStatus,
    val count: Int,
)

data class LibraryInsightState(
    val downloadedBytes: Long = 0L,
    val episodes: Int = 0,
    val podcasts: Int = 0,
    val hosts: Int = 0,
    val collections: Int = 0,
    val growth: List<InsightPoint> = emptyList(),
    val statusBreakdown: List<InsightStatusCount> = emptyList(),
)

data class ListeningInsightState(
    val listenedSeconds: Int = 0,
    val changePercent: Float? = null,
    val plays: Int = 0,
    val streakDays: Int = 0,
    val averageSessionSeconds: Int = 0,
    val activity: List<InsightPoint> = emptyList(),
    val categories: List<InsightBreakdown> = emptyList(),
    val completed: Int = 0,
    val skipped: Int = 0,
    val stopped: Int = 0,
    val topHosts: List<InsightHostStat> = emptyList(),
    val topEpisodes: List<InsightEpisodeStat> = emptyList(),
)

data class InsightUiState(
    val libraryPeriod: InsightPeriod = InsightPeriod.ThirtyDays,
    val listeningPeriod: InsightPeriod = InsightPeriod.SevenDays,
    val library: LibraryInsightState = LibraryInsightState(),
    val listening: ListeningInsightState = ListeningInsightState(),
)
