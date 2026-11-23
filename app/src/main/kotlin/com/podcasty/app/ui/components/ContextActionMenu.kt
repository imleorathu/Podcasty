/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.podcasty.app.ui.theme.LocalPodcastyColors

sealed interface ContextActionMenuEntry {
    data object Divider : ContextActionMenuEntry
    data class Action(
        val label: String,
        val symbol: String,
        val enabled: Boolean = true,
        val destructive: Boolean = false,
        val onClick: () -> Unit,
    ) : ContextActionMenuEntry
}

@Composable
fun ContextActionMenu(entries: List<ContextActionMenuEntry>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
    ) {
        entries.forEach { entry ->
            when (entry) {
                ContextActionMenuEntry.Divider -> ActionListDivider(ActionListDividerStyle.FullWidth)
                is ContextActionMenuEntry.Action -> ContextActionMenuAction(entry)
            }
        }
    }
}

@Composable
private fun ContextActionMenuAction(entry: ContextActionMenuEntry.Action) {
    val colors = LocalPodcastyColors.current
    val contentColor = if (entry.destructive) colors.primary else colors.textMain
    val alpha = if (entry.enabled) 1f else 0.45f
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics { contentDescription = entry.label }
            .clickable(enabled = entry.enabled, role = Role.Button, onClick = entry.onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(22.dp),
            contentAlignment = Alignment.Center,
        ) {
            MaterialSymbol(
                entry.symbol,
                null,
                size = 20.dp,
                tint = contentColor.copy(alpha = alpha),
            )
        }
        Text(
            entry.label,
            modifier = Modifier.weight(1f).padding(start = 14.dp),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Medium,
                lineHeight = 19.sp,
            ),
            color = contentColor.copy(alpha = alpha),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
