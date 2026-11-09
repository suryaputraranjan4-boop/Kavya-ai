package com.example.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Requirement 19: Security.
 * API keys and tokens must:
 * - never be hardcoded
 * - never be committed into source control
 * - never appear in logs
 * - be stored securely using Android Keystore-backed AES-GCM encryption
 * - be masked in UI
 */
object SecureStorage {

    private const val TAG = "KavyaSecureStorage"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "kavya_secure_credentials_key"
    private const val PREFS_FILE = "kavya_encrypted_vault"
    private const val GCM_TAG_LENGTH = 128
    private const val IV_SEPARATOR = "]"
    private const val LEGACY_OBF_PREFIX = "OBF:"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    }

    private fun getOrCreateSecretKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)

            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE
                )
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            } else {
                val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
                entry?.secretKey
            }
        } catch (e: Exception) {
            // Never downgrade credential protection when Android Keystore is unavailable.
            Log.w(TAG, "AndroidKeyStore is unavailable (${e.javaClass.simpleName}).")
            null
        }
    }

    /**
     * Encrypts plain text using AES-256-GCM.
     * Returns null when encryption is unavailable; plaintext fallbacks are forbidden.
     */
    private fun encrypt(plainText: String): String? {
        if (plainText.isBlank()) return ""
        val secretKey = getOrCreateSecretKey() ?: return null

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val ivString = Base64.encodeToString(iv, Base64.NO_WRAP)
            val cipherString = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
            "$ivString$IV_SEPARATOR$cipherString"
        } catch (e: Exception) {
            Log.e(TAG, "AES-GCM encryption failed (${e.javaClass.simpleName}).")
            null
        }
    }

    /**
     * Decrypts an Android Keystore AES-GCM payload.
     */
    private fun decrypt(encryptedString: String): String {
        if (encryptedString.isBlank()) return ""

        val secretKey = getOrCreateSecretKey() ?: return ""
        val parts = encryptedString.split(IV_SEPARATOR)
        if (parts.size != 2) return ""

        return try {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val cipherBytes = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            val decryptedBytes = cipher.doFinal(cipherBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed (${e.javaClass.simpleName}).")
            ""
        }
    }

    /**
     * Stores a sensitive secret only when Android Keystore AES-GCM encryption succeeds.
     *
     * @return true when the encrypted value was committed, false when encryption or
     * persistence failed. Existing callers may safely ignore the result, but must not
     * assume a credential was saved when false is returned.
     */
    fun saveSecret(context: Context, key: String, value: String): Boolean {
        val prefs = getPrefs(context)
        if (value.isBlank()) {
            return prefs.edit().remove(key).commit()
        }

        val encryptedValue = encrypt(value.trim()) ?: return false
        return prefs.edit().putString(key, encryptedValue).commit()
    }

    /**
     * Retrieves a sensitive secret. Legacy OBF values were only Base64-encoded and
     * are deliberately discarded; users must re-enter those credentials.
     */
    fun getSecret(context: Context, key: String): String {
        val prefs = getPrefs(context)
        val encrypted = prefs.getString(key, null) ?: return ""
        if (encrypted.startsWith(LEGACY_OBF_PREFIX)) {
            prefs.edit().remove(key).apply()
            Log.w(TAG, "Removed an insecure legacy credential entry.")
            return ""
        }
        return decrypt(encrypted)
    }

    /**
     * Removes a stored secret.
     */
    fun clearSecret(context: Context, key: String) {
        getPrefs(context).edit().remove(key).apply()
    }

    /**
     * Masks an API key for safe UI display (e.g., "sk-or-...9f2a").
     * Never exposes raw keys in UI or logs.
     */
    fun maskSecret(secret: String): String {
        if (secret.length <= 8) return "••••••••"
        val prefix = secret.take(4)
        val suffix = secret.takeLast(4)
        return "$prefix••••••••$suffix"
    }
}
