/*
 * Podcasty Speech Backend — Authentication, Rate Limiting & SSRF Safety
 */
import crypto from "node:crypto";
import net from "node:net";

export function parseAllowedTokens(envValue) {
  return String(envValue || "")
    .split(",")
    .map((t) => t.trim())
    .filter(Boolean);
}

/**
 * Constant-time Bearer token verification. Never logs token material.
 */
export function verifyBearerToken(authHeader, allowedTokens) {
  if (!allowedTokens || allowedTokens.length === 0) {
    return { ok: false, reason: "Server authentication tokens are not configured." };
  }
  if (!authHeader || typeof authHeader !== "string" || !authHeader.startsWith("Bearer ")) {
    return { ok: false, reason: "Missing Authorization Bearer token." };
  }
  const provided = authHeader.slice("Bearer ".length).trim();
  if (!provided) {
    return { ok: false, reason: "Empty Bearer token." };
  }
  const providedBuf = Buffer.from(provided, "utf8");
  for (const expected of allowedTokens) {
    const expectedBuf = Buffer.from(expected, "utf8");
    if (
      providedBuf.length === expectedBuf.length &&
      crypto.timingSafeEqual(providedBuf, expectedBuf)
    ) {
      return { ok: true };
    }
  }
  return { ok: false, reason: "Invalid Bearer token." };
}

/**
 * Simple sliding-window per-client rate limiter for audio chunks & control frames.
 */
export class TokenBucketRateLimiter {
  constructor(maxEventsPerMinute = 1200) {
    this.maxEventsPerMinute = Math.max(60, Number(maxEventsPerMinute) || 1200);
    this.buckets = new Map();
  }

  allow(clientKey, nowMs = Date.now()) {
    let bucket = this.buckets.get(clientKey);
    if (!bucket || nowMs - bucket.windowStartMs >= 60_000) {
      bucket = { windowStartMs: nowMs, count: 0 };
      this.buckets.set(clientKey, bucket);
    }
    bucket.count += 1;
    return bucket.count <= this.maxEventsPerMinute;
  }

  cleanup(clientKey) {
    this.buckets.delete(clientKey);
  }
}

/**
 * Blocks private, loopback, link-local, and cloud metadata IP addresses to prevent SSRF
 * if any backend helper fetches external URLs.
 */
export function isPrivateOrUnsafeHost(hostname) {
  const host = String(hostname || "").trim().toLowerCase();
  if (!host || host === "localhost" || host.endsWith(".local") || host.endsWith(".internal")) {
    return true;
  }
  const ipVersion = net.isIP(host);
  if (ipVersion === 4) {
    const [a, b] = host.split(".").map(Number);
    if (a === 10 || a === 127 || a === 0) return true;
    if (a === 169 && b === 254) return true; // link-local / cloud metadata 169.254.169.254
    if (a === 172 && b >= 16 && b <= 31) return true;
    if (a === 192 && b === 168) return true;
    if (a === 100 && b >= 64 && b <= 127) return true;
  }
  if (ipVersion === 6) {
    if (host === "::1" || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80:")) {
      return true;
    }
  }
  return false;
}
