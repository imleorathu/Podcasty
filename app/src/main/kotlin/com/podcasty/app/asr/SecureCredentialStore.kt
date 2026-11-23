/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.asr

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Hardware-backed Android KeyStore AES-256-GCM vault for optional user-supplied credentials
 * (ASR backend bearer token and optional YouTube Data API v3 key).
 *
 * Credentials are never hardcoded, never stored in plaintext SharedPreferences, and never logged.
 */
class SecureCredentialStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs by lazy {
        appContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
    }

    fun saveAsrAuthToken(token: String?) {
        saveSecret(KeyAsrAuthToken, token)
    }

    fun getAsrAuthToken(): String? = readSecret(KeyAsrAuthToken)

    fun hasAsrAuthToken(): Boolean = !getAsrAuthToken().isNullOrBlank()

    fun saveYouTubeApiKey(apiKey: String?) {
        saveSecret(KeyYouTubeApiKey, apiKey)
    }

    fun getYouTubeApiKey(): String? = readSecret(KeyYouTubeApiKey)

    fun hasYouTubeApiKey(): Boolean = !getYouTubeApiKey().isNullOrBlank()

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun saveSecret(key: String, rawValue: String?) {
        val cleaned = rawValue?.trim()
        if (cleaned.isNullOrEmpty()) {
            prefs.edit().remove(key).apply()
            return
        }
        val encrypted = encrypt(cleaned)
        prefs.edit().putString(key, encrypted).apply()
    }

    private fun readSecret(key: String): String? {
        val payload = prefs.getString(key, null) ?: return null
        return runCatching { decrypt(payload) }.getOrNull()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(AndroidKeyStore).apply { load(null) }
        val existing = keyStore.getEntry(KeyAlias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, AndroidKeyStore)
        generator.init(
            KeyGenParameterSpec.Builder(
                KeyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(Transformation)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(1 + iv.size + cipherBytes.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(cipherBytes, 0, combined, 1 + iv.size, cipherBytes.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String? {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        if (combined.size < 14) return null
        val ivLen = combined[0].toInt() and 0xFF
        if (ivLen < 12 || combined.size <= 1 + ivLen) return null
        val iv = combined.copyOfRange(1, 1 + ivLen)
        val cipherBytes = combined.copyOfRange(1 + ivLen, combined.size)
        val cipher = Cipher.getInstance(Transformation)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
    }

    private companion object {
        const val AndroidKeyStore = "AndroidKeyStore"
        const val KeyAlias = "podcasty_asr_credentials_aes_v1"
        const val Transformation = "AES/GCM/NoPadding"
        const val PrefsName = "podcasty_secure_credentials_v1"
        const val KeyAsrAuthToken = "enc_asr_auth_token"
        const val KeyYouTubeApiKey = "enc_youtube_data_api_key"
    }
}
