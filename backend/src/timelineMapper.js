/*
 * Podcasty Speech Backend — Audio Frame to Media Playback Timeline Mapper
 *
 * Maps Google Cloud Speech-to-Text stream offsets back to the podcast's original
 * media playback timestamps (never API response arrival wall-clock time).
 */

export class MediaTimelineMapper {
  constructor(sampleRateHertz = 16000) {
    this.sampleRateHertz = sampleRateHertz;
    this.bytesPerMs = (sampleRateHertz * 2) / 1000; // LINEAR16 mono = 2 bytes/sample
    this.streamDurationMs = 0;
    this.segments = []; // { streamStartMs, streamEndMs, mediaStartMs, mediaEndMs }
    this.lastMediaMs = 0;
  }

  reset(initialMediaMs = 0) {
    this.streamDurationMs = 0;
    this.segments = [];
    this.lastMediaMs = Math.max(0, Number(initialMediaMs) || 0);
  }

  recordAudioFrame(mediaStartMs, mediaEndMs, pcmByteLength) {
    const durationMs = Math.max(1, Math.round(pcmByteLength / this.bytesPerMs));
    const streamStartMs = this.streamDurationMs;
    const streamEndMs = streamStartMs + durationMs;
    this.streamDurationMs = streamEndMs;

    const safeMediaStart = Math.max(0, Number(mediaStartMs) || this.lastMediaMs);
    const safeMediaEnd = Math.max(safeMediaStart + 1, Number(mediaEndMs) || safeMediaStart + durationMs);
    this.lastMediaMs = safeMediaEnd;

    this.segments.push({
      streamStartMs,
      streamEndMs,
      mediaStartMs: safeMediaStart,
      mediaEndMs: safeMediaEnd,
    });

    // Keep bounded history (~2 minutes of 100 ms chunks = 1200 entries)
    if (this.segments.length > 1500) {
      this.segments.splice(0, this.segments.length - 1200);
    }
  }

  mapStreamOffsetToMediaMs(streamOffsetMs) {
    if (this.segments.length === 0) {
      return { mediaMs: this.lastMediaMs, exact: false };
    }
    const target = Math.max(0, Number(streamOffsetMs) || 0);
    // Binary search over recorded stream segments
    let lo = 0;
    let hi = this.segments.length - 1;
    while (lo <= hi) {
      const mid = (lo + hi) >>> 1;
      const seg = this.segments[mid];
      if (target < seg.streamStartMs) {
        hi = mid - 1;
      } else if (target > seg.streamEndMs) {
        lo = mid + 1;
      } else {
        const span = Math.max(1, seg.streamEndMs - seg.streamStartMs);
        const fraction = (target - seg.streamStartMs) / span;
        const mediaMs = Math.round(seg.mediaStartMs + fraction * (seg.mediaEndMs - seg.mediaStartMs));
        return { mediaMs, exact: true };
      }
    }
    const nearest = this.segments[Math.min(this.segments.length - 1, Math.max(0, hi))];
    return { mediaMs: nearest.mediaEndMs, exact: false };
  }

  latestWindow() {
    if (this.segments.length === 0) {
      return { startMs: this.lastMediaMs, endMs: this.lastMediaMs + 1000 };
    }
    const recent = this.segments[this.segments.length - 1];
    return { startMs: recent.mediaStartMs, endMs: recent.mediaEndMs };
  }
}

export function decodeBinaryAudioFrame(buffer) {
  if (!Buffer.isBuffer(buffer) || buffer.length <= 16) {
    return null;
  }
  const mediaStartMs = Number(buffer.readBigInt64BE(0));
  const mediaEndMs = Number(buffer.readBigInt64BE(8));
  const pcmBytes = buffer.subarray(16);
  return { mediaStartMs, mediaEndMs, pcmBytes };
}

export function protobufDurationToMs(duration) {
  if (!duration) return null;
  const seconds = Number(duration.seconds || 0);
  const nanos = Number(duration.nanos || 0);
  if (Number.isNaN(seconds) || Number.isNaN(nanos)) return null;
  return Math.round(seconds * 1000 + nanos / 1_000_000);
}
