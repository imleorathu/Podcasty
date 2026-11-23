/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.podcasty.app.ui.theme.LocalPodcastyColors

@Composable
fun HeroCard(
    symbol: String,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    belowTitle: (@Composable () -> Unit)? = null,
    bottomContent: (@Composable () -> Unit)? = null,
) {
    HeroCard(
        title = title,
        description = description,
        modifier = modifier,
        belowTitle = belowTitle,
        bottomContent = bottomContent,
    ) {
        MaterialSymbol(
            symbol = symbol,
            contentDescription = null,
            size = 40.dp,
            tint = LocalPodcastyColors.current.textMuted,
        )
    }
}

@Composable
fun HeroCard(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    belowTitle: (@Composable () -> Unit)? = null,
    bottomContent: (@Composable () -> Unit)? = null,
    icon: @Composable () -> Unit,
) {
    val colors = LocalPodcastyColors.current
    PodcastyCard(
        modifier = modifier,
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            icon()
            Text(
                text = title,
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = colors.textMain,
            )
            belowTitle?.invoke()
            Text(
                text = description,
                modifier = Modifier.padding(top = if (belowTitle == null) 8.dp else 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted,
            )
        }
        bottomContent?.invoke()
    }
}
