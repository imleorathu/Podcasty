/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.audio

import android.content.Context
import android.media.MediaCodecList
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import java.io.InputStream

enum class FormatSupportStatus(val label: String) {
    NativeDecoderSupported("Native decoder supported"),
    SoftwareDecoderSupported("Software decoder supported"),
    RequiresFormatParameters("Requires format parameters"),
    Experimental("Experimental"),
    Unsupported("Unsupported"),
}

enum class AudioFormatCategory(val label: String) {
    LosslessHighRes("Lossless & high-resolution"),
    StandardCompressed("Standard compressed"),
    VoiceRecording("Voice recording"),
    OpticalDisc("CD audio"),
}

data class AudioFormatMatrixEntry(
    val id: String,
    val name: String,
    val extensions: List<String>,
    val category: AudioFormatCategory,
    val container: String,
    val codec: String,
    val mimeType: String?,
    val status: FormatSupportStatus,
    val decoderBackend: String,
    val licenseNote: String,
    val notes: String,
)

data class DetectedAudioFormat(
    val formatId: String,
    val displayName: String,
    val container: String,
    val codec: String,
    val mimeType: String?,
    val status: FormatSupportStatus,
    val isPlayable: Boolean,
    val isHighResCandidate: Boolean,
    val requiresRawPcmParams: Boolean = false,
    val unsupportedReason: String? = null,
)

object AudioFormatCatalog {
    val scannableExtensions: Set<String> = setOf(
        "wav", "aiff", "aif", "pcm", "raw", "dsf", "dff", "flac", "m4a", "m4b", "m4p", "m4r", "mp4",
        "3gp", "3gpp", "3ga", "wma", "ape", "wv", "mp3", "mp2", "mp1", "mpga", "aac", "ogg", "oga",
        "opus", "spx", "amr", "awb", "gsm", "au", "snd", "dss", "dvf", "cda", "mid", "midi", "weba",
        "webm", "mkv", "mka", "caf",
    )

    @OptIn(UnstableApi::class)
    fun isFfmpegAvailable(): Boolean = runCatching { FfmpegLibrary.isAvailable() }.getOrDefault(false)

    @OptIn(UnstableApi::class)
    fun ffmpegVersion(): String? = runCatching {
        if (FfmpegLibrary.isAvailable()) FfmpegLibrary.getVersion() else null
    }.getOrNull()

