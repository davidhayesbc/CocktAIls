package com.davidhayesbc.cocktails.android.product

import android.content.Context

/** Firecrawl key storage isolated from other provider credentials. */
internal class FirecrawlCredentialStore(context: Context) {
    private val delegate = AndroidCredentialStore(
        context = context,
        preferencesName = "firecrawl_credential_ciphertext",
        ciphertextName = "api_key_aes_gcm_v1",
        keyAlias = "com.davidhayesbc.cocktails.firecrawl-api-key.v1",
    )

    fun hasSavedKey(): Boolean = delegate.hasSavedValue()
    fun readApiKey(): String? = delegate.readValue()
    fun saveApiKey(apiKey: String): Boolean = delegate.saveValue(apiKey)
    fun clear(): Boolean = delegate.clearValue()
}
