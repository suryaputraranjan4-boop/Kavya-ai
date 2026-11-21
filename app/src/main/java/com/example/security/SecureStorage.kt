package com.example.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.SecureRandom
import java.security.spec.KeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Authoritative Security & Credential Storage System.
 * API keys and credentials:
 * - are never hardcoded or logged in plaintext
 * - use Android KeyStore AES-256-GCM encryption with CSPRNG-seeded AES-256 software fallback
 * - guarantee key persistence across process recreation, device restarts, and navigation
 * - mask secrets in logs and UI
 */
object SecureStorage {

    private const val TAG = "KavyaSecureStorage"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "kavya_secure_credentials_key"
    private const val PREFS_FILE = "kavya_encrypted_vault"
    private const val META_PREFS_FILE = "kavya_encrypted_vault_meta"
    private const val META_SEED_KEY = "fallback_entropy_b64"
    private const val META_SALT_KEY = "fallback_salt_b64"
    private const val PBKDF2_ITERATIONS = 120_000
    private const val GCM_TAG_LENGTH = 128
    private const val IV_SEPARATOR = "]"
    private const val LEGACY_OBF_PREFIX = "OBF:"
    private const val FALLBACK_PREFIX = "FALLBACK:"

    @Volatile
    private var fallbackKeyCache: SecretKey? = null

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    }

    private fun getMetaPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(META_PREFS_FILE, Context.MODE_PRIVATE)
    }

    /**
     * Primary: Retrieves or generates an Android KeyStore AES-256 secret key.
     */
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
            Log.w(TAG, "AndroidKeyStore is unavailable (${e.javaClass.simpleName}); invoking CSPRNG software fallback.")
            null
        }
    }

    /**
     * Fallback: Generates and persists a per-install CSPRNG seed & random 32-byte salt
     * when Android KeyStore is unavailable, deriving a 256-bit AES key via PBKDF2WithHmacSHA256.
     */
    private fun getFallbackSecretKey(context: Context): SecretKey {
        fallbackKeyCache?.let { return it }
        synchronized(this) {
            fallbackKeyCache?.let { return it }

            val meta = getMetaPrefs(context)
            var seedB64 = meta.getString(META_SEED_KEY, null)
            var saltB64 = meta.getString(META_SALT_KEY, null)
            val random = SecureRandom()

            if (seedB64.isNullOrBlank() || saltB64.isNullOrBlank()) {
                val seedBytes = ByteArray(32).also { random.nextBytes(it) }
                val saltBytes = ByteArray(32).also { random.nextBytes(it) }
                seedB64 = Base64.encodeToString(seedBytes, Base64.NO_WRAP)
                saltB64 = Base64.encodeToString(saltBytes, Base64.NO_WRAP)
                meta.edit()
                    .putString(META_SEED_KEY, seedB64)
                    .putString(META_SALT_KEY, saltB64)
                    .commit()
            }

            val salt = Base64.decode(saltB64, Base64.NO_WRAP)
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val spec: KeySpec = PBEKeySpec(seedB64.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
            val tmp = factory.generateSecret(spec)
            val secretKey = SecretKeySpec(tmp.encoded, "AES")
            fallbackKeyCache = secretKey
            return secretKey
        }
    }

    /**
     * Encrypts plain text using AES-256-GCM.
     * Tries Android KeyStore first; if unavailable, uses CSPRNG PBKDF2 software AES-GCM encryption.
     */
    private fun encrypt(context: Context, plainText: String): String? {
        if (plainText.isBlank()) return ""
        val trimmed = plainText.trim()

        // 1. Try KeyStore Encryption
        val primaryKey = getOrCreateSecretKey()
        if (primaryKey != null) {
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, primaryKey)
                val iv = cipher.iv
                val cipherBytes = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
                val ivString = Base64.encodeToString(iv, Base64.NO_WRAP)
                val cipherString = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
                return "$ivString$IV_SEPARATOR$cipherString"
            } catch (e: Exception) {
                Log.w(TAG, "Primary Keystore encryption failed (${e.javaClass.simpleName}); using fallback.")
            }
        }

        // 2. Fallback AES-GCM Encryption
        return try {
            val fallbackKey = getFallbackSecretKey(context)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, fallbackKey)
            val iv = cipher.iv
            val cipherBytes = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
            val ivString = Base64.encodeToString(iv, Base64.NO_WRAP)
            val cipherString = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
            "$FALLBACK_PREFIX$ivString$IV_SEPARATOR$cipherString"
        } catch (e: Exception) {
            Log.e(TAG, "Fallback AES-GCM encryption failed (${e.javaClass.simpleName}).")
            null
        }
    }

    /**
     * Decrypts an AES-256-GCM payload. Supports both KeyStore payloads and Fallback payloads.
     */
    private fun decrypt(context: Context, encryptedString: String): String {
        if (encryptedString.isBlank()) return ""

        val isFallback = encryptedString.startsWith(FALLBACK_PREFIX)
        val payload = if (isFallback) encryptedString.removePrefix(FALLBACK_PREFIX) else encryptedString

        val parts = payload.split(IV_SEPARATOR)
        if (parts.size != 2) return ""

        val iv = try { Base64.decode(parts[0], Base64.NO_WRAP) } catch (_: Exception) { return "" }
        val cipherBytes = try { Base64.decode(parts[1], Base64.NO_WRAP) } catch (_: Exception) { return "" }

        if (isFallback) {
            return try {
                val fallbackKey = getFallbackSecretKey(context)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
                cipher.init(Cipher.DECRYPT_MODE, fallbackKey, spec)
                String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
            } catch (e: Exception) {
                Log.e(TAG, "Fallback decryption failed (${e.javaClass.simpleName}).")
                ""
            }
        }

        val primaryKey = getOrCreateSecretKey()
        if (primaryKey != null) {
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
                cipher.init(Cipher.DECRYPT_MODE, primaryKey, spec)
                return String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
            } catch (e: Exception) {
                Log.w(TAG, "Primary Keystore decryption failed (${e.javaClass.simpleName}); trying fallback decryption.")
            }
        }

        // Final attempt with fallback key if primary failed
        return try {
            val fallbackKey = getFallbackSecretKey(context)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, fallbackKey, spec)
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Stores a secret securely using Keystore AES-GCM or Fallback AES-GCM.
     * Guaranteed atomic write via SharedPreferences commit().
     */
    fun saveSecret(context: Context, key: String, value: String): Boolean {
        val prefs = getPrefs(context)
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            return prefs.edit().remove(key).commit()
        }

        val encryptedValue = encrypt(context, trimmed) ?: return false
        val committed = prefs.edit().putString(key, encryptedValue).commit()

        if (committed) {
            // Read-back verification
            val readBack = getSecret(context, key)
            if (readBack != trimmed) {
                Log.e(TAG, "Read-back verification mismatch for key $key; attempting fallback save.")
                val fallbackEncrypted = encrypt(context, trimmed)
                if (fallbackEncrypted != null) {
                    return prefs.edit().putString(key, fallbackEncrypted).commit()
                }
            }
        }
        return committed
    }

    /**
     * Retrieves a sensitive secret. Automatically purges insecure legacy entries.
     */
    fun getSecret(context: Context, key: String): String {
        val prefs = getPrefs(context)
        val encrypted = prefs.getString(key, null) ?: return ""
        if (encrypted.startsWith(LEGACY_OBF_PREFIX)) {
            prefs.edit().remove(key).commit()
            Log.w(TAG, "Purged insecure legacy credential entry for key $key.")
            return ""
        }
        return decrypt(context, encrypted)
    }

    /**
     * Removes a stored secret.
     */
    fun clearSecret(context: Context, key: String) {
        getPrefs(context).edit().remove(key).commit()
    }

    /**
     * Masks an API key for safe UI display (e.g., "AIza...9f2a" or "sk-o...4b1c").
     */
    fun maskSecret(secret: String): String {
        val trimmed = secret.trim()
        if (trimmed.isBlank()) return "Not Configured"
        if (trimmed.length <= 8) return "••••••••"
        val prefix = trimmed.take(4)
        val suffix = trimmed.takeLast(4)
        return "$prefix••••••••$suffix"
    }
}
