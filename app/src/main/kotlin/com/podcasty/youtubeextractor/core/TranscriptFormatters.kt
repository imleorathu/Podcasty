/*
 * Podcasty — Native Android YouTube Caption Extractor Core
 * Ported from "caption extractor android" (devhims/youtube-caption-extractor v1.10.2 port, MIT License).
 */
package com.podcasty.youtubeextractor.core

object TranscriptFormatters {
    fun toSrt(subtitles: List<Subtitle>): String = subtitles.mapIndexed { index, item ->
        buildString {
            append(index + 1)
            append('\n')
            append(formatTimestamp(item.startSeconds, srt = true))
            append(" --> ")
            append(formatTimestamp(item.endSeconds, srt = true))
            append('\n')
            append(item.text)
            append("\n")
        }
    }.joinToString("\n")

    fun toVtt(subtitles: List<Subtitle>): String = buildString {
        append("WEBVTT\n\n")
        subtitles.forEach { item ->
            append(formatTimestamp(item.startSeconds, srt = false))
            append(" --> ")
            append(formatTimestamp(item.endSeconds, srt = false))
            append('\n')
            append(item.text)
            append("\n\n")
        }
    }

    fun formatClock(seconds: Double): String {
        val total = seconds.coerceAtLeast(0.0).toLong()
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val secs = total % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, secs)
        } else {
            "%02d:%02d".format(minutes, secs)
        }
    }

    private fun formatTimestamp(seconds: Double, srt: Boolean): String {
        val totalMs = (seconds.coerceAtLeast(0.0) * 1000.0).toLong()
        val hours = totalMs / 3_600_000
        val minutes = (totalMs % 3_600_000) / 60_000
        val secs = (totalMs % 60_000) / 1000
        val millis = totalMs % 1000
        val separator = if (srt) ',' else '.'
        return "%02d:%02d:%02d%c%03d".format(hours, minutes, secs, separator, millis)
    }
}
