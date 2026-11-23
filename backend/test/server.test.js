import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import test from "node:test";
import WebSocket from "ws";
import { GoogleCloudSttSession } from "../src/googleSttBridge.js";
import { isPrivateOrUnsafeHost, verifyBearerToken } from "../src/security.js";
import { createPodcastySpeechServer } from "../src/server.js";
import { MediaTimelineMapper, decodeBinaryAudioFrame } from "../src/timelineMapper.js";
import {
  decodeHtmlEntities,
  extractYouTubeCaptions,
  normalizeCaptionLanguage,
  normalizeSubtitlesToCues,
} from "../src/youtubeCaptionExtractor.js";

function encodeBinaryAudioFrame(mediaStartMs, mediaEndMs, pcmBytes) {
  const buf = Buffer.alloc(16 + pcmBytes.length);
  buf.writeBigInt64BE(BigInt(mediaStartMs), 0);
  buf.writeBigInt64BE(BigInt(mediaEndMs), 8);
  pcmBytes.copy(buf, 16);
  return buf;
}

class FakeGrpcStream extends EventEmitter {
  constructor(languageCode) {
    super();
    this.languageCode = languageCode;
    this.writtenChunks = 0;
  }
  write(chunk) {
    this.writtenChunks += 1;
    if (this.writtenChunks === 1) {
      const text =
        this.languageCode === "ta-IN" || this.languageCode === "ta-LK"
          ? "இன்றைய பாட்காஸ்டுக்கு"
          : this.languageCode === "si-LK"
            ? "අද වැඩසටහනට"
            : "Welcome to today's";
      this.emit("data", {
        results: [
          {
            isFinal: false,
            resultEndTime: { seconds: 0, nanos: 100_000_000 },
            alternatives: [{ transcript: text }],
          },
        ],
      });
    } else if (this.writtenChunks === 2) {
      const text =
        this.languageCode === "ta-IN" || this.languageCode === "ta-LK"
          ? "இன்றைய பாட்காஸ்டுக்கு வரவேற்கிறோம்"
          : this.languageCode === "si-LK"
            ? "අද වැඩසටහනට සාදරයෙන් පිළිගනිමු"
            : "Welcome to today's podcast";
      this.emit("data", {
        results: [
          {
            isFinal: true,
            resultEndTime: { seconds: 0, nanos: 200_000_000 },
            alternatives: [
              {
                transcript: text,
                words: [
                  { startTime: { seconds: 0, nanos: 0 }, endTime: { seconds: 0, nanos: 90_000_000 } },
                  { startTime: { seconds: 0, nanos: 100_000_000 }, endTime: { seconds: 0, nanos: 200_000_000 } },
                ],
              },
            ],
          },
        ],
      });
    }
  }
  end() {
    this.ended = true;
  }
}

test("security: constant-time Bearer token & SSRF private host blocking", () => {
  assert.equal(verifyBearerToken("Bearer secret-1", ["secret-1"]).ok, true);
  assert.equal(verifyBearerToken("Bearer wrong", ["secret-1"]).ok, false);
  assert.equal(verifyBearerToken(undefined, ["secret-1"]).ok, false);

  assert.equal(isPrivateOrUnsafeHost("localhost"), true);
  assert.equal(isPrivateOrUnsafeHost("127.0.0.1"), true);
  assert.equal(isPrivateOrUnsafeHost("169.254.169.254"), true);
  assert.equal(isPrivateOrUnsafeHost("192.168.1.5"), true);
  assert.equal(isPrivateOrUnsafeHost("10.0.0.2"), true);
  assert.equal(isPrivateOrUnsafeHost("feeds.npr.org"), false);
});

test("timelineMapper: maps stream offsets back to exact media playback positions across seeks", () => {
  const mapper = new MediaTimelineMapper(16000);
  mapper.reset(12_500);
  // 1600 samples * 2 bytes = 3200 bytes = 100 ms
  mapper.recordAudioFrame(12_500, 12_600, 3200);
  mapper.recordAudioFrame(12_600, 12_700, 3200);
  mapper.recordAudioFrame(12_700, 12_800, 3200);

  const mappedStart = mapper.mapStreamOffsetToMediaMs(50);
  assert.equal(mappedStart.exact, true);
  assert.equal(mappedStart.mediaMs, 12_550);

  const mappedMid = mapper.mapStreamOffsetToMediaMs(250);
  assert.equal(mappedMid.exact, true);
  assert.equal(mappedMid.mediaMs, 12_750);

  // Seek forward to 90_000 ms
  mapper.reset(90_000);
  mapper.recordAudioFrame(90_000, 90_100, 3200);
  const afterSeek = mapper.mapStreamOffsetToMediaMs(40);
  assert.equal(afterSeek.mediaMs, 90_040);

  const frame = encodeBinaryAudioFrame(12_500, 12_600, Buffer.alloc(3200, 1));
  const decoded = decodeBinaryAudioFrame(frame);
  assert.equal(decoded.mediaStartMs, 12_500);
  assert.equal(decoded.mediaEndMs, 12_600);
  assert.equal(decoded.pcmBytes.length, 3200);
});

