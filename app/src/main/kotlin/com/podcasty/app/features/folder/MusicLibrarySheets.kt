/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.features.folder

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.podcasty.app.core.formatCompactDuration
import com.podcasty.app.core.formatFileSize
import com.podcasty.app.domain.Episode
import com.podcasty.app.domain.ExcludedFolder
import com.podcasty.app.domain.LinearFolderSummary
import com.podcasty.app.domain.MusicFolder
import com.podcasty.app.domain.MusicQueue
import com.podcasty.app.storage.LocalAudioFileDetails
import com.podcasty.app.transcript.TranscriptDocument
import com.podcasty.app.ui.components.MaterialSymbol
import com.podcasty.app.ui.components.MaterialSymbols
import com.podcasty.app.ui.components.PodcastyBottomSheet
import com.podcasty.app.ui.components.PodcastyDialog
import com.podcasty.app.ui.components.PodcastyPillButton
import com.podcasty.app.ui.components.PodcastyPillButtonVariant
import com.podcasty.app.ui.components.PodcastyTextField
import com.podcasty.app.ui.components.PodcastyTextFieldSize
import com.podcasty.app.ui.theme.LocalPodcastyColors

@Composable
fun FileDetailsBottomSheet(
    details: LocalAudioFileDetails,
    onDismiss: () -> Unit,
) {
    val colors = LocalPodcastyColors.current
    PodcastyBottomSheet(
        title = {
            Text(
                text = "File details",
                style = MaterialTheme.typography.titleMedium,
                color = colors.textMain,
            )
        },
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DetailField("Title", details.episode.title)
            DetailField("Artist", details.episode.hostName)
            DetailField("Album", details.episode.podcastTitle)
            DetailField("File name", details.fileName)
            DetailField("Folder", details.folderPath)
            DetailField("Storage", details.storageLabel)
            DetailField("Access", if (details.isWritable) "Read / Write" else "Read-only")
            DetailField("Format", details.episode.formatSummary)
            DetailField("Duration", formatCompactDuration(details.episode.durationMs))
            DetailField("Size", formatFileSize(details.sizeBytes))
            DetailField("MIME", details.mimeType)
            DetailField("URI", details.uriString)
            Spacer(Modifier.height(6.dp))
            PodcastyPillButton(
                label = "Close",
                onClick = onDismiss,
                variant = PodcastyPillButtonVariant.Secondary,
            )
        }
    }
}

@Composable
private fun DetailField(label: String, value: String) {
    val colors = LocalPodcastyColors.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = colors.textMuted,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textMain,
        )
        HorizontalDivider(color = colors.borderGlass)
    }
}

@Composable
fun RenameFileBottomSheet(
    episode: Episode,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalPodcastyColors.current
    val initialName = remember(episode.id) {
        episode.audioAsset.substringAfterLast('/').substringBeforeLast('.').ifBlank { episode.title }
    }
    var nameInput by remember(episode.id) { mutableStateOf(initialName) }

    PodcastyBottomSheet(
        title = {
            Text(
                text = "Rename music file",
                style = MaterialTheme.typography.titleMedium,
                color = colors.textMain,
            )
        },
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PodcastyTextField(
                value = nameInput,
                onValueChange = { nameInput = it },
                placeholder = "File name (extension preserved)",
                size = PodcastyTextFieldSize.Large,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    PodcastyPillButton(
                        label = "Cancel",
                        onClick = onDismiss,
                        variant = PodcastyPillButtonVariant.Secondary,
                    )
                }
                Box(Modifier.weight(1f)) {
                    PodcastyPillButton(
                        label = "Rename",
                        onClick = {
                            if (nameInput.trim().isNotEmpty()) {
                                onRename(nameInput.trim())
                            }
                        },
                        variant = PodcastyPillButtonVariant.Primary,
                    )
                }
            }
        }
    }
}

