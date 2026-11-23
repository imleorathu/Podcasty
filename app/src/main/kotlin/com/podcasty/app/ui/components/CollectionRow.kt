/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.podcasty.app.R
import com.podcasty.app.domain.PodcastCollection
import com.podcasty.app.ui.theme.LocalPodcastyColors

@Composable
fun CollectionRow(
    collectionId: String,
    name: String,
    subtitle: String?,
    artworkPaths: List<String>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
) {
    val colors = LocalPodcastyColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick)
            .padding(start = 24.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CollectionArtwork(collectionId, artworkPaths)
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
            MaterialSymbol(
                symbol = MaterialSymbols.ChevronRight,
                contentDescription = stringResource(R.string.collection_row_open),
                size = 20.dp,
                tint = colors.textMuted,
            )
        }
    }
}

@Composable
fun CollectionArtwork(
    collectionId: String,
    artworkPaths: List<String>,
    modifier: Modifier = Modifier,
    size: Dp = 110.dp,
) {
    val colors = LocalPodcastyColors.current
    val distinctArt = artworkPaths.distinct()
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.glassElevated)
            .border(1.dp, colors.borderGlass, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val targetPx = if (size > 110.dp) 480 else 128
        when {
            distinctArt.size >= 4 -> {
                Column(Modifier.fillMaxSize()) {
                    CollectionArtworkPair(distinctArt.take(2), targetPx)
                    CollectionArtworkPair(distinctArt.drop(2).take(2), targetPx)
                }
            }
            distinctArt.isNotEmpty() -> CollectionArtworkImage(distinctArt.first(), Modifier.fillMaxSize(), targetPx)
            else -> {
                val symbol = when (collectionId) {
                    PodcastCollection.SavedCollectionId -> MaterialSymbols.Star
                    PodcastCollection.RecentlyPlayedCollectionId -> MaterialSymbols.History
                    else -> MaterialSymbols.QueueMusic
                }
                MaterialSymbol(
                    symbol = symbol,
                    contentDescription = null,
                    size = 30.dp,
                    tint = colors.textMuted,
                    filled = collectionId == PodcastCollection.SavedCollectionId,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.CollectionArtworkPair(paths: List<String>, targetPx: Int) {
    Row(Modifier.fillMaxWidth().weight(1f)) {
        paths.forEach { path -> CollectionArtworkImage(path, Modifier.weight(1f).fillMaxSize(), targetPx) }
    }
}

@Composable
private fun CollectionArtworkImage(path: String, modifier: Modifier, targetPx: Int) {
    val bitmap = rememberArtworkThumbnail(path, targetPx = targetPx)
    if (bitmap != null) Image(bitmap, null, modifier = modifier, contentScale = ContentScale.Crop)
}
