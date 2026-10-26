package com.davidhayesbc.cocktails.android.product

import android.content.Context

/** Ollama Cloud key is encrypted independently from Firecrawl's key. */
internal class OllamaCloudCredentialStore(context: Context) {
    private val delegate = AndroidCredentialStore(
        context = context,
        preferencesName = "ollama_cloud_credential_ciphertext",
        ciphertextName = "api_key_aes_gcm_v1",
        keyAlias = "com.davidhayesbc.cocktails.ollama-cloud-api-key.v1",
    )

    fun hasSavedKey(): Boolean = delegate.hasSavedValue()
    fun readApiKey(): String? = delegate.readValue()
    fun saveApiKey(apiKey: String): Boolean = delegate.saveValue(apiKey)
    fun clear(): Boolean = delegate.clearValue()
}