@Composable
fun DestinationFolderPickerSheet(
    title: String,
    musicFolders: List<MusicFolder>,
    linearFolders: List<LinearFolderSummary>,
    onSelectDestination: (treeUri: Uri, relativePath: String) -> Unit,
    onPickSystemFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalPodcastyColors.current
    val writableRoots = remember(musicFolders) {
        musicFolders.filter { it.treeUri.startsWith("content://") }
    }
    PodcastyBottomSheet(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.textMain,
            )
        },
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PodcastyPillButton(
                label = "Choose system folder…",
                onClick = onPickSystemFolder,
                variant = PodcastyPillButtonVariant.Primary,
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(writableRoots, key = { "root_${it.treeUri}" }) { root ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.card)
                            .clickable { onSelectDestination(Uri.parse(root.treeUri), "") }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MaterialSymbol(MaterialSymbols.Storage, null, size = 20.dp, tint = colors.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(root.displayName, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = colors.textMain)
                            Text("${root.storageType.label} (Root)", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                        }
                    }
                }
                val subFolders = linearFolders.filter { it.treeUri.startsWith("content://") && it.relativePath.isNotEmpty() }
                items(subFolders, key = { "sub_${it.folderKey}" }) { folder ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.card)
                            .clickable { onSelectDestination(Uri.parse(folder.treeUri), folder.relativePath) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MaterialSymbol(MaterialSymbols.Folder, null, size = 20.dp, tint = colors.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(folder.folderName, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = colors.textMain)
                            Text(folder.displayPath, style = MaterialTheme.typography.bodySmall, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmbeddedLyricsBottomSheet(
    episode: Episode,
    transcriptText: String?,
    documents: List<TranscriptDocument>,
    onEditTags: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalPodcastyColors.current
    val displayLyrics = remember(transcriptText, documents) {
        if (documents.isNotEmpty() && documents.first().cues.isNotEmpty()) {
            documents.first().cues.joinToString("\n") { it.text }
        } else {
            transcriptText?.takeIf { it.isNotBlank() }
        }
    }
    PodcastyBottomSheet(
        title = {
            Column {
                Text(episode.title, style = MaterialTheme.typography.titleMedium, color = colors.textMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Embedded & sidecar lyrics", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            }
        },
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (displayLyrics.isNullOrBlank()) {
                Text(
                    text = "No embedded lyrics or sidecar LRC file found for this track.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted,
                )
            } else {
                Text(
                    text = displayLyrics,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textMain,
                )
            }
            if (episode.isLocalAudioFile) {
                PodcastyPillButton(
                    label = "Edit lyrics in Tag Editor",
                    onClick = onEditTags,
                    variant = PodcastyPillButtonVariant.Secondary,
                )
            }
        }
    }
}

@Composable
fun DeleteFilesConfirmDialog(
    episodes: List<Episode>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = if (episodes.size == 1) "Delete \"${episodes.first().title}\"?" else "Delete ${episodes.size} audio files?"
    val body = if (episodes.size == 1) {
        "This permanently removes the file from your device storage and library. Android will prompt you for confirmation if required by Scoped Storage."
    } else {
        "This permanently removes ${episodes.size} selected audio files from your device storage and library."
    }
    PodcastyDialog(
        title = title,
        description = body,
        dismissLabel = "Cancel",
        onDismiss = onDismiss,
        confirmLabel = "Delete",
        onConfirm = onConfirm,
        confirmVariant = PodcastyPillButtonVariant.Destructive,
    )
}

@Composable
fun MultiQueueManagerBottomSheet(
    queues: List<MusicQueue>,
    activeQueueId: String,
    targetEpisodeIdsToAdd: List<String> = emptyList(),
    onSelectQueue: (String) -> Unit,
    onAddEpisodesToQueue: (queueId: String, episodeIds: List<String>) -> Unit,
    onCreateQueue: (name: String, initialEpisodeIds: List<String>) -> Unit,
    onRenameQueue: (queueId: String, newName: String) -> Unit,
    onDeleteQueue: (queueId: String) -> Unit,
    onClearActiveQueue: () -> Unit,
    onUndoClearActiveQueue: () -> Unit = {},
    onSaveQueueAsPlaylist: (name: String, episodeIds: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalPodcastyColors.current
    var newQueueName by remember { mutableStateOf("") }
    var clearedRecently by remember { mutableStateOf(false) }
    var renamingQueue by remember { mutableStateOf<MusicQueue?>(null) }
    var renameText by remember { mutableStateOf("") }
    val isPickerMode = targetEpisodeIdsToAdd.isNotEmpty()

    PodcastyBottomSheet(
        title = {
            Text(
                text = if (isPickerMode) "Add ${targetEpisodeIdsToAdd.size} track(s) to queue" else "Music queues",
                style = MaterialTheme.typography.titleMedium,
                color = colors.textMain,
            )
        },
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PodcastyTextField(
                    value = newQueueName,
                    onValueChange = { newQueueName = it },
                    placeholder = "New queue name (e.g., Tamil Songs)",
                    size = PodcastyTextFieldSize.Medium,
                    modifier = Modifier.weight(1f),
                )
                PodcastyPillButton(
                    label = "Create",
                    onClick = {
                        val trimmed = newQueueName.trim()
                        if (trimmed.isNotEmpty()) {
                            onCreateQueue(trimmed, targetEpisodeIdsToAdd)
                            newQueueName = ""
                            if (isPickerMode) onDismiss()
                        }
                    },
                    variant = PodcastyPillButtonVariant.Primary,
                    modifier = Modifier.width(92.dp),
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(queues, key = { it.id }) { q ->
                    val isActive = q.id == activeQueueId || q.isActive
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isActive) colors.primary.copy(alpha = 0.14f) else colors.card)
                            .clickable {
                                if (isPickerMode) {
                                    onAddEpisodesToQueue(q.id, targetEpisodeIdsToAdd)
                                    onDismiss()
                                } else {
                                    onSelectQueue(q.id)
                                    onDismiss()
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MaterialSymbol(
                            symbol = MaterialSymbols.QueueMusic,
                            contentDescription = null,
                            size = 20.dp,
                            tint = if (isActive) colors.primary else colors.textMuted,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = q.name + if (isActive) " (Active)" else "",
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.textMain,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${q.episodeIds.size} tracks",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textMuted,
                            )
                        }
                        if (!isPickerMode) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        renamingQueue = q
                                        renameText = q.name
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                MaterialSymbol(MaterialSymbols.Edit, "Rename", size = 18.dp, tint = colors.textMuted)
                            }
                            if (queues.size > 1) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onDeleteQueue(q.id) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    MaterialSymbol(MaterialSymbols.Delete, "Delete", size = 18.dp, tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }

            if (!isPickerMode) {
                val activeQueue = queues.firstOrNull { it.id == activeQueueId || it.isActive }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (activeQueue != null && activeQueue.episodeIds.isNotEmpty()) {
                        Box(Modifier.weight(1f)) {
                            PodcastyPillButton(
                                label = "Save as playlist",
                                onClick = {
                                    onSaveQueueAsPlaylist("${activeQueue.name} Playlist", activeQueue.episodeIds)
                                    onDismiss()
                                },
                                variant = PodcastyPillButtonVariant.Secondary,
                            )
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        PodcastyPillButton(
                            label = if (clearedRecently) "Undo clear" else "Clear active queue",
                            onClick = {
                                if (clearedRecently) {
                                    onUndoClearActiveQueue()
                                    clearedRecently = false
                                } else {
                                    onClearActiveQueue()
                                    clearedRecently = true
                                }
                            },
                            variant = PodcastyPillButtonVariant.Secondary,
                        )
                    }
                }
            }
        }
    }

    renamingQueue?.let { rq ->
        PodcastyDialog(
            title = "Rename queue",
            description = "Choose a new name for \"${rq.name}\".",
            dismissLabel = "Cancel",
            onDismiss = { renamingQueue = null },
            confirmLabel = "Save",
            onConfirm = {
                if (renameText.trim().isNotEmpty()) {
                    onRenameQueue(rq.id, renameText.trim())
                }
                renamingQueue = null
            },
            content = {
                PodcastyTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    placeholder = "Queue name",
                    size = PodcastyTextFieldSize.Medium,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
    }
}

@Composable
fun ExcludedFoldersBottomSheet(
    excludedFolders: List<ExcludedFolder>,
    onRestoreFolder: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalPodcastyColors.current
    PodcastyBottomSheet(
        title = {
            Text("Excluded folders", style = MaterialTheme.typography.titleMedium, color = colors.textMain)
        },
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (excludedFolders.isEmpty()) {
                Text(
                    text = "No folders have been excluded. Exclude any folder from Linear Folder view to hide it from the music index.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(excludedFolders, key = { it.folderKey }) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.card)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = item.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textMain,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.width(8.dp))
                            PodcastyPillButton(
                                label = "Restore",
                                onClick = { onRestoreFolder(item.folderKey) },
                                variant = PodcastyPillButtonVariant.Secondary,
                                modifier = Modifier.width(88.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DuplicateMusicBottomSheet(
    result: com.podcasty.app.storage.DuplicateMusicResult?,
    onDeleteSelected: (List<Episode>) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalPodcastyColors.current
    var selected by remember(result) { mutableStateOf(emptySet<String>()) }
    PodcastyBottomSheet(
        title = { Text("Duplicate music", style = MaterialTheme.typography.titleMedium, color = colors.textMain) },
        onDismiss = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (result == null) {
                Text("Comparing matching files… Close this sheet to cancel.", color = colors.textMuted)
            } else {
                Text("${result.groups.size} identical file groups · ${result.unreadableFiles} unreadable files" + if (result.truncated) " · First 1,000 candidates only" else "", color = colors.textMuted, style = MaterialTheme.typography.bodySmall)
                if (result.groups.isEmpty()) Text("No identical files found among matching metadata candidates.", color = colors.textMain)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    result.groups.forEachIndexed { group, tracks ->
                        item(key = "group_$group") { Text("Copy group ${group + 1}", color = colors.primary, style = MaterialTheme.typography.labelMedium) }
                        items(tracks, key = { it.id }) { track ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(track.title, color = colors.textMain, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${track.hostName} · ${formatCompactDuration(track.durationMs)} · ${formatFileSize(track.fileSizeBytes)}", color = colors.textMuted, style = MaterialTheme.typography.bodySmall)
                                    Text(track.sourceUri.orEmpty(), color = colors.textMuted, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                                androidx.compose.material3.Checkbox(checked = track.id in selected, onCheckedChange = { checked -> selected = if (checked) selected + track.id else selected - track.id })
                            }
                        }
                    }
                }
                if (selected.isNotEmpty()) PodcastyPillButton(
                    label = "Remove ${selected.size} selected files…",
                    onClick = { onDeleteSelected(result.groups.flatten().filter { it.id in selected }) },
                    variant = PodcastyPillButtonVariant.Destructive,
                )
            }
        }
    }
}
