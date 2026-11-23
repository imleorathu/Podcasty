/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.features.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.podcasty.app.domain.Episode
import com.podcasty.app.domain.InsightBreakdown
import com.podcasty.app.domain.InsightEpisodeStat
import com.podcasty.app.domain.InsightHostStat
import com.podcasty.app.domain.InsightPeriod
import com.podcasty.app.domain.InsightPoint
import com.podcasty.app.domain.InsightStatusCount
import com.podcasty.app.domain.InsightUiState
import com.podcasty.app.domain.LibraryInsightState
import com.podcasty.app.domain.ListeningHistoryEntry
import com.podcasty.app.domain.ListeningInsightState
import com.podcasty.app.domain.PlaybackStatus
import com.podcasty.app.domain.PodcastRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class InsightViewModel(
    private val repository: PodcastRepository,
) : ViewModel() {
    private val libraryPeriod = MutableStateFlow(InsightPeriod.ThirtyDays)
    private val listeningPeriod = MutableStateFlow(InsightPeriod.SevenDays)

    val uiState: StateFlow<InsightUiState> = combine(
        combine(repository.episodes, repository.podcasts, repository.hosts) { e, p, h -> Triple(e, p, h) },
        combine(repository.collections, repository.listeningHistory) { c, hist -> c to hist },
        combine(libraryPeriod, listeningPeriod) { lp, hsp -> lp to hsp },
    ) { (episodes, podcasts, hosts), (collections, history), (libPeriod, listenPeriod) ->
        val now = System.currentTimeMillis()
        val libDays = when (libPeriod) {
            InsightPeriod.SevenDays -> 7
            InsightPeriod.ThirtyDays -> 30
            InsightPeriod.All -> 90
        }
        val listenDays = when (listenPeriod) {
            InsightPeriod.SevenDays -> 7
            InsightPeriod.ThirtyDays -> 30
            InsightPeriod.All -> 90
        }

        val growth = buildCumulativeGrowth(episodes, libDays, now)
        val statusCounts = PlaybackStatus.entries.mapNotNull { status ->
            val count = episodes.count { it.playbackStatus == status }
            if (count > 0) InsightStatusCount(status, count) else null
        }.sortedByDescending { it.count }

        val libraryState = LibraryInsightState(
            downloadedBytes = episodes.filter { it.isDownloaded }.sumOf { it.fileSizeBytes },
            episodes = episodes.size,
            podcasts = podcasts.size,
            hosts = hosts.size,
            collections = collections.size + 2, // include Saved + Recently Played
            growth = growth,
            statusBreakdown = statusCounts,
        )

        val periodStart = if (listenPeriod == InsightPeriod.All) 0L else now - listenDays * DayMillis
        val previousStart = if (listenPeriod == InsightPeriod.All) 0L else periodStart - listenDays * DayMillis
        val activeHistory = history.filter { it.playedAt >= periodStart }
        val prevHistory = if (listenPeriod == InsightPeriod.All) emptyList() else {
            history.filter { it.playedAt in previousStart until periodStart }
        }

        val listenedSeconds = activeHistory.sumOf { it.listenedSeconds }
        val prevSeconds = prevHistory.sumOf { it.listenedSeconds }
        val changePct = if (listenPeriod != InsightPeriod.All && prevSeconds > 0) {
            ((listenedSeconds - prevSeconds).toFloat() / prevSeconds.toFloat()) * 100f
        } else {
            null
        }
        val plays = activeHistory.size
        val averageSession = if (plays > 0) listenedSeconds / plays else 0
        val streakDays = computeStreakDays(history, now)
        val activityPoints = buildDailyActivity(activeHistory, listenDays.coerceAtMost(30), now)

        val categoryMap = activeHistory.groupBy { it.categoryName }
            .mapValues { (_, items) -> items.sumOf { it.listenedSeconds } }
            .entries.sortedByDescending { it.value }
        val topCategories = if (categoryMap.size <= 5) {
            categoryMap.map { InsightBreakdown(it.key, it.value) }
        } else {
            val head = categoryMap.take(4).map { InsightBreakdown(it.key, it.value) }
            val otherSum = categoryMap.drop(4).sumOf { it.value }
            head + InsightBreakdown("Other", otherSum, isOther = true)
        }

        val completed = activeHistory.count { it.completed }
        val skipped = activeHistory.count { it.skipped }
        val stopped = (plays - completed - skipped).coerceAtLeast(0)

        val hostsById = hosts.associateBy { it.id }
        val topHosts = activeHistory.groupBy { it.hostId }
            .mapNotNull { (hostId, items) ->
                val host = hostsById[hostId]
                val sec = items.sumOf { it.listenedSeconds }
                InsightHostStat(
                    id = hostId,
                    name = host?.name ?: items.first().hostName,
                    artworkAsset = host?.artworkAsset,
                    listenedSeconds = sec,
                )
            }
            .sortedByDescending { it.listenedSeconds }
            .take(8)

        val episodesById = episodes.associateBy { it.id }
        val topEpisodes = activeHistory.groupBy { it.episodeId }
            .mapNotNull { (epId, items) ->
                val ep = episodesById[epId] ?: return@mapNotNull null
                InsightEpisodeStat(
                    episode = ep,
                    listenedSeconds = items.sumOf { it.listenedSeconds },
                    playCount = items.size,
                )
            }
            .sortedByDescending { it.listenedSeconds }
            .take(10)

        val listeningState = ListeningInsightState(
            listenedSeconds = listenedSeconds,
            changePercent = changePct,
            plays = plays,
            streakDays = streakDays,
            averageSessionSeconds = averageSession,
            activity = activityPoints,
            categories = topCategories,
            completed = completed,
            skipped = skipped,
            stopped = stopped,
            topHosts = topHosts,
            topEpisodes = topEpisodes,
        )

        InsightUiState(
            libraryPeriod = libPeriod,
            listeningPeriod = listenPeriod,
            library = libraryState,
            listening = listeningState,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), InsightUiState())

    fun setLibraryPeriod(period: InsightPeriod) {
        libraryPeriod.value = period
    }

    fun setListeningPeriod(period: InsightPeriod) {
        listeningPeriod.value = period
    }

    private fun buildCumulativeGrowth(episodes: List<Episode>, days: Int, now: Long): List<InsightPoint> {
        val fmt = SimpleDateFormat("MMM d", Locale.US)
        val bucketCount = minOf(days, 12)
        val stepMs = (days * DayMillis) / bucketCount.coerceAtLeast(1)
        val startMs = now - days * DayMillis
        return (0 until bucketCount).map { i ->
            val cutoff = if (i == bucketCount - 1) now else startMs + (i + 1) * stepMs
            val count = episodes.count { it.publishedAt <= cutoff }
            InsightPoint(date = fmt.format(Date(cutoff)), value = count)
        }
    }

    private fun buildDailyActivity(entries: List<ListeningHistoryEntry>, days: Int, now: Long): List<InsightPoint> {
        val fmt = SimpleDateFormat("MMM d", Locale.US)
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayStart = cal.timeInMillis
        return (days - 1 downTo 0).map { daysAgo ->
            val dayStart = todayStart - daysAgo * DayMillis
            val dayEnd = dayStart + DayMillis
            val sec = entries.filter { it.playedAt in dayStart until dayEnd }.sumOf { it.listenedSeconds }
            InsightPoint(date = fmt.format(Date(dayStart)), value = sec)
        }
    }

    private fun computeStreakDays(entries: List<ListeningHistoryEntry>, now: Long): Int {
        if (entries.isEmpty()) return 0
        val cal = Calendar.getInstance()
        val daysSet = entries.map { entry ->
            cal.timeInMillis = entry.playedAt
            cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
        }.toSet()
        cal.timeInMillis = now
        var streak = 0
        for (i in 0 until 365) {
            val key = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
            if (key in daysSet) {
                streak++
            } else if (i > 0) {
                break
            }
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return streak
    }

    private companion object {
        const val DayMillis = 86_400_000L
    }

    class Factory(private val repository: PodcastRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = InsightViewModel(repository) as T
    }
}
