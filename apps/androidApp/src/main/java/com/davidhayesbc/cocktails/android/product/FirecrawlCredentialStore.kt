package com.davidhayesbc.cocktails.android.product

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores only AES-GCM ciphertext in app-private preferences; the AES key stays in Android Keystore. */
internal class FirecrawlCredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)

    @Synchronized
    fun hasSavedKey(): Boolean = preferences.contains(CIPHERTEXT_KEY)

    /** Returns null when unset or when Keystore decryption fails; never falls back to plaintext. */
    @Synchronized
    fun readApiKey(): String? {
        val encoded = preferences.getString(CIPHERTEXT_KEY, null) ?: return null
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            require(payload.size > NONCE_BYTES)
            val nonce = payload.copyOfRange(0, NONCE_BYTES)
            val encrypted = payload.copyOfRange(NONCE_BYTES, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, nonce))
            String(cipher.doFinal(encrypted), Charsets.UTF_8).takeIf(String::isNotEmpty)
        }.getOrNull()
    }

    /** Saving is an explicit Settings action. Only bounded non-empty API key text is accepted. */
    @Synchronized
    fun saveApiKey(apiKey: String): Boolean {
        val value = apiKey.trim()
        if (value.isEmpty() || value.length > MAX_API_KEY_CHARS) return false
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val encoded = Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
            preferences.edit().putString(CIPHERTEXT_KEY, encoded).commit()
        }.getOrDefault(false)
    }

    @Synchronized
    fun clear() {
        preferences.edit().remove(CIPHERTEXT_KEY).commit()
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "com.davidhayesbc.cocktails.firecrawl-api-key.v1"
        const val PREFERENCES_FILE = "firecrawl_credential_ciphertext"
        const val CIPHERTEXT_KEY = "api_key_aes_gcm_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        const val MAX_API_KEY_CHARS = 4096
    }
}
