/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.podcasty.app.ui.theme.LocalPodcastyColors

enum class PodcastyDialogActionLayout { Horizontal, Vertical }

@Composable
fun PodcastyDialog(
    title: String,
    description: String,
    dismissLabel: String,
    onDismiss: () -> Unit,
    confirmLabel: String? = null,
    onConfirm: () -> Unit = {},
    confirmVariant: PodcastyPillButtonVariant = PodcastyPillButtonVariant.Primary,
    actionLayout: PodcastyDialogActionLayout = PodcastyDialogActionLayout.Horizontal,
    content: (@Composable () -> Unit)? = null,
) {
    val colors = LocalPodcastyColors.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.85f)
                .clip(RoundedCornerShape(36.dp))
                .background(colors.card),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, top = 28.dp, end = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.textMain,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted,
                )
                content?.invoke()
            }
            when (actionLayout) {
                PodcastyDialogActionLayout.Horizontal -> Row(
                    modifier = Modifier.padding(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PodcastyPillButton(
                        label = dismissLabel,
                        onClick = onDismiss,
                        variant = PodcastyPillButtonVariant.Secondary,
                        modifier = Modifier.weight(1f),
                    )
                    confirmLabel?.let {
                        PodcastyPillButton(
                            label = it,
                            onClick = onConfirm,
                            variant = confirmVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                PodcastyDialogActionLayout.Vertical -> Column(
                    modifier = Modifier.padding(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PodcastyPillButton(label = dismissLabel, onClick = onDismiss, variant = PodcastyPillButtonVariant.Secondary)
                    confirmLabel?.let {
                        PodcastyPillButton(label = it, onClick = onConfirm, variant = confirmVariant)
                    }
                }
            }
        }
    }
}
