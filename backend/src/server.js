/*
 * Podcasty Speech Backend — Secure WebSocket Server
 *
 * Architecture:
 *   Android App --(Secure WebSocket wss://)--> Node.js Backend --(Bidirectional gRPC)--> Google Cloud STT
 */
import fs from "node:fs";
import http from "node:http";
import https from "node:https";
import { WebSocketServer } from "ws";
import { GoogleCloudSttSession } from "./googleSttBridge.js";
import {
  TokenBucketRateLimiter,
  parseAllowedTokens,
  verifyBearerToken,
} from "./security.js";
import { decodeBinaryAudioFrame } from "./timelineMapper.js";
import { extractYouTubeCaptions, isValidYouTubeVideoId } from "./youtubeCaptionExtractor.js";

const DEFAULT_ALLOWED_LANGUAGES = [
  "ta-IN", "ta-LK", "si-LK",
  "en-US", "en-IN", "en-GB",
  "hi-IN", "ml-IN", "te-IN", "kn-IN", "mr-IN", "bn-IN", "gu-IN", "pa-Guru-IN", "ur-IN",
  "ar-SA", "zh-CN", "ja-JP", "ko-KR", "es-ES", "fr-FR", "de-DE", "pt-BR", "ru-RU",
  "it-IT", "tr-TR", "id-ID", "ms-MY", "th-TH", "vi-VN",
];
const BCP47_REGEX = /^[a-zA-Z]{2,3}(?:-[a-zA-Z0-9]{2,8}){0,3}$/;

