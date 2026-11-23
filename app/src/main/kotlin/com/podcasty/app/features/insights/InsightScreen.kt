/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.features.insights

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import com.podcasty.app.R
import com.podcasty.app.core.formatFileSize
import com.podcasty.app.domain.InsightBreakdown
import com.podcasty.app.domain.InsightPeriod
import com.podcasty.app.domain.InsightStatusCount
import com.podcasty.app.domain.InsightUiState
import com.podcasty.app.domain.ListeningInsightState
import com.podcasty.app.ui.components.DiscCard
import com.podcasty.app.ui.components.EpisodeRow
import com.podcasty.app.ui.components.MaterialSymbols
import com.podcasty.app.ui.theme.LocalPodcastyColors

@Composable
fun InsightScreen(
    state: InsightUiState,
    hazeState: HazeState?,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onLibraryPeriodSelected: (InsightPeriod) -> Unit,
    onListeningPeriodSelected: (InsightPeriod) -> Unit,
    onHostClick: (String) -> Unit,
    onEpisodeClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expandedEpisodes by rememberSaveable { mutableStateOf(false) }
    val visibleEpisodes = if (expandedEpisodes) state.listening.topEpisodes else state.listening.topEpisodes.take(5)

    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(key = "insight-lib-header") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionHeader(R.string.insight_library_section, Modifier.weight(1f))
                InsightPeriodSelector(state.libraryPeriod, onLibraryPeriodSelected, hazeState)
            }
        }
        item(key = "insight-lib-metrics") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard(
                    labelRes = R.string.insight_library_size,
                    value = formatFileSize(state.library.downloadedBytes),
                    modifier = Modifier.fillMaxWidth(),
                )
                MetricPair(
                    R.string.insight_total_episodes to formatNumber(state.library.episodes),
                    R.string.insight_total_podcasts to formatNumber(state.library.podcasts),
                )
                MetricPair(
                    R.string.insight_total_hosts to formatNumber(state.library.hosts),
                    R.string.insight_total_collections to formatNumber(state.library.collections),
                )
            }
        }
        item(key = "insight-lib-charts") {
            ResponsivePair(
                first = {
                    ChartCard(R.string.insight_library_growth, MaterialSymbols.LegendToggle) {
                        if (state.library.episodes > 0) {
                            InsightLineChart(
                                state.library.growth,
                                stringResource(R.string.insight_library_growth_description),
                            )
                        } else {
                            EmptyInsightText(R.string.insight_no_library_data)
                        }
                    }
                },
                second = { EpisodeStatusCard(state.library.statusBreakdown) },
            )
        }
        item(key = "insight-history-header") {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionHeader(R.string.insight_listening_section, Modifier.weight(1f))
                InsightPeriodSelector(state.listeningPeriod, onListeningPeriodSelected, hazeState)
            }
        }
        item(key = "insight-listening-hero") {
            ListeningHero(state.listening)
        }
        item(key = "insight-streak-session") {
            MetricPair(
                R.string.insight_streak to stringResource(R.string.insight_days_value, state.listening.streakDays),
                R.string.insight_average_session to formatInsightDuration(state.listening.averageSessionSeconds),
            )
        }
        item(key = "insight-category-outcome") {
            ResponsivePair(
                first = { CategoryCard(state.listening.categories) },
                second = { OutcomeCard(state.listening) },
            )
        }
        item(key = "insight-top-hosts") {
            ChartCard(
                titleRes = R.string.insight_top_hosts,
                symbol = MaterialSymbols.People,
                contentPadding = PaddingValues(vertical = 20.dp),
                headerPadding = PaddingValues(horizontal = 20.dp),
            ) {
                if (state.listening.topHosts.isEmpty()) {
                    EmptyInsightText(R.string.insight_no_top_hosts, Modifier.padding(horizontal = 20.dp))
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(state.listening.topHosts, key = { it.id }) { host ->
                            DiscCard(
                                title = host.name,
                                subtitle = formatInsightDuration(host.listenedSeconds),
                                artworkPath = host.artworkAsset,
                                artworkShape = CircleShape,
                                modifier = Modifier.size(112.dp, 168.dp),
                                fallbackSymbol = MaterialSymbols.People,
                                onClick = { onHostClick(host.id) },
                            )
                        }
                    }
                }
            }
        }
        item(key = "insight-top-episodes") {
            ChartCard(R.string.insight_top_episodes, MaterialSymbols.Headphones) {
                if (visibleEpisodes.isEmpty()) {
                    EmptyInsightText(R.string.insight_no_top_episodes)
                } else {
                    visibleEpisodes.forEachIndexed { index, item ->
                        EpisodeRow(
                            title = item.episode.title,
                            subtitle = item.episode.podcastTitle,
                            artworkPath = item.episode.artworkAsset,
                            contentPadding = PaddingValues(vertical = 6.dp),
                            onClick = { onEpisodeClick(item.episode.id) },
                            trailingContent = {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = formatInsightDuration(item.listenedSeconds),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = LocalPodcastyColors.current.textMuted,
                                    )
                                    Text(
                                        text = stringResource(R.string.insight_plays_value, item.playCount),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = LocalPodcastyColors.current.textMuted,
                                    )
                                }
                            },
                        )
                        if (index < visibleEpisodes.lastIndex) {
                            HorizontalDivider(
                                color = LocalPodcastyColors.current.borderGlass,
                                thickness = 1.dp,
                            )
                        }
                    }
                    if (state.listening.topEpisodes.size > 5) {
                        Text(
                            text = stringResource(if (expandedEpisodes) R.string.insight_show_less else R.string.insight_show_more),
                            color = LocalPodcastyColors.current.textMuted,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .align(Alignment.End)
                                .heightIn(min = 48.dp)
                                .clickable(role = Role.Button) { expandedEpisodes = !expandedEpisodes }
                                .padding(horizontal = 12.dp, vertical = 15.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ListeningHero(state: ListeningInsightState) {
    ChartCard(R.string.insight_total_time, MaterialSymbols.Headphones) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                Text(
                    text = formatInsightDuration(state.listenedSeconds),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = LocalPodcastyColors.current.textMain,
                )
                state.changePercent?.let {
                    Text(
                        text = stringResource(R.string.insight_change_value, it),
                        style = MaterialTheme.typography.labelMedium,
                        color = LocalPodcastyColors.current.textMuted,
                    )
                }
            }
            Text(
                text = stringResource(R.string.insight_plays_value, state.plays),
                style = MaterialTheme.typography.labelLarge,
                color = LocalPodcastyColors.current.textMuted,
            )
        }
        if (state.listenedSeconds > 0) {
            InsightBarChart(
                state.activity,
                stringResource(R.string.insight_activity_description),
                Modifier.padding(top = 20.dp),
            )
        } else {
            EmptyInsightText(R.string.insight_no_listening_data)
        }
    }
}

@Composable
private fun EpisodeStatusCard(values: List<InsightStatusCount>) {
    ChartCard(R.string.insight_episode_status, MaterialSymbols.GraphicEq) {
        if (values.isEmpty()) {
            EmptyInsightText(R.string.insight_no_library_data)
        } else {
            val total = values.sumOf { it.count }
            InsightDonut(
                values = values.map { it.count to statusColor(it.status) },
                centerText = formatNumber(total),
                description = stringResource(R.string.insight_episode_status_description),
            )
            values.forEach {
                BreakdownRow(statusLabel(it.status), it.count, total, statusColor(it.status))
            }
        }
    }
}

@Composable
private fun CategoryCard(values: List<InsightBreakdown>) {
    ChartCard(R.string.insight_category_distribution, MaterialSymbols.Label) {
        if (values.isEmpty()) {
            EmptyInsightText(R.string.insight_no_categories)
        } else {
            val total = values.sumOf { it.listenedSeconds }
            InsightDonut(
                values = values.mapIndexed { index, value -> value.listenedSeconds to insightPalette(index) },
                centerText = formatInsightDuration(total),
                description = stringResource(R.string.insight_category_description),
            )
            values.forEachIndexed { index, value ->
                BreakdownRow(
                    label = if (value.isOther) stringResource(R.string.insight_other) else value.name,
                    value = value.listenedSeconds,
                    total = total,
                    color = insightPalette(index),
                    duration = true,
                )
            }
        }
    }
}

@Composable
private fun OutcomeCard(state: ListeningInsightState) {
    val values = listOf(
        stringResource(R.string.insight_completed) to state.completed,
        stringResource(R.string.insight_skipped) to state.skipped,
        stringResource(R.string.insight_stopped) to state.stopped,
    ).sortedByDescending { it.second }
    val colors = LocalPodcastyColors.current
    val palette = listOf(colors.primary, colors.accentAmber, colors.textMuted)
    val total = values.sumOf { it.second }
    ChartCard(R.string.insight_playback_outcomes, MaterialSymbols.BarChart) {
        if (total == 0) {
            EmptyInsightText(R.string.insight_no_outcomes)
        } else {
            InsightDonut(
                values = values.mapIndexed { index, value -> value.second to palette[index] },
                centerText = stringResource(R.string.insight_attempts_value, total),
                description = stringResource(R.string.insight_outcomes_description),
            )
            values.forEachIndexed { index, value ->
                BreakdownRow(value.first, value.second, total, palette[index])
            }
        }
    }
}
