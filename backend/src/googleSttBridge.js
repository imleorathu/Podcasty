/*
 * Podcasty Speech Backend — Google Cloud Speech-to-Text Bidirectional gRPC Bridge
 *
 * Uses Application Default Credentials (`GOOGLE_APPLICATION_CREDENTIALS`) on the server.
 * Automatically rolls over streams before Google Cloud STT's 305-second single-stream limit.
 */
import speech from "@google-cloud/speech";
import { MediaTimelineMapper, protobufDurationToMs } from "./timelineMapper.js";

const STREAM_ROLLOVER_MS = 270_000; // Roll over cleanly before Google's 305s hard limit

export class GoogleCloudSttSession {
  constructor({
    sessionId,
    languageCode = "en-US",
    sampleRateHertz = 16000,
    initialMediaMs = 0,
    onCaption,
    onError,
    speechClient = null,
  }) {
    this.sessionId = sessionId;
    this.languageCode = languageCode;
    this.sampleRateHertz = sampleRateHertz;
    this.onCaption = onCaption;
    this.onError = onError;
    this.client = speechClient || new speech.SpeechClient();
    this.timeline = new MediaTimelineMapper(sampleRateHertz);
    this.timeline.reset(initialMediaMs);

    this.recognizeStream = null;
    this.rolloverTimer = null;
    this.closed = false;
    this.paused = false;
    this.segmentCounter = 0;
    this.lastFinalStreamEndMs = 0;
  }

  start() {
    if (this.closed) return;
    this.paused = false;
    this.#openGrpcStream();
  }

  writeAudio(mediaStartMs, mediaEndMs, pcmBytes) {
    if (this.closed || this.paused) return;
    if (!this.recognizeStream) {
      this.#openGrpcStream();
    }
    this.timeline.recordAudioFrame(mediaStartMs, mediaEndMs, pcmBytes.length);
    try {
      this.recognizeStream.write(pcmBytes);
    } catch (err) {
      this.onError?.(`Audio stream write failed: ${err.message}`, true);
    }
  }

  pause() {
    this.paused = true;
    this.#closeGrpcStream();
  }

  resume(newMediaPositionMs) {
    if (this.closed) return;
    this.paused = false;
    this.#closeGrpcStream();
    this.timeline.reset(newMediaPositionMs);
    this.lastFinalStreamEndMs = 0;
    this.#openGrpcStream();
  }

  stop() {
    this.closed = true;
    this.#closeGrpcStream();
  }

  #openGrpcStream() {
    this.#closeGrpcStream();
    if (this.closed) return;

    const request = {
      config: {
        encoding: "LINEAR16",
        sampleRateHertz: this.sampleRateHertz,
        languageCode: this.languageCode,
        enableAutomaticPunctuation: true,
        enableWordTimeOffsets: true,
        model: this.languageCode.startsWith("en-") ? "latest_long" : "default",
      },
      interimResults: true,
    };

    const stream = this.client
      .streamingRecognize(request)
      .on("error", (err) => {
        if (this.closed) return;
        const msg = err?.details || err?.message || "Google Cloud Speech-to-Text error";
        // gRPC code 11 (OUT_OF_RANGE) happens at stream limit — roll over seamlessly
        if (err?.code === 11) {
          this.#rolloverStream();
          return;
        }
        this.onError?.(msg, err?.code !== 7 && err?.code !== 16);
      })
      .on("data", (data) => {
        if (this.closed) return;
        this.#handleRecognitionResponse(data);
      });

    this.recognizeStream = stream;
    this.rolloverTimer = setTimeout(() => {
      if (!this.closed && !this.paused) {
        this.#rolloverStream();
      }
    }, STREAM_ROLLOVER_MS);
  }

  #rolloverStream() {
    const { endMs } = this.timeline.latestWindow();
    this.#closeGrpcStream();
    this.timeline.reset(endMs);
    this.lastFinalStreamEndMs = 0;
    this.#openGrpcStream();
  }

  #closeGrpcStream() {
    if (this.rolloverTimer) {
      clearTimeout(this.rolloverTimer);
      this.rolloverTimer = null;
    }
    if (this.recognizeStream) {
      try {
        this.recognizeStream.removeAllListeners();
        this.recognizeStream.end();
      } catch {
        // ignore close race
      }
      this.recognizeStream = null;
    }
  }

  #handleRecognitionResponse(data) {
    const result = data?.results?.[0];
    const alt = result?.alternatives?.[0];
    const transcript = alt?.transcript?.trim();
    if (!transcript) return;

    const isFinal = Boolean(result.isFinal);
    const words = alt.words || [];
    const firstWordStartMs = words.length > 0 ? protobufDurationToMs(words[0].startTime) : null;
    const lastWordEndMs = words.length > 0 ? protobufDurationToMs(words[words.length - 1].endTime) : null;
    const resultEndMs = protobufDurationToMs(result.resultEndTime);

    let mappedStart;
    let mappedEnd;
    let timingPrecision = "exact";

    if (firstWordStartMs !== null && lastWordEndMs !== null) {
      const s = this.timeline.mapStreamOffsetToMediaMs(firstWordStartMs);
      const e = this.timeline.mapStreamOffsetToMediaMs(lastWordEndMs);
      mappedStart = s.mediaMs;
      mappedEnd = Math.max(mappedStart + 250, e.mediaMs);
      timingPrecision = s.exact && e.exact ? "exact" : "estimated";
    } else if (resultEndMs !== null) {
      const s = this.timeline.mapStreamOffsetToMediaMs(this.lastFinalStreamEndMs);
      const e = this.timeline.mapStreamOffsetToMediaMs(resultEndMs);
      mappedStart = s.mediaMs;
      mappedEnd = Math.max(mappedStart + 250, e.mediaMs);
      timingPrecision = "estimated";
    } else {
      const latest = this.timeline.latestWindow();
      mappedStart = latest.startMs;
      mappedEnd = Math.max(mappedStart + 250, latest.endMs);
      timingPrecision = "estimated";
    }

    if (isFinal && resultEndMs !== null) {
      this.lastFinalStreamEndMs = resultEndMs;
    }

    const segId = isFinal
      ? `${this.sessionId}_final_${++this.segmentCounter}`
      : `${this.sessionId}_partial`;

    this.onCaption?.({
      type: isFinal ? "final" : "partial",
      id: segId,
      sessionId: this.sessionId,
      startMs: mappedStart,
      endMs: mappedEnd,
      text: transcript,
      language: this.languageCode,
      status: isFinal ? "final" : "partial",
      timingPrecision,
    });
  }
}