test("WebSocket server: rejects unauthorized client and streams en-US & ta-IN partial -> final captions", async () => {
  const fakeSpeechClient = {
    streamingRecognize: (req) => new FakeGrpcStream(req.config.languageCode),
  };

  const { server } = createPodcastySpeechServer({
    allowedTokens: ["test-token-123"],
    sessionFactory: (opts) => new GoogleCloudSttSession({ ...opts, speechClient: fakeSpeechClient }),
  });

  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address();

  try {
    // 1. Unauthorized WebSocket must be rejected with HTTP 401
    await assert.rejects(
      () =>
        new Promise((resolve, reject) => {
          const ws = new WebSocket(`ws://127.0.0.1:${port}/ws/captions`, {
            headers: { Authorization: "Bearer invalid" },
          });
          ws.on("open", resolve);
          ws.on("error", reject);
        }),
    );

    // 2. Authorized English (en-US) streaming session
    const enEvents = await runCaptionSession(port, "test-token-123", "en-US", 12_500);
    assert.equal(enEvents[0].type, "ready");
    assert.equal(enEvents[1].type, "partial");
    assert.equal(enEvents[1].text, "Welcome to today's");
    assert.equal(enEvents[2].type, "final");
    assert.equal(enEvents[2].text, "Welcome to today's podcast");
    assert.equal(enEvents[2].startMs, 12_500);
    assert.equal(enEvents[2].timingPrecision, "exact");

    // 3. Authorized Tamil (ta-IN) streaming session
    const taEvents = await runCaptionSession(port, "test-token-123", "ta-IN", 30_000);
    assert.equal(taEvents[0].type, "ready");
    assert.equal(taEvents[1].type, "partial");
    assert.equal(taEvents[2].type, "final");
    assert.equal(taEvents[2].language, "ta-IN");
    assert.equal(taEvents[2].text, "இன்றைய பாட்காஸ்டுக்கு வரவேற்கிறோம்");
    assert.equal(taEvents[2].startMs, 30_000);

    // 4. Authorized Sinhala (si-LK) streaming session
    const siEvents = await runCaptionSession(port, "test-token-123", "si-LK", 45_000);
    assert.equal(siEvents[0].type, "ready");
    assert.equal(siEvents[1].type, "partial");
    assert.equal(siEvents[2].type, "final");
    assert.equal(siEvents[2].language, "si-LK");
    assert.equal(siEvents[2].text, "අද වැඩසටහනට සාදරයෙන් පිළිගනිමු");
    assert.equal(siEvents[2].startMs, 45_000);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

function runCaptionSession(port, token, languageCode, mediaStartMs) {
  return new Promise((resolve, reject) => {
    const received = [];
    const ws = new WebSocket(`ws://127.0.0.1:${port}/ws/captions`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    ws.on("open", () => {
      ws.send(
        JSON.stringify({
          type: "start",
          sessionId: `sess_${languageCode}`,
          languageCode,
          sampleRateHertz: 16000,
          mediaStartMs,
        }),
      );
    });
    ws.on("message", (raw) => {
      const msg = JSON.parse(raw.toString("utf8"));
      received.push(msg);
      if (msg.type === "ready") {
        ws.send(encodeBinaryAudioFrame(mediaStartMs, mediaStartMs + 100, Buffer.alloc(3200, 7)));
        ws.send(encodeBinaryAudioFrame(mediaStartMs + 100, mediaStartMs + 200, Buffer.alloc(3200, 9)));
      }
      if (msg.type === "final") {
        ws.send(JSON.stringify({ type: "stop", sessionId: `sess_${languageCode}` }));
        ws.close();
        resolve(received);
      }
    });
    ws.on("error", reject);
  });
}

test("youtubeCaptionExtractor: normalizes languages, decodes HTML entities, and prevents cue overlaps", () => {
  assert.equal(normalizeCaptionLanguage("en-US"), "en");
  assert.equal(normalizeCaptionLanguage("ta-IN"), "ta");
  assert.equal(normalizeCaptionLanguage("EN_GB"), "en");
  assert.equal(decodeHtmlEntities("Welcome &amp; let&#39;s talk\nabout &quot;AI&quot;"), 'Welcome & let\'s talk about "AI"');

  const cues = normalizeSubtitlesToCues([
    { start: "5.20", dur: "3.10", text: "Second line" },
    { start: "1.12", dur: "6.00", text: "First &amp; overlapping line" },
  ]);
  assert.equal(cues.length, 2);
  assert.equal(cues[0].startMs, 1120);
  assert.equal(cues[0].endMs, 5200); // clamped to next cue's startMs so no overlap
  assert.equal(cues[0].text, "First & overlapping line");
  assert.equal(cues[1].startMs, 5200);
  assert.equal(cues[1].endMs, 8300);
});

test("HTTP /api/youtube/captions: enforces Bearer auth, validates videoId, extracts EN & TA captions, and handles 404", async () => {
  const stubExtractor = ({ videoId, lang }) =>
    extractYouTubeCaptions({
      videoId,
      lang,
      fetchVideoDetails: async ({ videoID, lang: reqLang }) => {
        if (videoID === "NO_CAPTIONS") {
          return { title: "Silent Video", description: "", subtitles: [] };
        }
        if (reqLang === "ta") {
          return {
            title: "தமிழ் பாட்காஸ்ட்",
            description: "நேரடி விளக்கம்",
            subtitles: [
              { start: "0.50", dur: "2.50", text: "வணக்கம் நண்பர்களே" },
              { start: "3.00", dur: "3.20", text: "இன்றைய நிகழ்ச்சிக்கு வரவேற்கிறோம்" },
            ],
          };
        }
        return {
          title: "Tech Podcast Episode",
          description: "Podcast &amp; systems",
          subtitles: [
            { start: "1.12", dur: "4.56", text: "Welcome to today&#39;s show" },
            { start: "5.68", dur: "3.00", text: "Let&#39;s discuss synchronized transcripts" },
          ],
        };
      },
      fetchSubtitles: async () => [],
    });

  const { server } = createPodcastySpeechServer({
    allowedTokens: ["yt-token-999"],
    youtubeCaptionExtractor: stubExtractor,
  });

  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address();
  const baseUrl = `http://127.0.0.1:${port}`;

  try {
    // 1. Unauthorized -> 401
    const unauth = await fetch(`${baseUrl}/api/youtube/captions?videoId=jNQXAC9IVRw&lang=en-US`);
    assert.equal(unauth.status, 401);

    // 2. Invalid videoId -> 400
    const badId = await fetch(`${baseUrl}/api/youtube/captions?videoId=short&lang=en-US`, {
      headers: { Authorization: "Bearer yt-token-999" },
    });
    assert.equal(badId.status, 400);

    // 3. Valid English extraction -> 200
    const enRes = await fetch(`${baseUrl}/api/youtube/captions?videoId=jNQXAC9IVRw&lang=en-US`, {
      headers: { Authorization: "Bearer yt-token-999" },
    });
    assert.equal(enRes.status, 200);
    const enJson = await enRes.json();
    assert.equal(enJson.videoId, "jNQXAC9IVRw");
    assert.equal(enJson.language, "en");
    assert.equal(enJson.cueCount, 2);
    assert.equal(enJson.cues[0].startMs, 1120);
    assert.equal(enJson.cues[0].endMs, 5680);
    assert.equal(enJson.cues[0].text, "Welcome to today's show");

    // 4. Valid Tamil extraction -> 200
    const taRes = await fetch(`${baseUrl}/api/youtube/captions?videoId=jNQXAC9IVRw&lang=ta-IN`, {
      headers: { Authorization: "Bearer yt-token-999" },
    });
    assert.equal(taRes.status, 200);
    const taJson = await taRes.json();
    assert.equal(taJson.language, "ta");
    assert.equal(taJson.cues[0].text, "வணக்கம் நண்பர்களே");

    // 5. No captions available -> 404
    const notFound = await fetch(`${baseUrl}/api/youtube/captions?videoId=NO_CAPTIONS&lang=en-US`, {
      headers: { Authorization: "Bearer yt-token-999" },
    });
    assert.equal(notFound.status, 404);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});
