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

/** Provider-specific encrypted values; each provider gets a distinct preference entry and Keystore key. */
internal class AndroidCredentialStore(
    context: Context,
    preferencesName: String,
    private val ciphertextName: String,
    private val keyAlias: String,
) {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    @Synchronized
    fun hasSavedValue(): Boolean = preferences.contains(ciphertextName)

    @Synchronized
    fun readValue(): String? {
        val encoded = preferences.getString(ciphertextName, null) ?: return null
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

    @Synchronized
    fun saveValue(value: String): Boolean {
        val cleaned = value.trim()
        if (cleaned.isEmpty() || cleaned.length > MAX_VALUE_CHARS) return false
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = cipher.doFinal(cleaned.toByteArray(Charsets.UTF_8))
            val encoded = Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
            preferences.edit().putString(ciphertextName, encoded).commit()
        }.getOrDefault(false)
    }

    @Synchronized
    fun clearValue(): Boolean {
        val preferenceClearSucceeded = preferences.edit().remove(ciphertextName).commit()
        val keyClearSucceeded = runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
            true
        }.getOrDefault(false)
        return preferenceClearSucceeded && keyClearSucceeded
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
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
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        const val MAX_VALUE_CHARS = 4096
    }
}
