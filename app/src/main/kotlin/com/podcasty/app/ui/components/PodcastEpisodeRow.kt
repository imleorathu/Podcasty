/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.podcasty.app.R
import com.podcasty.app.ui.theme.LocalPodcastyColors

@Composable
fun PodcastEpisodeRow(
    number: Int,
    title: String,
    subtitle: String,
    isCurrentPlaying: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onMoreClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = LocalPodcastyColors.current
    Row(
        modifier
            .fillMaxWidth()
            .then(
                if (onClick == null && onLongClick == null) Modifier else Modifier.combinedClickable(
                    onClick = { onClick?.invoke() },
                    onLongClick = onLongClick,
                ),
            )
            .padding(start = 22.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            number.toString(),
            Modifier.width(36.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (isCurrentPlaying) colors.primary else colors.textMuted,
        )
        Column(
            Modifier
                .weight(1f)
                .padding(end = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (isCurrentPlaying) colors.primary else colors.textMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { onMoreClick?.invoke() }) {
            MaterialSymbol(
                MaterialSymbols.MoreVert,
                stringResource(R.string.episode_row_more_options),
                size = 20.dp,
                tint = colors.textMuted,
            )
        }
    }
}