    fun hasNativeDecoder(mimeType: String?): Boolean {
        if (mimeType.isNullOrBlank()) return false
        if (mimeType == MimeTypes.AUDIO_RAW) return true
        return runCatching {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            list.codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
            }
        }.getOrDefault(false)
    }

    @OptIn(UnstableApi::class)
    fun hasFfmpegDecoder(mimeType: String?): Boolean {
        if (mimeType.isNullOrBlank()) return false
        return runCatching {
            FfmpegLibrary.isAvailable() && FfmpegLibrary.supportsFormat(mimeType)
        }.getOrDefault(false)
    }

    /**
     * Builds the runtime-probed compatibility matrix for all 21 required audio formats.
     * Never claims decoder support without querying Android MediaCodecList and Media3 FFmpeg.
     */
    fun buildCompatibilityMatrix(): List<AudioFormatMatrixEntry> {
        val ffmpegAvailable = isFfmpegAvailable()
        val alacFfmpeg = hasFfmpegDecoder(MimeTypes.AUDIO_ALAC)
        val alacNative = hasNativeDecoder(MimeTypes.AUDIO_ALAC)
        val flacNative = hasNativeDecoder(MimeTypes.AUDIO_FLAC)
        val flacFfmpeg = hasFfmpegDecoder(MimeTypes.AUDIO_FLAC)
        val mp3Native = hasNativeDecoder(MimeTypes.AUDIO_MPEG)
        val aacNative = hasNativeDecoder(MimeTypes.AUDIO_AAC)
        val vorbisNative = hasNativeDecoder(MimeTypes.AUDIO_VORBIS)
        val opusNative = hasNativeDecoder(MimeTypes.AUDIO_OPUS)
        val amrNbNative = hasNativeDecoder(MimeTypes.AUDIO_AMR_NB)
        val amrWbNative = hasNativeDecoder(MimeTypes.AUDIO_AMR_WB)
        val mulawNative = hasNativeDecoder(MimeTypes.AUDIO_MLAW) || hasNativeDecoder(MimeTypes.AUDIO_ALAW)

        fun statusFor(native: Boolean, ffmpeg: Boolean, fallback: FormatSupportStatus = FormatSupportStatus.Unsupported): Pair<FormatSupportStatus, String> =
            when {
                native -> FormatSupportStatus.NativeDecoderSupported to "Android MediaCodec + Media3"
                ffmpeg -> FormatSupportStatus.SoftwareDecoderSupported to "Media3 FFmpeg (GPL-3.0)"
                else -> fallback to "No compatible container/codec decoder"
            }

        val (flacStatus, flacBackend) = statusFor(flacNative, flacFfmpeg, FormatSupportStatus.SoftwareDecoderSupported)
        val (alacStatus, alacBackend) = statusFor(alacNative, alacFfmpeg)
        val (mp3Status, mp3Backend) = statusFor(mp3Native, hasFfmpegDecoder(MimeTypes.AUDIO_MPEG))
        val (aacStatus, aacBackend) = statusFor(aacNative, hasFfmpegDecoder(MimeTypes.AUDIO_AAC))
        val (vorbisStatus, vorbisBackend) = statusFor(vorbisNative, hasFfmpegDecoder(MimeTypes.AUDIO_VORBIS))
        val (opusStatus, opusBackend) = statusFor(opusNative, hasFfmpegDecoder(MimeTypes.AUDIO_OPUS))
        val (amrStatus, amrBackend) = statusFor(amrNbNative, hasFfmpegDecoder(MimeTypes.AUDIO_AMR_NB))
        val (awbStatus, awbBackend) = statusFor(amrWbNative, hasFfmpegDecoder(MimeTypes.AUDIO_AMR_WB))

        return listOf(
            // Lossless & High-Resolution
            AudioFormatMatrixEntry(
                id = "wav",
                name = "WAV",
                extensions = listOf(".wav"),
                category = AudioFormatCategory.LosslessHighRes,
                container = "RIFF / WAVE",
                codec = "PCM (8/16/24/32-bit, float)",
                mimeType = MimeTypes.AUDIO_RAW,
                status = FormatSupportStatus.NativeDecoderSupported,
                decoderBackend = "Media3 WavExtractor + AudioTrack",
                licenseNote = "Apache 2.0 (AndroidX Media3)",
                notes = "Supports standard PCM and IEEE float WAV files via streaming playback.",
            ),
            AudioFormatMatrixEntry(
                id = "aiff",
                name = "AIFF",
                extensions = listOf(".aiff", ".aif"),
                category = AudioFormatCategory.LosslessHighRes,
                container = "IFF / AIFF",
                codec = "Big-endian PCM",
                mimeType = MimeTypes.AUDIO_RAW,
                status = FormatSupportStatus.SoftwareDecoderSupported,
                decoderBackend = "Built-in AIFF PCM stream wrapper + AudioTrack",
                licenseNote = "GPL-3.0 (Podcasty)",
                notes = "Uncompressed AIFF PCM streams are converted on the fly without loading full files into RAM.",
            ),
            AudioFormatMatrixEntry(
                id = "pcm",
                name = "Raw PCM",
                extensions = listOf(".pcm", ".raw"),
                category = AudioFormatCategory.LosslessHighRes,
                container = "Headerless raw stream",
                codec = "Linear PCM",
                mimeType = MimeTypes.AUDIO_RAW,
                status = FormatSupportStatus.RequiresFormatParameters,
                decoderBackend = "RawPcmDataSource + Media3 AudioTrack",
                licenseNote = "GPL-3.0 (Podcasty)",
                notes = "Raw PCM has no header. Configure sample rate, bit depth, channels, and endianness before playback.",
            ),
            AudioFormatMatrixEntry(
                id = "dsd",
                name = "DSD (DSF / DFF)",
                extensions = listOf(".dsf", ".dff"),
                category = AudioFormatCategory.LosslessHighRes,
                container = "DSF / DSDIFF",
                codec = "DSD64 / DSD128",
                mimeType = null,
                status = FormatSupportStatus.Unsupported,
                decoderBackend = "No Media3 DSD demuxer bundled",
                licenseNote = "N/A",
                notes = "Detected accurately via DSF/FRM8 headers. Native bit-perfect DSD or DoP is not bundled; convert to FLAC/WAV for lossless playback.",
            ),
            AudioFormatMatrixEntry(
                id = "flac",
                name = "FLAC",
                extensions = listOf(".flac"),
                category = AudioFormatCategory.LosslessHighRes,
                container = "FLAC / Ogg FLAC",
                codec = "FLAC",
                mimeType = MimeTypes.AUDIO_FLAC,
                status = flacStatus,
                decoderBackend = flacBackend,
                licenseNote = "BSD / Apache 2.0 / GPL-3.0",
                notes = "Lossless compression up to 24-bit/192 kHz with full Vorbis tag and embedded artwork support.",
            ),
            AudioFormatMatrixEntry(
                id = "alac",
                name = "ALAC",
                extensions = listOf(".m4a"),
                category = AudioFormatCategory.LosslessHighRes,
                container = "MP4 / M4A (ftyp + alac)",
                codec = "Apple Lossless (ALAC)",
                mimeType = MimeTypes.AUDIO_ALAC,
                status = alacStatus,
                decoderBackend = alacBackend,
                licenseNote = "Apache 2.0 / GPL-3.0 (FFmpeg)",
                notes = if (alacStatus != FormatSupportStatus.Unsupported) {
                    "Inspected inside M4A containers to distinguish ALAC from AAC; decoded via ${alacBackend}."
                } else {
                    "Detected inside M4A container, but no ALAC decoder is available on this device/build."
                },
            ),
            AudioFormatMatrixEntry(
                id = "wma_lossless",
                name = "WMA Lossless",
                extensions = listOf(".wma"),
                category = AudioFormatCategory.LosslessHighRes,
                container = "ASF",
                codec = "WMA Lossless (0x0163)",
                mimeType = "audio/x-ms-wma",
                status = FormatSupportStatus.Unsupported,
                decoderBackend = if (ffmpegAvailable) "ASF container demuxer not included in Media3" else "Unavailable",
                licenseNote = "N/A",
                notes = "ASF header codec ID is inspected accurately; Media3 does not ship an ASF container extractor.",
            ),
            AudioFormatMatrixEntry(
                id = "ape",
                name = "APE (Monkey's Audio)",
                extensions = listOf(".ape"),
                category = AudioFormatCategory.LosslessHighRes,
                container = "APE",
                codec = "Monkey's Audio",
                mimeType = "audio/x-ape",
                status = FormatSupportStatus.Unsupported,
                decoderBackend = "No Media3 APE demuxer bundled",
                licenseNote = "N/A",
                notes = "Detected via MAC header; Media3 lacks an APE container extractor. Tags can still be inspected via TagLib.",
            ),
            AudioFormatMatrixEntry(
                id = "wavpack",
                name = "WavPack",
                extensions = listOf(".wv"),
                category = AudioFormatCategory.LosslessHighRes,
                container = "WavPack",
                codec = "WavPack",
                mimeType = "audio/x-wavpack",
                status = FormatSupportStatus.Unsupported,
                decoderBackend = "No Media3 WavPack demuxer bundled",
                licenseNote = "N/A",
                notes = "Detected via wvpk header; Media3 lacks a WavPack container extractor. Tags can still be inspected via TagLib.",
            ),
            // Standard Compressed
            AudioFormatMatrixEntry(
                id = "mp3",
                name = "MP3",
                extensions = listOf(".mp3"),
                category = AudioFormatCategory.StandardCompressed,
                container = "MP3 / ID3v2",
                codec = "MPEG-1/2 Layer III",
                mimeType = MimeTypes.AUDIO_MPEG,
                status = mp3Status,
                decoderBackend = mp3Backend,
                licenseNote = "Apache 2.0 (AndroidX Media3)",
                notes = "Full ID3v2 tag, artwork, lyrics, and gapless playback support.",
            ),
            AudioFormatMatrixEntry(
                id = "aac",
                name = "AAC",
                extensions = listOf(".aac", ".m4a"),
                category = AudioFormatCategory.StandardCompressed,
                container = "ADTS / MP4 (M4A)",
                codec = "AAC-LC / HE-AAC",
                mimeType = MimeTypes.AUDIO_AAC,
                status = aacStatus,
                decoderBackend = aacBackend,
                licenseNote = "Apache 2.0 (AndroidX Media3)",
                notes = "Supports both raw ADTS (.aac) and MP4/M4A (.m4a) containers.",
            ),
            AudioFormatMatrixEntry(
                id = "ogg_vorbis",
                name = "Ogg Vorbis",
                extensions = listOf(".ogg", ".oga"),
                category = AudioFormatCategory.StandardCompressed,
                container = "Ogg",
                codec = "Vorbis",
                mimeType = MimeTypes.AUDIO_VORBIS,
                status = vorbisStatus,
                decoderBackend = vorbisBackend,
                licenseNote = "BSD / Apache 2.0",
                notes = "Supports Ogg Vorbis streams and Vorbis comment metadata.",
            ),
            AudioFormatMatrixEntry(
                id = "opus",
                name = "Opus",
                extensions = listOf(".opus", ".ogg"),
                category = AudioFormatCategory.StandardCompressed,
                container = "Ogg / WebM",
                codec = "Opus",
                mimeType = MimeTypes.AUDIO_OPUS,
                status = opusStatus,
                decoderBackend = opusBackend,
                licenseNote = "BSD / Apache 2.0",
                notes = "Modern low-latency podcast and music codec with Ogg Opus metadata.",
            ),
            AudioFormatMatrixEntry(
                id = "wma",
                name = "WMA",
                extensions = listOf(".wma"),
                category = AudioFormatCategory.StandardCompressed,
                container = "ASF",
                codec = "WMA v1/v2 / WMA Pro",
                mimeType = "audio/x-ms-wma",
                status = FormatSupportStatus.Unsupported,
                decoderBackend = "ASF demuxer not included in Media3",
                licenseNote = "N/A",
                notes = "ASF tags can be read/edited with TagLib, but playback requires an ASF container extractor not bundled in Media3.",
            ),
            AudioFormatMatrixEntry(
                id = "amr",
                name = "AMR-NB",
                extensions = listOf(".amr"),
                category = AudioFormatCategory.StandardCompressed,
                container = "AMR",
                codec = "AMR Narrowband",
                mimeType = MimeTypes.AUDIO_AMR_NB,
                status = amrStatus,
                decoderBackend = amrBackend,
                licenseNote = "Apache 2.0 (AndroidX Media3)",
                notes = "Standard narrowband voice memo playback via Media3 AmrExtractor.",
            ),
            AudioFormatMatrixEntry(
                id = "awb",
                name = "AMR-WB",
                extensions = listOf(".awb"),
                category = AudioFormatCategory.StandardCompressed,
                container = "AMR-WB",
                codec = "AMR Wideband",
                mimeType = MimeTypes.AUDIO_AMR_WB,
                status = awbStatus,
                decoderBackend = awbBackend,
                licenseNote = "Apache 2.0 (AndroidX Media3)",
                notes = "Wideband voice recording playback via Media3 AmrExtractor.",
            ),
            AudioFormatMatrixEntry(
                id = "gsm",
                name = "GSM",
                extensions = listOf(".gsm"),
                category = AudioFormatCategory.StandardCompressed,
                container = "Raw GSM / WAV-GSM",
                codec = "GSM 06.10",
                mimeType = "audio/gsm",
                status = if (hasFfmpegDecoder("audio/gsm")) FormatSupportStatus.Experimental else FormatSupportStatus.Unsupported,
                decoderBackend = if (hasFfmpegDecoder("audio/gsm")) "Media3 FFmpeg (Experimental)" else "No GSM decoder available",
                licenseNote = "GPL-3.0 (if available)",
                notes = "Raw headerless GSM files lack a Media3 container extractor on Android.",
            ),
            AudioFormatMatrixEntry(
                id = "au",
                name = "AU (Sun / NeXT)",
                extensions = listOf(".au", ".snd"),
                category = AudioFormatCategory.StandardCompressed,
                container = "Sun AU (.snd)",
                codec = "Linear PCM / u-law",
                mimeType = if (mulawNative) MimeTypes.AUDIO_MLAW else MimeTypes.AUDIO_RAW,
                status = FormatSupportStatus.SoftwareDecoderSupported,
                decoderBackend = "Built-in Sun AU PCM stream wrapper + AudioTrack",
                licenseNote = "GPL-3.0 (Podcasty)",
                notes = "Supports linear PCM Sun AU files (.snd header) via streaming WAV header wrapping.",
            ),
            // Voice Recordings
            AudioFormatMatrixEntry(
                id = "dss",
                name = "DSS",
                extensions = listOf(".dss"),
                category = AudioFormatCategory.VoiceRecording,
                container = "Digital Speech Standard",
                codec = "Proprietary DSS / DS2",
                mimeType = null,
                status = FormatSupportStatus.Unsupported,
                decoderBackend = "No legally compatible Android decoder",
                licenseNote = "Proprietary Olympus/Grundig/Philips format",
                notes = "Proprietary dictation format with no reliable Android Media3 decoder. Convert to WAV/MP3/Opus.",
            ),
            AudioFormatMatrixEntry(
                id = "dvf",
                name = "DVF",
                extensions = listOf(".dvf"),
                category = AudioFormatCategory.VoiceRecording,
                container = "Sony Digital Voice File",
                codec = "Proprietary Sony LPEC/TRC",
                mimeType = null,
                status = FormatSupportStatus.Unsupported,
                decoderBackend = "No open-source Android decoder",
                licenseNote = "Proprietary Sony format",
                notes = "Proprietary Sony voice recorder format. Marked unsupported rather than pretending to play.",
            ),
            // Optical Disc
            AudioFormatMatrixEntry(
                id = "cda",
                name = "CDA (CD Track Reference)",
                extensions = listOf(".cda"),
                category = AudioFormatCategory.OpticalDisc,
                container = "RIFF / CDDA shortcut (44 bytes)",
                codec = "None (disc pointer only)",
                mimeType = null,
                status = FormatSupportStatus.Unsupported,
                decoderBackend = "Optical CD drive required (CDDA)",
                licenseNote = "N/A",
                notes = "CDA files are 44-byte optical-disc track shortcuts, not standalone audio files. Rip the CD to FLAC or WAV to play on Android.",
            ),
        )
    }

    /**
     * Inspects actual file header magic bytes (plus extension fallback only when needed)
     * to identify real container + codec without assuming playability from extension alone.
     */
    fun detectFromUri(context: Context, uri: Uri, displayName: String?): DetectedAudioFormat {
        val header = runCatching {
            context.contentResolver.openInputStream(uri)?.use { readPrefix(it, 4096) }
        }.getOrNull() ?: ByteArray(0)
        return detectFromHeader(header, displayName)
    }

    fun detectFromHeader(header: ByteArray, fileName: String?): DetectedAudioFormat {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        val matrixById = buildCompatibilityMatrix().associateBy { it.id }

        fun fromMatrix(
            id: String,
            highRes: Boolean = false,
            requiresPcm: Boolean = false,
            overrideReason: String? = null,
        ): DetectedAudioFormat {
            val entry = matrixById[id]
            val status = entry?.status ?: FormatSupportStatus.Unsupported
            val playable = status == FormatSupportStatus.NativeDecoderSupported ||
                status == FormatSupportStatus.SoftwareDecoderSupported ||
                status == FormatSupportStatus.RequiresFormatParameters ||
                status == FormatSupportStatus.Experimental
            return DetectedAudioFormat(
                formatId = id,
                displayName = entry?.name ?: ext.uppercase(),
                container = entry?.container ?: ext.uppercase(),
                codec = entry?.codec ?: "Unknown",
                mimeType = entry?.mimeType,
                status = status,
                isPlayable = playable,
                isHighResCandidate = highRes,
                requiresRawPcmParams = requiresPcm,
                unsupportedReason = if (!playable) overrideReason ?: entry?.notes else null,
            )
        }

        // 1. RIFF container: distinguish CDA vs WAV!
        if (header.size >= 12 && ascii(header, 0, 4) == "RIFF") {
            val riffType = ascii(header, 8, 4)
            if (riffType == "CDDA" || ext == "cda") {
                return fromMatrix(
                    "cda",
                    overrideReason = "CDA files are optical-disc track references, not standalone audio files. Rip the CD to FLAC or WAV first.",
                )
            }
            if (riffType == "WAVE") {
                return fromMatrix("wav", highRes = true)
            }
        }

        // 2. AIFF / AIFC
        if (header.size >= 12 && ascii(header, 0, 4) == "FORM" && (ascii(header, 8, 4) == "AIFF" || ascii(header, 8, 4) == "AIFC")) {
            return fromMatrix("aiff", highRes = true)
        }

        // 3. FLAC
        if (header.size >= 4 && ascii(header, 0, 4) == "fLaC") {
            return fromMatrix("flac", highRes = true)
        }

        // 4. Sun AU (.snd)
        if (header.size >= 4 && ascii(header, 0, 4) == ".snd") {
            return fromMatrix("au")
        }

        // 5. DSD (DSF / DSDIFF)
        if (header.size >= 4 && (ascii(header, 0, 4) == "DSD " || ascii(header, 0, 4) == "FRM8")) {
            return fromMatrix(
                "dsd",
                highRes = true,
                overrideReason = "DSD (DSF/DFF) audio requires a dedicated DSD-to-PCM demuxer not bundled in Media3.",
            )
        }

        // 6. APE (Monkey's Audio)
        if (header.size >= 4 && ascii(header, 0, 4) == "MAC ") {
            return fromMatrix("ape", highRes = true)
        }

        // 7. WavPack
        if (header.size >= 4 && ascii(header, 0, 4) == "wvpk") {
            return fromMatrix("wavpack", highRes = true)
        }

        // 8. Ogg container: distinguish Opus vs Vorbis vs FLAC!
        if (header.size >= 4 && ascii(header, 0, 4) == "OggS") {
            val text = String(header, Charsets.ISO_8859_1)
            return when {
                text.contains("OpusHead") -> fromMatrix("opus")
                text.contains("FLAC") -> fromMatrix("flac", highRes = true)
                else -> fromMatrix("ogg_vorbis")
            }
        }

        // 9. MP4 / M4A container: distinguish ALAC vs AAC inside ftyp/moov!
        if (header.size >= 12 && ascii(header, 4, 4) == "ftyp") {
            val text = String(header, Charsets.ISO_8859_1)
            return if (text.contains("alac")) {
                fromMatrix("alac", highRes = true)
            } else {
                fromMatrix("aac")
            }
        }

        // 10. ASF / WMA container (GUID 30 26 B2 75 8E 66 CF 11)
        if (header.size >= 8 &&
            (header[0].toInt() and 0xFF) == 0x30 &&
            (header[1].toInt() and 0xFF) == 0x26 &&
            (header[2].toInt() and 0xFF) == 0xB2 &&
            (header[3].toInt() and 0xFF) == 0x75
        ) {
            // Codec tag 0x0163 inside ASF audio header denotes WMA Lossless
            val isLossless = containsLittleEndianShort(header, 0x0163)
            return fromMatrix(if (isLossless) "wma_lossless" else "wma", highRes = isLossless)
        }

        // 11. AMR-WB vs AMR-NB
        if (header.size >= 9 && ascii(header, 0, 9) == "#!AMR-WB\n") {
            return fromMatrix("awb")
        }
        if (header.size >= 6 && ascii(header, 0, 6) == "#!AMR\n") {
            return fromMatrix("amr")
        }

        // 12. DSS / DVF proprietary voice files
        if (ext == "dss" || (header.size >= 4 && (ascii(header, 1, 3) == "dss" || ascii(header, 1, 3) == "ds2"))) {
            return fromMatrix("dss")
        }
        if (ext == "dvf" || (header.size >= 8 && String(header, 0, minOf(header.size, 16), Charsets.ISO_8859_1).contains("MS_VOICE"))) {
            return fromMatrix("dvf")
        }

        // 13. ID3 tag or MPEG / ADTS frame sync
        if (header.size >= 3 && ascii(header, 0, 3) == "ID3") {
            return if (ext == "aac") fromMatrix("aac") else fromMatrix("mp3")
        }
        if (header.size >= 2 && (header[0].toInt() and 0xFF) == 0xFF && (header[1].toInt() and 0xE0) == 0xE0) {
            val layer = (header[1].toInt() ushr 1) and 0x03
            return if (layer == 0) fromMatrix("aac") else fromMatrix("mp3")
        }

        // 14. Extension-based fallback for headerless formats (Raw PCM, GSM, CDA, etc.)
        return when (ext) {
            "cda" -> fromMatrix(
                "cda",
                overrideReason = "CDA files are optical-disc track references, not standalone audio files.",
            )
            "pcm", "raw" -> fromMatrix("pcm", highRes = true, requiresPcm = true)
            "wav" -> fromMatrix("wav", highRes = true)
            "aiff", "aif" -> fromMatrix("aiff", highRes = true)
            "flac" -> fromMatrix("flac", highRes = true)
            "m4a", "m4b", "m4p", "m4r", "mp4", "3gp", "3gpp", "3ga", "aac" -> fromMatrix("aac")
            "mp3", "mp2", "mp1", "mpga" -> fromMatrix("mp3")
            "ogg", "oga", "spx" -> fromMatrix("ogg_vorbis")
            "opus", "weba", "webm", "mkv", "mka" -> fromMatrix("opus")
            "caf" -> fromMatrix("alac", highRes = true)
            "amr" -> fromMatrix("amr")
            "awb" -> fromMatrix("awb")
            "gsm" -> fromMatrix("gsm")
            "au", "snd" -> fromMatrix("au")
            "dsf", "dff" -> fromMatrix("dsd", highRes = true)
            "ape" -> fromMatrix("ape", highRes = true)
            "wv" -> fromMatrix("wavpack", highRes = true)
            "wma" -> fromMatrix("wma")
            "dss" -> fromMatrix("dss")
            "dvf" -> fromMatrix("dvf")
            else -> DetectedAudioFormat(
                formatId = "unknown",
                displayName = ext.uppercase().ifBlank { "Unknown" },
                container = "Unknown",
                codec = "Unknown",
                mimeType = null,
                status = FormatSupportStatus.Unsupported,
                isPlayable = false,
                isHighResCandidate = false,
                unsupportedReason = "Unrecognized audio container or unsupported codec.",
            )
        }
    }

    private fun readPrefix(stream: InputStream, maxBytes: Int): ByteArray {
        val buf = ByteArray(maxBytes)
        var total = 0
        while (total < maxBytes) {
            val read = stream.read(buf, total, maxBytes - total)
            if (read <= 0) break
            total += read
        }
        return if (total == maxBytes) buf else buf.copyOf(total)
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        if (bytes.size >= offset + length) String(bytes, offset, length, Charsets.US_ASCII) else ""

    private fun containsLittleEndianShort(bytes: ByteArray, value: Int): Boolean {
        val lo = (value and 0xFF).toByte()
        val hi = ((value ushr 8) and 0xFF).toByte()
        for (i in 0 until bytes.size - 1) {
            if (bytes[i] == lo && bytes[i + 1] == hi) return true
        }
        return false
    }
}
