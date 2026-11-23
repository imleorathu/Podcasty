/*
 * Podcasty — Android Podcast & Music Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
package com.podcasty.app.features.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.podcasty.app.R
import com.podcasty.app.core.formatSpeedLabel
import com.podcasty.app.core.formatTimerCountdown
import com.podcasty.app.domain.SleepTimerPreset
import com.podcasty.app.domain.SleepTimerRequest
import com.podcasty.app.domain.SleepTimerState
import com.podcasty.app.domain.SupportedFadeOutSeconds
import com.podcasty.app.domain.SupportedPlaybackSpeeds
import com.podcasty.app.ui.components.ActionListDivider
import com.podcasty.app.ui.components.ActionListDividerStyle
import com.podcasty.app.ui.components.PodcastyBottomSheet
import com.podcasty.app.ui.components.PodcastyPillButton
import com.podcasty.app.ui.components.PodcastyPillButtonVariant
import com.podcasty.app.ui.components.PodcastyTextField
import com.podcasty.app.ui.components.PodcastyTextFieldSize
import com.podcasty.app.ui.components.RadioList
import com.podcasty.app.ui.components.SelectionOption
import com.podcasty.app.ui.theme.LocalPodcastyColors
import kotlin.math.abs

@Composable
internal fun PlaybackSpeedBottomSheet(
    currentSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalPodcastyColors.current
    val options = SupportedPlaybackSpeeds.map { speed ->
        val suffix = when (speed) {
            1.0f -> " (Standard)"
            else -> ""
        }
        SelectionOption(value = speed, label = "${formatSpeedLabel(speed)}$suffix")
    }
    val selected = SupportedPlaybackSpeeds.minByOrNull { abs(it - currentSpeed) } ?: 1.0f
    PodcastyBottomSheet(
        title = {
            Text(
                stringResource(R.string.player_speed_title),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textMain,
            )
        },
        onDismiss = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            RadioList(
                options = options,
                selectedValue = selected,
                onValueSelected = { speed ->
                    onSpeedSelected(speed)
                    onDismiss()
                },
            )
        }
    }
}

@Composable
internal fun SleepTimerBottomSheet(
    sleepTimer: SleepTimerState,
    defaultFadeOutSeconds: Int = 30,
    onConfigureTimer: (SleepTimerRequest) -> Unit,
    onFadeOutChanged: (Int) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val colors = LocalPodcastyColors.current
    var fadeSeconds by remember(sleepTimer.fadeOutSeconds, defaultFadeOutSeconds) {
        mutableIntStateOf(if (sleepTimer.isActive) sleepTimer.fadeOutSeconds else defaultFadeOutSeconds)
    }
    var customMinutesText by remember { mutableStateOf("25") }
    var trackCountText by remember { mutableStateOf("3") }
    var clockTimeText by remember { mutableStateOf("23:00") }

    val options = listOf(
        SelectionOption(SleepTimerPreset.Off, labelRes = R.string.player_sleep_timer_off),
        SelectionOption(SleepTimerPreset.FiveMin, label = stringResource(R.string.player_sleep_timer_minutes, 5)),
        SelectionOption(SleepTimerPreset.TenMin, label = stringResource(R.string.player_sleep_timer_minutes, 10)),
        SelectionOption(SleepTimerPreset.FifteenMin, label = stringResource(R.string.player_sleep_timer_minutes, 15)),
        SelectionOption(SleepTimerPreset.TwentyMin, label = stringResource(R.string.player_sleep_timer_minutes, 20)),
        SelectionOption(SleepTimerPreset.ThirtyMin, label = stringResource(R.string.player_sleep_timer_minutes, 30)),
        SelectionOption(SleepTimerPreset.FortyFiveMin, label = stringResource(R.string.player_sleep_timer_minutes, 45)),
        SelectionOption(SleepTimerPreset.SixtyMin, label = stringResource(R.string.player_sleep_timer_minutes, 60)),
        SelectionOption(SleepTimerPreset.EndOfEpisode, labelRes = R.string.player_sleep_timer_end_of_episode),
    )

    PodcastyBottomSheet(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.player_sleep_timer_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textMain,
                )
                val badge = when {
                    sleepTimer.remainingSeconds != null -> formatTimerCountdown(sleepTimer.remainingSeconds)
                    sleepTimer.preset == SleepTimerPreset.AfterTracks && sleepTimer.remainingTracks != null ->
                        "${sleepTimer.remainingTracks} left"
                    sleepTimer.preset == SleepTimerPreset.EndOfEpisode -> "End"
                    else -> null
                }
                badge?.let { text ->
                    Text(
                        text = if (sleepTimer.isFadingOut) "$text · Fading" else text,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.primary,
                    )
                }
            }
        },
        onDismiss = onDismiss,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RadioList(
                options = options,
                selectedValue = sleepTimer.preset,
                onValueSelected = { preset ->
                    onConfigureTimer(SleepTimerRequest(preset = preset, fadeOutSeconds = fadeSeconds))
                    onDismiss()
                },
            )

            ActionListDivider(style = ActionListDividerStyle.FullWidth)

            // Custom Duration
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.sleep_timer_custom_duration),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textMuted,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PodcastyTextField(
                        value = customMinutesText,
                        onValueChange = { text -> customMinutesText = text.filter { it.isDigit() }.take(3) },
                        placeholder = "Minutes",
                        size = PodcastyTextFieldSize.Small,
                        modifier = Modifier.weight(1f),
                    )
                    PodcastyPillButton(
                        label = stringResource(R.string.sleep_timer_set_minutes),
                        onClick = {
                            val mins = customMinutesText.toIntOrNull()?.coerceIn(1, 360) ?: return@PodcastyPillButton
                            onConfigureTimer(
                                SleepTimerRequest(
                                    preset = SleepTimerPreset.CustomDuration,
                                    customMinutes = mins,
                                    fadeOutSeconds = fadeSeconds,
                                ),
                            )
                            onDismiss()
                        },
                        variant = PodcastyPillButtonVariant.Primary,
                    )
                }
            }

            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.sleep_timer_clock_time), style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PodcastyTextField(
                        value = clockTimeText,
                        onValueChange = { clockTimeText = it.filter { c -> c.isDigit() || c == ':' }.take(5) },
                        placeholder = "HH:mm", size = PodcastyTextFieldSize.Small, modifier = Modifier.weight(1f),
                    )
                    PodcastyPillButton(
                        label = stringResource(R.string.sleep_timer_set_clock_time),
                        variant = PodcastyPillButtonVariant.Secondary,
                        onClick = {
                            val deadline = nextSleepClockDeadline(clockTimeText) ?: return@PodcastyPillButton
                            onConfigureTimer(SleepTimerRequest(SleepTimerPreset.ClockTime, fadeOutSeconds = fadeSeconds, deadlineEpochMs = deadline))
                            onDismiss()
                        },
                    )
                }
            }

            // Stop after N tracks / episodes
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.sleep_timer_after_tracks),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textMuted,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PodcastyTextField(
                        value = trackCountText,
                        onValueChange = { text -> trackCountText = text.filter { it.isDigit() }.take(2) },
                        placeholder = "Tracks",
                        size = PodcastyTextFieldSize.Small,
                        modifier = Modifier.weight(1f),
                    )
                    PodcastyPillButton(
                        label = stringResource(R.string.sleep_timer_set_tracks),
                        onClick = {
                            val count = trackCountText.toIntOrNull()?.coerceIn(1, 50) ?: return@PodcastyPillButton
                            onConfigureTimer(
                                SleepTimerRequest(
                                    preset = SleepTimerPreset.AfterTracks,
                                    trackCount = count,
                                    fadeOutSeconds = fadeSeconds,
                                ),
                            )
                            onDismiss()
                        },
                        variant = PodcastyPillButtonVariant.Secondary,
                    )
                }
            }

            ActionListDivider(style = ActionListDividerStyle.FullWidth)

            // Gradual volume fade-out (player volume only, never system volume)
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.sleep_timer_fade_out_title),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textMuted,
                )
                RadioList(
                    options = SupportedFadeOutSeconds.map { sec ->
                        SelectionOption(
                            value = sec,
                            label = if (sec == 0) stringResource(R.string.player_sleep_timer_off)
                            else stringResource(R.string.playback_seconds_value, sec),
                        )
                    },
                    selectedValue = fadeSeconds,
                    onValueSelected = { sec ->
                        fadeSeconds = sec
                        onFadeOutChanged(sec)
                    },
                )
            }
        }
    }
}

/** Interpret wall-clock time in the device timezone, advancing to tomorrow if necessary. */
internal fun nextSleepClockDeadline(text: String, nowMs: Long = System.currentTimeMillis()): Long? {
    val parts = text.split(':')
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull()?.takeIf { it in 0..23 } ?: return null
    val minute = parts[1].toIntOrNull()?.takeIf { it in 0..59 } ?: return null
    return java.util.Calendar.getInstance().apply {
        timeInMillis = nowMs
        set(java.util.Calendar.HOUR_OF_DAY, hour)
        set(java.util.Calendar.MINUTE, minute)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
        if (timeInMillis <= nowMs) add(java.util.Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis
}
