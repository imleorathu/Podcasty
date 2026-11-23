/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.podcasty.app.ui.theme.LocalPodcastyColors

enum class PodcastyPillButtonVariant { Primary, Secondary, Destructive }

@Composable
fun PodcastyPillButton(
    label: String,
    onClick: () -> Unit,
    variant: PodcastyPillButtonVariant,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalPodcastyColors.current
    val shape = RoundedCornerShape(28.dp)
    val background = when (variant) {
        PodcastyPillButtonVariant.Primary,
        PodcastyPillButtonVariant.Destructive,
        -> colors.primary
        PodcastyPillButtonVariant.Secondary -> colors.buttonSecondary
    }
    val contentColor = when (variant) {
        PodcastyPillButtonVariant.Primary,
        PodcastyPillButtonVariant.Destructive,
        -> colors.onPrimary
        PodcastyPillButtonVariant.Secondary -> colors.textMain
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .clip(shape)
            .background(if (enabled) background else background.copy(alpha = 0.45f))
            .semantics { contentDescription = label }
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
        )
    }
}
