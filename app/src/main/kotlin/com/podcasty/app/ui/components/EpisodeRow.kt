/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.podcasty.app.R
import com.podcasty.app.ui.theme.LocalPodcastyColors

private val EpisodeArtworkShape = RoundedCornerShape(8.dp)

@Composable
fun EpisodeRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    artworkPath: String? = null,
    isCurrentPlaying: Boolean = false,
    isPlaying: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(start = 24.dp, top = 6.dp, end = 8.dp, bottom = 6.dp),
    onClick: (() -> Unit)? = null,
    onMoreClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = LocalPodcastyColors.current
    val bitmap = rememberArtworkThumbnail(artworkPath)
    val clickModifier = remember(onClick, onLongClick) {
        if (onClick != null || onLongClick != null) {
            Modifier.combinedClickable(
                onClick = { onClick?.invoke() },
                onLongClick = onLongClick,
            )
        } else {
            Modifier
        }
    }
    val handleMoreClick = remember(onMoreClick) { { onMoreClick?.invoke(); Unit } }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(clickModifier)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(EpisodeArtworkShape)
                .background(colors.glassElevated)
                .border(1.dp, colors.borderGlass, EpisodeArtworkShape),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                MaterialSymbol(
                    symbol = MaterialSymbols.Podcasts,
                    contentDescription = null,
                    size = 22.dp,
                    tint = colors.textMuted,
                )
            }
            if (isCurrentPlaying) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(colors.playerBackdrop.copy(alpha = 0.62f)),
                    contentAlignment = Alignment.Center,
                ) {
                    PodcastyPlayingIndicator(isPlaying = isPlaying)
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp, end = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (isCurrentPlaying) colors.primary else colors.textMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        trailingContent?.invoke(this) ?: run {
            IconButton(
                onClick = handleMoreClick,
                modifier = Modifier.size(48.dp),
            ) {
                MaterialSymbol(
                    symbol = MaterialSymbols.MoreVert,
                    contentDescription = stringResource(R.string.episode_row_more_options),
                    size = 20.dp,
                    tint = colors.textMuted,
                )
            }
        }
    }
}
