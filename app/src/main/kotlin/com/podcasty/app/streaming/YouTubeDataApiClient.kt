/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.streaming

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class YouTubeCaptionTrack(
    val id: String,
    val language: String,
    val name: String,
    val trackKind: String,
)

sealed class YouTubeApiException(message: String) : Exception(message) {
    class NotAuthenticated(message: String) : YouTubeApiException(message)
    class NotAuthorizedForVideo(message: String) : YouTubeApiException(message)
    class NoCaptionTracks(message: String) : YouTubeApiException(message)
    class RequestFailed(message: String) : YouTubeApiException(message)
}

/**
 * Policy-compliant YouTube Data API v3 + OAuth 2.0 PKCE client (Priority A transcripts).
 *
 * Security & Compliance:
 * - Zero hardcoded OAuth client secrets or API keys.
 * - OAuth tokens are encrypted at rest using Android KeyStore AES-256-GCM before saving to private prefs.
 * - Uses official Google OAuth 2.0 Authorization Code Flow with PKCE (SHA-256) and scope
 *   `https://www.googleapis.com/auth/youtube.force-ssl` when the user configures their OAuth Client ID.
 * - Strictly honors YouTube Data API v3 authorization: `captions.download` only succeeds for videos
 *   owned/editable by the authenticated user; 403 responses are reported honestly and never bypassed.
 */
