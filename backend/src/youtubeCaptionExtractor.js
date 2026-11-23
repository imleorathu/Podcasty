/*
 * Podcasty Speech Backend — YouTube Caption Extraction via youtube-caption-extractor
 *
 * Uses https://github.com/devhims/youtube-caption-extractor (`getVideoDetails` / `getSubtitles`)
 * to extract timestamped public YouTube captions and normalize them into strictly sorted,
 * non-overlapping millisecond cues for Podcasty's CaptionSyncEngine.
 */
import { getSubtitles, getVideoDetails } from "youtube-caption-extractor";

const VIDEO_ID_REGEX = /^[A-Za-z0-9_-]{11}$/;

export function isValidYouTubeVideoId(videoId) {
  return typeof videoId === "string" && VIDEO_ID_REGEX.test(videoId.trim());
}

/**
 * Normalizes BCP-47 tags like `en-US` -> `en`, `ta-IN` -> `ta`, `en-GB` -> `en`
 * for `youtube-caption-extractor`'s `lang` parameter.
 */
export function normalizeCaptionLanguage(rawLang) {
  const cleaned = String(rawLang || "en").trim().toLowerCase();
  if (!cleaned) return "en";
  const primary = cleaned.split(/[-_]/)[0];
  return /^[a-z]{2,3}$/.test(primary) ? primary : "en";
}

export function decodeHtmlEntities(rawText) {
  if (!rawText || typeof rawText !== "string") return "";
  return rawText
    .replace(/&#(\d+);/g, (_, num) => {
      const code = Number(num);
      return Number.isFinite(code) ? String.fromCodePoint(code) : "";
    })
    .replace(/&#x([0-9a-fA-F]+);/g, (_, hex) => {
      const code = parseInt(hex, 16);
      return Number.isFinite(code) ? String.fromCodePoint(code) : "";
    })
    .replace(/&amp;/g, "&")
    .replace(/&quot;/g, '"')
    .replace(/&#39;|&apos;/g, "'")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&nbsp;/g, " ")
    .replace(/\r?\n/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

/**
 * Converts `{ start, dur, text }` subtitle records (in seconds) into strictly sorted,
 * non-overlapping millisecond cues `{ startMs, endMs, text }`.
 */
export function normalizeSubtitlesToCues(rawSubtitles) {
  if (!Array.isArray(rawSubtitles) || rawSubtitles.length === 0) return [];

  const parsed = [];
  for (const item of rawSubtitles) {
    const text = decodeHtmlEntities(item?.text);
    if (!text) continue;
    const startSec = Number(item?.start);
    if (!Number.isFinite(startSec) || startSec < 0) continue;
    const durSec = Number(item?.dur);
    const startMs = Math.round(startSec * 1000);
    const rawEndMs =
      Number.isFinite(durSec) && durSec > 0
        ? Math.round((startSec + durSec) * 1000)
        : startMs + 2500;
    parsed.push({
      startMs,
      endMs: Math.max(startMs + 100, rawEndMs),
      text,
    });
  }

  parsed.sort((a, b) => a.startMs - b.startMs);

  // Enforce non-overlapping boundaries so CaptionSyncEngine binary search stays exact
  for (let i = 0; i < parsed.length - 1; i += 1) {
    const current = parsed[i];
    const next = parsed[i + 1];
    if (current.endMs > next.startMs && next.startMs > current.startMs) {
      current.endMs = next.startMs;
    }
    if (current.endMs < current.startMs) {
      current.endMs = current.startMs;
    }
  }
  return parsed;
}

/**
 * Extracts YouTube metadata and timestamped captions using `youtube-caption-extractor`.
 * Supports injecting custom `fetchVideoDetails` / `fetchSubtitles` functions for deterministic tests.
 */
export async function extractYouTubeCaptions({
  videoId,
  lang = "en",
  fetchVideoDetails = getVideoDetails,
  fetchSubtitles = getSubtitles,
}) {
  const cleanId = String(videoId || "").trim();
  if (!isValidYouTubeVideoId(cleanId)) {
    const err = new Error("Invalid YouTube videoId. Expected an 11-character video identifier.");
    err.statusCode = 400;
    throw err;
  }

  const normalizedLang = normalizeCaptionLanguage(lang);
  const candidateLangs = [
    normalizedLang,
    ...(normalizedLang === "ta" ? ["ta", "si", "en"] : []),
    ...(normalizedLang === "si" ? ["si", "ta", "en"] : []),
    "en",
    "ta",
    "si",
    "hi",
  ].filter((v, idx, arr) => v && arr.indexOf(v) === idx);

  let title = "";
  let description = "";
  let resolvedLang = normalizedLang;
  let subtitles = [];

  try {
    const details = await fetchVideoDetails({ videoID: cleanId, lang: normalizedLang });
    title = decodeHtmlEntities(details?.title || "");
    description = decodeHtmlEntities(details?.description || "").slice(0, 800);
    if (Array.isArray(details?.subtitles) && details.subtitles.length > 0) {
      subtitles = details.subtitles;
    }
  } catch {
    // Fallback cleanly to getSubtitles across candidate languages
  }

  for (const candidate of candidateLangs) {
    if (subtitles.length > 0) break;
    try {
      const subs = await fetchSubtitles({ videoID: cleanId, lang: candidate });
      if (Array.isArray(subs) && subs.length > 0) {
        subtitles = subs;
        resolvedLang = candidate;
        break;
      }
    } catch {
      // Try next candidate language
    }
  }

  const cues = normalizeSubtitlesToCues(subtitles);
  if (cues.length === 0) {
    const err = new Error(
      `No public captions or auto-generated subtitles were found for video ${cleanId} (${normalizedLang}).`,
    );
    err.statusCode = 404;
    throw err;
  }

  return {
    videoId: cleanId,
    title: title || `YouTube Video (${cleanId})`,
    description,
    language: resolvedLang,
    requestedLanguage: normalizedLang,
    cueCount: cues.length,
    cues,
  };
}