export function createPodcastySpeechServer({
  allowedTokens = parseAllowedTokens(process.env.PODCASTY_API_TOKENS),
  allowedLanguages = process.env.ALLOWED_LANGUAGES
    ? process.env.ALLOWED_LANGUAGES.split(",").map((s) => s.trim()).filter(Boolean)
    : DEFAULT_ALLOWED_LANGUAGES,
  allowAllBcp47Languages = process.env.RESTRICT_LANGUAGES !== "true",
  maxConcurrentSessions = Number(process.env.MAX_CONCURRENT_SESSIONS || 20),
  maxChunksPerMinute = Number(process.env.MAX_CHUNKS_PER_MINUTE || 1200),
  maxChunkBytes = Number(process.env.MAX_CHUNK_BYTES || 32768),
  tlsCertPath = process.env.TLS_CERT_PATH || "",
  tlsKeyPath = process.env.TLS_KEY_PATH || "",
  sessionFactory = null,
  youtubeCaptionExtractor = extractYouTubeCaptions,
} = {}) {
  const rateLimiter = new TokenBucketRateLimiter(maxChunksPerMinute);
  const activeSessions = new Set();

  const requestHandler = async (req, res) => {
    const parsedUrl = new URL(req.url || "/", "http://localhost");
    const pathname = parsedUrl.pathname;

    if (req.method === "GET" && pathname === "/health") {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(
        JSON.stringify({
          status: "ok",
          activeSessions: activeSessions.size,
          supportedLanguages: allowedLanguages,
          youtubeCaptionExtractor: true,
        }),
      );
      return;
    }

    if (req.method === "GET" && pathname === "/api/youtube/captions") {
      const auth = verifyBearerToken(req.headers["authorization"], allowedTokens);
      if (!auth.ok) {
        res.writeHead(401, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ error: auth.reason }));
        return;
      }
      const clientKey = req.socket?.remoteAddress || "http-client";
      if (!rateLimiter.allow(clientKey)) {
        res.writeHead(429, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ error: "Rate limit exceeded. Please try again shortly." }));
        return;
      }

      const videoId = (parsedUrl.searchParams.get("videoId") || "").trim();
      const lang = (parsedUrl.searchParams.get("lang") || "en").trim();
      if (!isValidYouTubeVideoId(videoId)) {
        res.writeHead(400, { "Content-Type": "application/json" });
        res.end(
          JSON.stringify({
            error: "Invalid videoId parameter. Expected an 11-character YouTube video ID.",
          }),
        );
        return;
      }

      try {
        const result = await youtubeCaptionExtractor({ videoId, lang });
        res.writeHead(200, { "Content-Type": "application/json; charset=utf-8" });
        res.end(JSON.stringify(result));
      } catch (err) {
        const status = err?.statusCode === 400 || err?.statusCode === 404 ? err.statusCode : 502;
        res.writeHead(status, { "Content-Type": "application/json; charset=utf-8" });
        res.end(
          JSON.stringify({
            error: err?.message || "Failed to extract YouTube captions.",
          }),
        );
      }
      return;
    }

    res.writeHead(404, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ error: "Not found" }));
  };

  const server =
    tlsCertPath && tlsKeyPath
      ? https.createServer(
          {
            cert: fs.readFileSync(tlsCertPath),
            key: fs.readFileSync(tlsKeyPath),
          },
          requestHandler,
        )
      : http.createServer(requestHandler);

  const wss = new WebSocketServer({ noServer: true, maxPayload: maxChunkBytes + 256 });

  server.on("upgrade", (request, socket, head) => {
    const pathname = new URL(request.url || "/", "http://localhost").pathname;
    if (pathname !== "/ws/captions" && pathname !== "/") {
      socket.write("HTTP/1.1 404 Not Found\r\n\r\n");
      socket.destroy();
      return;
    }
    const auth = verifyBearerToken(request.headers["authorization"], allowedTokens);
    if (!auth.ok) {
      socket.write("HTTP/1.1 401 Unauthorized\r\n\r\n");
      socket.destroy();
      return;
    }
    if (activeSessions.size >= maxConcurrentSessions) {
      socket.write("HTTP/1.1 429 Too Many Requests\r\n\r\n");
      socket.destroy();
      return;
    }
    wss.handleUpgrade(request, socket, head, (ws) => {
      wss.emit("connection", ws, request);
    });
  });

  wss.on("connection", (ws, request) => {
    const clientKey = request.socket.remoteAddress || "unknown";
    let session = null;
    activeSessions.add(ws);

    const sendJson = (obj) => {
      if (ws.readyState === ws.OPEN) {
        ws.send(JSON.stringify(obj));
      }
    };

    ws.on("message", (data, isBinary) => {
      if (!rateLimiter.allow(clientKey)) {
        sendJson({
          type: "error",
          message: "Rate limit exceeded. Slow down audio streaming frequency.",
          recoverable: true,
        });
        return;
      }

      if (isBinary) {
        if (!session) return;
        const buf = Buffer.isBuffer(data) ? data : Buffer.from(data);
        if (buf.length > maxChunkBytes + 16) {
          sendJson({ type: "error", message: "Audio chunk exceeds maximum size.", recoverable: true });
          return;
        }
        const decoded = decodeBinaryAudioFrame(buf);
        if (!decoded) return;
        session.writeAudio(decoded.mediaStartMs, decoded.mediaEndMs, decoded.pcmBytes);
        return;
      }

      let msg;
      try {
        msg = JSON.parse(data.toString("utf8"));
      } catch {
        sendJson({ type: "error", message: "Malformed JSON control frame.", recoverable: true });
        return;
      }

      switch (msg.type) {
        case "start": {
          const lang = String(msg.languageCode || "en-US").trim();
          const isAllowedLang =
            allowedLanguages.includes(lang) ||
            (allowAllBcp47Languages && BCP47_REGEX.test(lang));
          if (!isAllowedLang) {
            sendJson({
              type: "error",
              message: `Unsupported language "${lang}". Allowed: ${allowedLanguages.join(", ")}`,
              recoverable: false,
            });
            return;
          }
          const sampleRateHertz = Number(msg.sampleRateHertz || 16000);
          if (sampleRateHertz !== 16000 && sampleRateHertz !== 8000 && sampleRateHertz !== 48000) {
            sendJson({ type: "error", message: "Unsupported sampleRateHertz.", recoverable: false });
            return;
          }
          session?.stop();
          const createFn =
            sessionFactory ||
            ((opts) => new GoogleCloudSttSession(opts));
          session = createFn({
            sessionId: String(msg.sessionId || `sess_${Date.now()}`),
            languageCode: lang,
            sampleRateHertz,
            initialMediaMs: Number(msg.mediaStartMs || 0),
            onCaption: (captionEvent) => sendJson(captionEvent),
            onError: (message, recoverable) =>
              sendJson({ type: "error", message, recoverable: Boolean(recoverable) }),
          });
          session.start();
          sendJson({
            type: "ready",
            sessionId: session.sessionId,
            language: lang,
          });
          break;
        }
        case "pause": {
          session?.pause();
          break;
        }
        case "resume": {
          session?.resume(Number(msg.mediaStartMs || 0));
          break;
        }
        case "stop": {
          session?.stop();
          session = null;
          sendJson({ type: "closed" });
          break;
        }
        default:
          sendJson({ type: "error", message: `Unknown control frame type: ${msg.type}`, recoverable: true });
      }
    });

    ws.on("close", () => {
      session?.stop();
      session = null;
      activeSessions.delete(ws);
      rateLimiter.cleanup(clientKey);
    });

    ws.on("error", () => {
      session?.stop();
      session = null;
      activeSessions.delete(ws);
    });
  });

  return { server, wss, activeSessions };
}

// Run standalone when launched directly (`node src/server.js`)
if (import.meta.url === `file://${process.argv[1]}`) {
  const port = Number(process.env.PORT || 8443);
  const host = process.env.HOST || "0.0.0.0";
  const { server } = createPodcastySpeechServer();
  server.listen(port, host, () => {
    const scheme = process.env.TLS_CERT_PATH ? "wss" : "ws";
    console.log(`Podcasty Speech Backend listening on ${scheme}://${host}:${port}/ws/captions`);
  });
}
