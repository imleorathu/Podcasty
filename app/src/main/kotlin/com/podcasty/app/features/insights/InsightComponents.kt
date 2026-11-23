/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.features.insights

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import java.text.NumberFormat
import com.podcasty.app.R
import com.podcasty.app.domain.InsightPeriod
import com.podcasty.app.domain.PlaybackStatus
import com.podcasty.app.ui.components.MaterialSymbol
import com.podcasty.app.ui.components.PodcastyCard
import com.podcasty.app.ui.components.Selection
import com.podcasty.app.ui.components.SelectionOption
import com.podcasty.app.ui.theme.LocalPodcastyColors

@Composable
internal fun ChartCard(
    @StringRes titleRes: Int,
    symbol: String,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    headerPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    PodcastyCard(contentPadding = contentPadding) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(headerPadding).padding(bottom = 16.dp),
        ) {
            MaterialSymbol(symbol, null, size = 18.dp, tint = LocalPodcastyColors.current.textMuted)
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.labelLarge,
                color = LocalPodcastyColors.current.textMuted,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        content()
    }
}

@Composable
internal fun ResponsivePair(first: @Composable () -> Unit, second: @Composable () -> Unit) {
    BoxWithConstraints {
        if (maxWidth >= 600.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) { first() }
                Column(Modifier.weight(1f)) { second() }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { first(); second() }
        }
    }
}

@Composable
internal fun InsightPeriodSelector(
    selected: InsightPeriod,
    onSelected: (InsightPeriod) -> Unit,
    hazeState: HazeState?,
    modifier: Modifier = Modifier,
) {
    val options = listOf(
        InsightPeriod.SevenDays to R.string.insight_range_7d,
        InsightPeriod.ThirtyDays to R.string.insight_range_30d,
        InsightPeriod.All to R.string.insight_range_all,
    )
    Selection(
        options = options.map { (value, label) -> SelectionOption(value, label) },
        selectedValue = selected,
        onValueSelected = onSelected,
        hazeState = hazeState,
        modifier = modifier,
    )
}

@Composable
internal fun MetricPair(
    first: Pair<Int, String>,
    second: Pair<Int, String>,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MetricCard(first.first, first.second, Modifier.weight(1f))
        MetricCard(second.first, second.second, Modifier.weight(1f))
    }
}

@Composable
internal fun MetricCard(@StringRes labelRes: Int, value: String, modifier: Modifier = Modifier) {
    PodcastyCard(modifier = modifier, contentPadding = PaddingValues(18.dp)) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = LocalPodcastyColors.current.textMuted,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = LocalPodcastyColors.current.textMain,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
internal fun BreakdownRow(label: String, value: Int, total: Int, color: Color, duration: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(
            text = label,
            modifier = Modifier.weight(1f).padding(start = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = LocalPodcastyColors.current.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = if (duration) formatInsightDuration(value) else formatNumber(value),
            style = MaterialTheme.typography.bodySmall,
            color = LocalPodcastyColors.current.textMuted,
        )
        Text(
            text = stringResource(R.string.insight_percent_value, if (total == 0) 0 else value * 100 / total),
            modifier = Modifier.padding(start = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = LocalPodcastyColors.current.textMuted,
        )
    }
}

@Composable
internal fun SectionHeader(@StringRes titleRes: Int, modifier: Modifier = Modifier) = Text(
    text = stringResource(titleRes),
    modifier = modifier,
    style = MaterialTheme.typography.titleLarge,
    fontWeight = FontWeight.SemiBold,
    color = LocalPodcastyColors.current.textMain,
)

@Composable
internal fun EmptyInsightText(@StringRes textRes: Int, modifier: Modifier = Modifier) = Text(
    text = stringResource(textRes),
    modifier = modifier.fillMaxWidth().padding(vertical = 36.dp),
    style = MaterialTheme.typography.bodyMedium,
    color = LocalPodcastyColors.current.textMuted,
)

internal fun formatNumber(value: Int): String = NumberFormat.getIntegerInstance().format(value)

@Composable
internal fun formatInsightDuration(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val days = safe / 86_400
    val hours = safe % 86_400 / 3_600
    val minutes = safe % 3_600 / 60
    return when {
        days > 0 -> stringResource(R.string.insight_duration_days_hours, days, hours)
        hours > 0 -> stringResource(R.string.insight_duration_hours_minutes, hours, minutes)
        minutes > 0 -> stringResource(R.string.insight_duration_minutes, minutes)
        else -> stringResource(R.string.insight_duration_seconds, safe)
    }
}

@Composable
internal fun statusLabel(status: PlaybackStatus): String = stringResource(
    when (status) {
        PlaybackStatus.Played -> R.string.insight_completed
        PlaybackStatus.InProgress -> R.string.insight_in_progress
        PlaybackStatus.Unplayed -> R.string.insight_unplayed
    },
)

@Composable
internal fun statusColor(status: PlaybackStatus): Color {
    val colors = LocalPodcastyColors.current
    return when (status) {
        PlaybackStatus.Played -> colors.success
        PlaybackStatus.InProgress -> colors.primary
        PlaybackStatus.Unplayed -> colors.accentAmber
    }
}

@Composable
internal fun insightPalette(index: Int): Color {
    val colors = LocalPodcastyColors.current
    return listOf(
        colors.primary,
        colors.success,
        colors.accentAmber,
        colors.accentFuchsia,
        colors.textMain.copy(alpha = 0.65f),
        colors.textMuted,
    )[index.coerceAtMost(5)]
}