class YouTubeDataApiClient(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val prefs by lazy {
        context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
    }

    private var pendingCodeVerifier: String? = null
    private var pendingState: String? = null

    fun getConfiguredClientId(): String = prefs.getString(KeyClientId, "").orEmpty()

    fun saveClientId(clientId: String) {
        prefs.edit().putString(KeyClientId, clientId.trim()).apply()
    }

    fun hasActiveOAuthToken(): Boolean = getDecryptedAccessToken() != null

    fun signOut() {
        prefs.edit()
            .remove(KeyEncryptedAccessToken)
            .remove(KeyEncryptedRefreshToken)
            .remove(KeyTokenExpiresAtMs)
            .apply()
    }

    /**
     * Launches official Google OAuth 2.0 PKCE consent in the system browser using the
     * user-configured Android OAuth Client ID (never hardcoded).
     */
    fun startOAuthPkceSignIn(clientId: String): Result<Unit> {
        val trimmedId = clientId.trim()
        if (trimmedId.isEmpty()) {
            return Result.failure(IllegalArgumentException("Enter your Google OAuth Android Client ID first."))
        }
        saveClientId(trimmedId)
        val verifier = generateCodeVerifier()
        val challenge = computeS256Challenge(verifier)
        val state = generateCodeVerifier().take(24)
        pendingCodeVerifier = verifier
        pendingState = state

        val authUri = Uri.parse("https://accounts.google.com/o/oauth2/v2/auth")
            .buildUpon()
            .appendQueryParameter("client_id", trimmedId)
            .appendQueryParameter("redirect_uri", RedirectUri)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", YouTubeCaptionsScope)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("state", state)
            .build()

        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW, authUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    /**
     * Handles the OAuth 2.0 redirect URI (`com.podcasty.app:/oauth2redirect?code=...`)
     * and exchanges the PKCE authorization code for an access token over HTTPS.
     */
    suspend fun handleOAuthRedirect(redirectUri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val error = redirectUri.getQueryParameter("error")
            if (!error.isNullOrBlank()) {
                throw YouTubeApiException.NotAuthenticated("OAuth authorization declined or failed: $error")
            }
            val returnedState = redirectUri.getQueryParameter("state")
            if (pendingState != null && returnedState != pendingState) {
                throw YouTubeApiException.NotAuthenticated("OAuth state mismatch. Please retry sign-in.")
            }
            val code = redirectUri.getQueryParameter("code")
                ?: throw YouTubeApiException.NotAuthenticated("Missing OAuth authorization code.")
            val verifier = pendingCodeVerifier
                ?: throw YouTubeApiException.NotAuthenticated("OAuth PKCE session expired. Please retry sign-in.")
            val clientId = getConfiguredClientId()
            if (clientId.isBlank()) {
                throw YouTubeApiException.NotAuthenticated("OAuth Client ID is not configured.")
            }

            val form = buildString {
                append("grant_type=authorization_code")
                append("&client_id=").append(URLEncoder.encode(clientId, "UTF-8"))
                append("&code=").append(URLEncoder.encode(code, "UTF-8"))
                append("&redirect_uri=").append(URLEncoder.encode(RedirectUri, "UTF-8"))
                append("&code_verifier=").append(URLEncoder.encode(verifier, "UTF-8"))
            }
            val response = httpPostForm("https://oauth2.googleapis.com/token", form)
            val obj = json.parseToJsonElement(response).jsonObject
            val accessToken = obj["access_token"]?.jsonPrimitive?.content
                ?: throw YouTubeApiException.NotAuthenticated("Token endpoint did not return an access token.")
            val refreshToken = obj["refresh_token"]?.jsonPrimitive?.content
            val expiresIn = obj["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L

            storeEncryptedTokens(accessToken, refreshToken, System.currentTimeMillis() + expiresIn * 1_000L)
            pendingCodeVerifier = null
            pendingState = null
        }
    }

    /**
     * Allows storing an externally obtained OAuth access token encrypted in Android KeyStore
     * (useful when testing Priority A with a short-lived Google OAuth Playground bearer token).
     */
    fun saveEncryptedSessionToken(accessToken: String, expiresInSeconds: Long = 3600L) {
        if (accessToken.isBlank()) return
        storeEncryptedTokens(
            accessToken = accessToken.trim(),
            refreshToken = null,
            expiresAtMs = System.currentTimeMillis() + expiresInSeconds * 1_000L,
        )
    }

    /**
     * Calls official YouTube Data API v3 `captions.list` for a video.
     */
    suspend fun listCaptionTracks(videoId: String): Result<List<YouTubeCaptionTrack>> = withContext(Dispatchers.IO) {
        runCatching {
            val token = getValidAccessToken()
                ?: throw YouTubeApiException.NotAuthenticated("Sign in with YouTube OAuth 2.0 to access authorized transcripts.")
            val url = "https://www.googleapis.com/youtube/v3/captions?part=snippet&videoId=${URLEncoder.encode(videoId, "UTF-8")}"
            val (code, body) = httpGetWithBearer(url, token)
            when (code) {
                200 -> parseCaptionListJson(body)
                401 -> {
                    signOut()
                    throw YouTubeApiException.NotAuthenticated("OAuth token expired or revoked. Please sign in again.")
                }
                403 -> throw YouTubeApiException.NotAuthorizedForVideo(
                    "YouTube Data API v3 denied caption access (HTTP 403). Captions can only be downloaded for videos you own or have edit permission for.",
                )
                404 -> throw YouTubeApiException.NoCaptionTracks("Video not found or has no caption tracks.")
                else -> throw YouTubeApiException.RequestFailed("YouTube Captions API error (HTTP $code).")
            }
        }
    }

    /**
     * Calls official YouTube Data API v3 `captions.download` (requesting VTT first, then SRT)
     * strictly for videos where the authenticated user holds authorization.
     */
    suspend fun downloadAuthorizedCaption(
        videoId: String,
        preferredLanguage: String = "en",
    ): Result<Pair<YouTubeCaptionTrack, String>> = withContext(Dispatchers.IO) {
        runCatching {
            val tracks = listCaptionTracks(videoId).getOrThrow()
            if (tracks.isEmpty()) {
                throw YouTubeApiException.NoCaptionTracks("No caption tracks listed for this video.")
            }
            val selected = tracks.firstOrNull { it.language.equals(preferredLanguage, ignoreCase = true) }
                ?: tracks.firstOrNull { it.language.startsWith(preferredLanguage, ignoreCase = true) }
                ?: tracks.first()

            val token = getValidAccessToken()
                ?: throw YouTubeApiException.NotAuthenticated("OAuth session required.")

            // Try WebVTT first, then SRT
            for (tfmt in listOf("vtt", "srt")) {
                val url = "https://www.googleapis.com/youtube/v3/captions/${URLEncoder.encode(selected.id, "UTF-8")}?tfmt=$tfmt"
                val (code, body) = httpGetWithBearer(url, token)
                when (code) {
                    200 -> if (body.isNotBlank()) return@runCatching selected to body
                    401 -> {
                        signOut()
                        throw YouTubeApiException.NotAuthenticated("OAuth token expired. Please sign in again.")
                    }
                    403 -> throw YouTubeApiException.NotAuthorizedForVideo(
                        "YouTube Data API v3 denied captions.download (HTTP 403). Only videos owned or editable by your authenticated account can download transcripts via this API.",
                    )
                    404 -> throw YouTubeApiException.NoCaptionTracks("Caption track could not be found (HTTP 404).")
                }
            }
            throw YouTubeApiException.RequestFailed("Could not download caption track in VTT or SRT format.")
        }
    }

    internal fun parseCaptionListJson(rawJson: String): List<YouTubeCaptionTrack> {
        val root = json.parseToJsonElement(rawJson).jsonObject
        val items = root["items"]?.jsonArray ?: return emptyList()
        return items.mapNotNull { elem ->
            val obj = elem.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val snippet = obj["snippet"]?.jsonObject ?: return@mapNotNull null
            YouTubeCaptionTrack(
                id = id,
                language = snippet["language"]?.jsonPrimitive?.content ?: "en",
                name = snippet["name"]?.jsonPrimitive?.content ?: "",
                trackKind = snippet["trackKind"]?.jsonPrimitive?.content ?: "standard",
            )
        }
    }

    private fun getValidAccessToken(): String? {
        val expiresAt = prefs.getLong(KeyTokenExpiresAtMs, 0L)
        if (expiresAt > 0L && System.currentTimeMillis() >= expiresAt - 30_000L) {
            return null
        }
        return getDecryptedAccessToken()
    }

    private fun storeEncryptedTokens(accessToken: String, refreshToken: String?, expiresAtMs: Long) {
        val editor = prefs.edit()
            .putString(KeyEncryptedAccessToken, encryptString(accessToken))
            .putLong(KeyTokenExpiresAtMs, expiresAtMs)
        if (!refreshToken.isNullOrBlank()) {
            editor.putString(KeyEncryptedRefreshToken, encryptString(refreshToken))
        }
        editor.apply()
    }

    private fun getDecryptedAccessToken(): String? {
        val blob = prefs.getString(KeyEncryptedAccessToken, null) ?: return null
        return runCatching { decryptString(blob) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun getOrCreateKeyStoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KeyStoreAlias, null) as? SecretKey)?.let { return it }
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            KeyStoreAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGen.init(spec)
        return keyGen.generateKey()
    }

    private fun encryptString(plainText: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKeyStoreKey())
        val iv = cipher.iv
        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(1 + iv.size + cipherBytes.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(cipherBytes, 0, combined, 1 + iv.size, cipherBytes.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decryptString(encoded: String): String {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        val ivLen = combined[0].toInt() and 0xFF
        val iv = combined.copyOfRange(1, 1 + ivLen)
        val cipherBytes = combined.copyOfRange(1 + ivLen, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKeyStoreKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
    }

    private fun httpGetWithBearer(urlString: String, token: String): Pair<Int, String> {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json, text/vtt, text/plain")
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            code to body
        } finally {
            conn.disconnect()
        }
    }

    private fun httpPostForm(urlString: String, formBody: String): String {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        return try {
            conn.outputStream.use { it.write(formBody.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw YouTubeApiException.NotAuthenticated("OAuth token exchange failed (HTTP $code).")
            }
            body
        } finally {
            conn.disconnect()
        }
    }

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun computeS256Challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    companion object {
        const val RedirectUri = "com.podcasty.app:/oauth2redirect"
        const val YouTubeCaptionsScope = "https://www.googleapis.com/auth/youtube.force-ssl"
        private const val PrefsName = "podcasty_youtube_oauth_vault"
        private const val KeyStoreAlias = "podcasty_yt_oauth_aes_gcm"
        private const val KeyClientId = "oauth_client_id"
        private const val KeyEncryptedAccessToken = "enc_access_token"
        private const val KeyEncryptedRefreshToken = "enc_refresh_token"
        private const val KeyTokenExpiresAtMs = "token_expires_at_ms"
    }
}
