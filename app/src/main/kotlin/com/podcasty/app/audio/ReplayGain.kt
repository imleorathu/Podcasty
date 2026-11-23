package com.podcasty.app.audio

import kotlin.math.log10
import kotlin.math.min

/** Uses one tagged loudness system at a time; unknown/invalid tags leave gain at unity. */
object ReplayGain {
    fun gainDb(properties: Map<String, Array<String>>, album: Boolean): Float {
        fun tag(name: String): Float? = properties.entries.firstOrNull { it.key.equals(name, true) }
            ?.value?.firstOrNull()?.trim()?.substringBefore(' ')?.replace(',', '.')?.toFloatOrNull()?.takeIf { it.isFinite() }
        val useAlbum = album && tag("REPLAYGAIN_ALBUM_GAIN") != null
        val prefix = if (useAlbum) "REPLAYGAIN_ALBUM" else "REPLAYGAIN_TRACK"
        var gain = tag("${prefix}_GAIN") ?: return 0f
        val peak = tag("${prefix}_PEAK")
        if (peak != null && peak > 0f) gain = min(gain, (20f * log10(1f / peak)))
        return gain.coerceIn(-15f, 6f)
    }
}
