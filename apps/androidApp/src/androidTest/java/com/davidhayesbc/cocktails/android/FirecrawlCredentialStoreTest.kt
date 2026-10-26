package com.davidhayesbc.cocktails.android

import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davidhayesbc.cocktails.android.product.FirecrawlCredentialStore
import com.davidhayesbc.cocktails.android.product.OllamaCloudCredentialStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirecrawlCredentialStoreTest {
    @Test
    fun keystoreEncryptedKeySurvivesStoreRecreationAndCanBeReplacedOrDeleted() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = FirecrawlCredentialStore(context)
        val ollamaStore = OllamaCloudCredentialStore(context)
        store.clear()
        ollamaStore.clear()
        try {
            val firstKey = "synthetic-firecrawl-key-for-instrumentation-only"
            assertTrue(store.saveApiKey(firstKey))
            assertTrue(store.hasSavedKey())
            val savedCiphertext = context.getSharedPreferences("firecrawl_credential_ciphertext", 0)
                .getString("api_key_aes_gcm_v1", null)
            assertTrue(savedCiphertext != null)
            assertFalse(savedCiphertext!!.contains(firstKey))
            assertEquals(firstKey, FirecrawlCredentialStore(context).readApiKey())

            val replacement = "synthetic-replacement-firecrawl-key"
            assertTrue(store.saveApiKey(replacement))
            val replacementCiphertext = context.getSharedPreferences("firecrawl_credential_ciphertext", 0)
                .getString("api_key_aes_gcm_v1", null)
            assertFalse(savedCiphertext == replacementCiphertext)
            assertEquals(replacement, FirecrawlCredentialStore(context).readApiKey())

            val ollamaKey = "synthetic-ollama-cloud-key"
            assertTrue(ollamaStore.saveApiKey(ollamaKey))
            val ollamaCiphertext = context.getSharedPreferences("ollama_cloud_credential_ciphertext", 0)
                .getString("api_key_aes_gcm_v1", null)
            assertTrue(ollamaCiphertext != null)
            assertFalse(ollamaCiphertext!!.contains(ollamaKey))
            assertEquals(ollamaKey, OllamaCloudCredentialStore(context).readApiKey())
            assertEquals(replacement, FirecrawlCredentialStore(context).readApiKey())

            store.clear()
            assertFalse(store.hasSavedKey())
            assertNull(store.readApiKey())
            assertTrue(ollamaStore.clear())
            assertNull(ollamaStore.readApiKey())
            assertFalse(context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0)
        } finally {
            store.clear()
            ollamaStore.clear()
        }
    }
}
