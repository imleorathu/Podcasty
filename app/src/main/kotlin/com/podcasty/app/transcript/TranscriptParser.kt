/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.transcript

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object TranscriptParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val lrcLineTagRegex = Regex("\\[(\\d{1,3}):([0-5]\\d)(?:[.:](\\d{1,3}))?]")
    private val enhancedLrcInlineTagRegex = Regex("<\\d{1,3}:[0-5]\\d(?:[.:]\\d{1,3})?>")
    private val lrcMetaTagRegex = Regex("^\\[(ti|ar|al|au|by|re|ve|length|offset|lang|la):(.*)]$", RegexOption.IGNORE_CASE)
    private val srtTimeRangeRegex = Regex(
        "^(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3}).*$",
    )
    private val vttTimeRangeRegex = Regex(
        "^(?:(\\d{1,2}):)?(\\d{2}):(\\d{2})\\.(\\d{1,3})\\s*-->\\s*(?:(\\d{1,2}):)?(\\d{2}):(\\d{2})\\.(\\d{1,3}).*$",
    )
    private val vttVoiceTagRegex = Regex("^<v(?:\\.[^\\s>]+)?\\s+([^>]+)>(.*)$", RegexOption.IGNORE_CASE)
    private val htmlTagRegex = Regex("<[^>]+>")

    fun detectFormat(content: String, fileName: String? = null): TranscriptFormat {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        val trimmed = normalizeInputText(content).trim()
        if (ext == "vtt" || trimmed.startsWith("WEBVTT", ignoreCase = true)) return TranscriptFormat.WebVtt
        if (ext == "srt" || srtTimeRangeRegex.containsMatchIn(trimmed.lineSequence().take(12).joinToString("\n"))) {
            return TranscriptFormat.Srt
        }
        if (ext == "json" || (trimmed.startsWith("{") && trimmed.contains("segments"))) {
            return TranscriptFormat.PodcastJson
        }
        if (ext == "lrc" || lrcLineTagRegex.containsMatchIn(trimmed.lineSequence().take(20).joinToString("\n"))) {
            return if (enhancedLrcInlineTagRegex.containsMatchIn(trimmed)) {
                TranscriptFormat.EnhancedLrc
            } else {
                TranscriptFormat.Lrc
            }
        }
        if (vttTimeRangeRegex.containsMatchIn(trimmed.lineSequence().take(12).joinToString("\n"))) {
            return TranscriptFormat.WebVtt
        }
        return TranscriptFormat.PlainText
    }

    /**
     * Decodes raw transcript bytes supporting UTF-8, UTF-16LE, UTF-16BE, and UTF-32 BOMs
     * so Tamil, Sinhala, Indic, Arabic, and CJK files never suffer mojibake when saved from
     * Windows or mobile subtitle editors.
     */
    fun decodeBytesToUnicode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val charset = when {
            bytes.size >= 3 &&
                (bytes[0].toInt() and 0xFF) == 0xEF &&
                (bytes[1].toInt() and 0xFF) == 0xBB &&
                (bytes[2].toInt() and 0xFF) == 0xBF -> Charsets.UTF_8
            bytes.size >= 2 &&
                (bytes[0].toInt() and 0xFF) == 0xFF &&
                (bytes[1].toInt() and 0xFF) == 0xFE -> Charsets.UTF_16LE
            bytes.size >= 2 &&
                (bytes[0].toInt() and 0xFF) == 0xFE &&
                (bytes[1].toInt() and 0xFF) == 0xFF -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return normalizeInputText(String(bytes, charset))
    }

    /**
     * Normalizes full-width and regional punctuation/brackets and strips BOMs while preserving
     * ZWJ (\u200D) and ZWNJ (\u200C) joiners required by Sinhala (සිංහල) and Tamil (தமிழ்) ligatures.
     */
    fun normalizeInputText(raw: String): String =
        raw.trimStart('\uFEFF', '\uFFFE')
            .replace('［', '[')
            .replace('］', ']')
            .replace('：', ':')
            .replace('．', '.')

    /**
     * Auto-detects language script from Unicode code blocks when no explicit `Language:` header
     * or `[lang:...]` tag is present in the lyrics/transcript.
     */
    fun detectScriptLanguage(text: String): String? {
        var tamilCount = 0
        var sinhalaCount = 0
        var devanagariCount = 0
        var malayalamCount = 0
        var teluguCount = 0
        var kannadaCount = 0
        var bengaliCount = 0
        var gujaratiCount = 0
        var gurmukhiCount = 0
        var arabicCount = 0
        var thaiCount = 0
        var cyrillicCount = 0
        var hangulCount = 0
        var kanaCount = 0
        var cjkCount = 0
        var latinCount = 0

        val sampleLimit = minOf(text.length, 4000)
        var i = 0
        while (i < sampleLimit) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when (cp) {
                in 0x0B80..0x0BFF -> tamilCount++
                in 0x0D80..0x0DFF -> sinhalaCount++
                in 0x0900..0x097F -> devanagariCount++
                in 0x0D00..0x0D7F -> malayalamCount++
                in 0x0C00..0x0C7F -> teluguCount++
                in 0x0C80..0x0CFF -> kannadaCount++
                in 0x0980..0x09FF -> bengaliCount++
                in 0x0A80..0x0AFF -> gujaratiCount++
                in 0x0A00..0x0A7F -> gurmukhiCount++
                in 0x0600..0x06FF, in 0x0750..0x077F -> arabicCount++
                in 0x0E00..0x0E7F -> thaiCount++
                in 0x0400..0x04FF -> cyrillicCount++
                in 0xAC00..0xD7AF, in 0x1100..0x11FF -> hangulCount++
                in 0x3040..0x30FF -> kanaCount++
                in 0x4E00..0x9FFF -> cjkCount++
                in 0x0041..0x005A, in 0x0061..0x007A, in 0x00C0..0x024F -> latinCount++
            }
        }
        val scriptCounts = listOf(
            "ta" to tamilCount,
            "si" to sinhalaCount,
            "hi" to devanagariCount,
            "ml" to malayalamCount,
            "te" to teluguCount,
            "kn" to kannadaCount,
            "bn" to bengaliCount,
            "gu" to gujaratiCount,
            "pa" to gurmukhiCount,
            "ar" to arabicCount,
            "th" to thaiCount,
            "ru" to cyrillicCount,
            "ko" to hangulCount,
            "ja" to (kanaCount * 2 + if (kanaCount > 0) cjkCount else 0),
            "zh" to if (kanaCount == 0) cjkCount else 0,
        )
        val bestNonLatin = scriptCounts.maxByOrNull { it.second }
        if (bestNonLatin != null && bestNonLatin.second >= 3) {
            return bestNonLatin.first
        }
        return if (latinCount >= 3) "en" else null
    }

    fun parse(
        episodeId: String,
        content: String,
        fileName: String? = null,
        language: String = "en",
        source: TranscriptSource = TranscriptSource.AuthorizedImport,
    ): TranscriptDocument {
        val cleanContent = normalizeInputText(content)
        if (cleanContent.isBlank()) {
            return TranscriptDocument(episodeId = episodeId, language = language, source = source)
        }
        val format = detectFormat(cleanContent, fileName)
        val cues = when (format) {
            TranscriptFormat.Lrc, TranscriptFormat.EnhancedLrc -> parseLrc(cleanContent)
            TranscriptFormat.Srt -> parseSrt(cleanContent)
            TranscriptFormat.WebVtt -> parseWebVtt(cleanContent)
            TranscriptFormat.PodcastJson -> parsePodcastJson(cleanContent)
            TranscriptFormat.PlainText -> parsePlainText(cleanContent)
        }
        val bodySample = cues.take(20).joinToString(" ") { it.text }
        val resolvedLang = extractLanguageHint(cleanContent)
            ?: detectScriptLanguage(bodySample)
            ?: language.takeIf { !it.equals("auto", ignoreCase = true) && it.isNotBlank() }
            ?: "en"
        return TranscriptDocument(
            episodeId = episodeId,
            language = resolvedLang,
            format = format,
            source = source,
            cues = cues,
        )
    }

    private fun extractLanguageHint(content: String): String? {
        for (line in content.lineSequence().take(15)) {
            val trimmed = line.trim()
            if (trimmed.startsWith("Language:", ignoreCase = true)) {
                return trimmed.substringAfter(':').trim().takeIf { it.length in 2..10 }
            }
            val meta = lrcMetaTagRegex.matchEntire(trimmed)
            if (meta != null && meta.groupValues[1].lowercase() in setOf("lang", "la")) {
                return meta.groupValues[2].trim().takeIf { it.length in 2..10 }
            }
        }
        return null
    }

    fun parseLrc(content: String): List<TranscriptCue> {
        var offsetMs = 0L
        data class RawEntry(val startMs: Long, val speaker: String?, val text: String)
        val raw = mutableListOf<RawEntry>()

        for (rawLine in content.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val meta = lrcMetaTagRegex.matchEntire(line)
            if (meta != null) {
                if (meta.groupValues[1].equals("offset", ignoreCase = true)) {
                    offsetMs = meta.groupValues[2].trim().toLongOrNull() ?: 0L
                }
                continue
            }
            val matches = lrcLineTagRegex.findAll(line).toList()
            if (matches.isEmpty()) continue
            val afterLastTag = line.substring(matches.last().range.last + 1)
            val cleanedBody = enhancedLrcInlineTagRegex.replace(afterLastTag, "").replace(Regex("\\s+"), " ").trim()
            if (cleanedBody.isEmpty()) continue
            val (speaker, text) = splitSpeakerPrefix(cleanedBody)
            for (match in matches) {
                val minutes = match.groupValues[1].toLongOrNull() ?: 0L
                val seconds = match.groupValues[2].toLongOrNull() ?: 0L
                val fracRaw = match.groupValues[3]
                val millis = parseFractionToMs(fracRaw)
                val startMs = (minutes * 60_000L + seconds * 1_000L + millis - offsetMs).coerceAtLeast(0L)
                raw += RawEntry(startMs = startMs, speaker = speaker, text = text)
            }
        }
        if (raw.isEmpty()) return parsePlainText(content)
        val sorted = raw.sortedBy { it.startMs }
        // Pair bilingual/translation LRC lines that share the same timestamp (<=50ms apart)
        // instead of dropping the first line during binary search.
        data class MergedEntry(val startMs: Long, val speaker: String?, val text: String, val translation: String?)
        val merged = mutableListOf<MergedEntry>()
        for (entry in sorted) {
            val prev = merged.lastOrNull()
            if (prev != null && kotlin.math.abs(entry.startMs - prev.startMs) <= 50L &&
                prev.translation == null && !prev.text.equals(entry.text, ignoreCase = false)
            ) {
                merged[merged.lastIndex] = prev.copy(translation = entry.text)
            } else {
                merged += MergedEntry(
                    startMs = entry.startMs,
                    speaker = entry.speaker,
                    text = entry.text,
                    translation = null,
                )
            }
        }
        return merged.mapIndexed { idx, entry ->
            val nextStart = merged.getOrNull(idx + 1)?.startMs
            val endMs = if (nextStart != null && nextStart > entry.startMs) nextStart else entry.startMs + 6_000L
            TranscriptCue(
                id = idx.toLong() + 1L,
                startMs = entry.startMs,
                endMs = endMs,
                text = entry.text,
                speaker = entry.speaker,
                translation = entry.translation,
            )
        }
    }

    fun parseSrt(content: String): List<TranscriptCue> {
        val cues = mutableListOf<TranscriptCue>()
        val normalized = content.replace("\r\n", "\n").replace("\r", "\n")
        val blocks = normalized.split(Regex("\n\\s*\n"))
        var nextId = 1L
        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.size < 2) continue
            val timeLineIndex = lines.indexOfFirst { srtTimeRangeRegex.matches(it) }
            if (timeLineIndex < 0) continue
            val match = srtTimeRangeRegex.matchEntire(lines[timeLineIndex]) ?: continue
            val startMs = toMillis(
                match.groupValues[1].toLong(),
                match.groupValues[2].toLong(),
                match.groupValues[3].toLong(),
                parseFractionToMs(match.groupValues[4]),
            )
            val endMs = toMillis(
                match.groupValues[5].toLong(),
                match.groupValues[6].toLong(),
                match.groupValues[7].toLong(),
                parseFractionToMs(match.groupValues[8]),
            ).coerceAtLeast(startMs + 500L)
            val body = lines.drop(timeLineIndex + 1)
                .joinToString(" ")
                .let { htmlTagRegex.replace(it, "") }
                .replace(Regex("\\s+"), " ")
                .trim()
            if (body.isEmpty()) continue
            val (speaker, text) = splitSpeakerPrefix(body)
            cues += TranscriptCue(
                id = nextId++,
                startMs = startMs,
                endMs = endMs,
                text = text,
                speaker = speaker,
            )
        }
        return cues.sortedBy { it.startMs }
    }

    fun parseWebVtt(content: String): List<TranscriptCue> {
        val cues = mutableListOf<TranscriptCue>()
        val normalized = content.replace("\r\n", "\n").replace("\r", "\n")
        val blocks = normalized.split(Regex("\n\\s*\n"))
        var nextId = 1L
        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            val first = lines.first()
            if (first.startsWith("WEBVTT", ignoreCase = true) ||
                first.startsWith("NOTE", ignoreCase = true) ||
                first.startsWith("STYLE", ignoreCase = true) ||
                first.startsWith("REGION", ignoreCase = true)
            ) {
                continue
            }
            val timeLineIndex = lines.indexOfFirst { vttTimeRangeRegex.matches(it) }
            if (timeLineIndex < 0) continue
            val match = vttTimeRangeRegex.matchEntire(lines[timeLineIndex]) ?: continue
            val startH = match.groupValues[1].toLongOrNull() ?: 0L
            val startM = match.groupValues[2].toLong()
            val startS = match.groupValues[3].toLong()
            val startMsFrac = parseFractionToMs(match.groupValues[4])
            val endH = match.groupValues[5].toLongOrNull() ?: 0L
            val endM = match.groupValues[6].toLong()
            val endS = match.groupValues[7].toLong()
            val endMsFrac = parseFractionToMs(match.groupValues[8])
            val startMs = toMillis(startH, startM, startS, startMsFrac)
            val endMs = toMillis(endH, endM, endS, endMsFrac).coerceAtLeast(startMs + 500L)

            val rawBody = lines.drop(timeLineIndex + 1).joinToString(" ").trim()
            if (rawBody.isEmpty()) continue
            val voiceMatch = vttVoiceTagRegex.matchEntire(rawBody)
            val (speaker, text) = if (voiceMatch != null) {
                val spk = voiceMatch.groupValues[1].trim()
                val cleaned = htmlTagRegex.replace(voiceMatch.groupValues[2], "").replace(Regex("\\s+"), " ").trim()
                spk to cleaned
            } else {
                val cleaned = htmlTagRegex.replace(rawBody, "").replace(Regex("\\s+"), " ").trim()
                splitSpeakerPrefix(cleaned)
            }
            if (text.isEmpty()) continue
            cues += TranscriptCue(
                id = nextId++,
                startMs = startMs,
                endMs = endMs,
                text = text,
                speaker = speaker,
            )
        }
        return cues.sortedBy { it.startMs }
    }

    fun parsePodcastJson(content: String): List<TranscriptCue> = runCatching {
        val root = json.parseToJsonElement(content)
        val segments: JsonArray = when (root) {
            is JsonObject -> root["segments"]?.jsonArray ?: root["cues"]?.jsonArray ?: return emptyList()
            is JsonArray -> root
            else -> return emptyList()
        }
        var nextId = 1L
        segments.mapNotNull { elem ->
            val obj = elem.jsonObject
            val text = (obj["body"] ?: obj["text"])?.jsonPrimitive?.content?.trim().orEmpty()
            if (text.isEmpty()) return@mapNotNull null
            val startSec = (obj["startTime"] ?: obj["start"])?.jsonPrimitive?.doubleOrNull ?: 0.0
            val endSec = (obj["endTime"] ?: obj["end"])?.jsonPrimitive?.doubleOrNull ?: (startSec + 5.0)
            val speaker = obj["speaker"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
            val startMs = (startSec * 1_000.0).toLong().coerceAtLeast(0L)
            val endMs = (endSec * 1_000.0).toLong().coerceAtLeast(startMs + 500L)
            TranscriptCue(
                id = nextId++,
                startMs = startMs,
                endMs = endMs,
                text = text,
                speaker = speaker,
            )
        }.sortedBy { it.startMs }
    }.getOrDefault(emptyList())

    fun parsePlainText(content: String): List<TranscriptCue> {
        var nextId = 1L
        return content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                val (speaker, text) = splitSpeakerPrefix(line)
                TranscriptCue(
                    id = nextId++,
                    startMs = -1L,
                    endMs = -1L,
                    text = text,
                    speaker = speaker,
                )
            }
            .toList()
    }

    fun toLrcString(cues: List<TranscriptCue>): String = buildString {
        cues.forEach { cue ->
            val stamp = if (cue.startMs >= 0L) {
                val mins = cue.startMs / 60_000L
                val secs = (cue.startMs % 60_000L) / 1_000L
                val centis = (cue.startMs % 1_000L) / 10L
                "[%02d:%02d.%02d]".format(mins, secs, centis)
            } else {
                ""
            }
            append(stamp)
            if (!cue.speaker.isNullOrBlank()) {
                append("${cue.speaker}: ")
            }
            appendLine(cue.text)
            if (!cue.translation.isNullOrBlank()) {
                append(stamp)
                appendLine(cue.translation)
            }
        }
    }.trimEnd()

    private fun splitSpeakerPrefix(body: String): Pair<String?, String> {
        val colonIdx = body.indexOf(':')
        return if (colonIdx in 1..28 && !body.substring(0, colonIdx).contains("http", ignoreCase = true)) {
            body.substring(0, colonIdx).trim() to body.substring(colonIdx + 1).trim()
        } else {
            null to body
        }
    }

    private fun parseFractionToMs(raw: String): Long {
        if (raw.isEmpty()) return 0L
        return when (raw.length) {
            1 -> raw.toLong() * 100L
            2 -> raw.toLong() * 10L
            else -> raw.take(3).toLong()
        }
    }

    private fun toMillis(hours: Long, minutes: Long, seconds: Long, millis: Long): Long =
        hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + millis
}
