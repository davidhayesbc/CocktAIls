package com.davidhayesbc.cocktails.android

import android.os.Bundle
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Text
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.davidhayesbc.cocktails.android.product.BundledReferenceProducts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.davidhayesbc.cocktails.android.ui.CocktailsApp
import com.davidhayesbc.cocktails.android.ui.theme.CocktailsTheme
import com.davidhayesbc.cocktails.android.product.DetailedBottleLookupCaptureCleanupScheduler
import com.davidhayesbc.cocktails.core.data.LocalCocktailRepository
import com.davidhayesbc.cocktails.shared.db.CocktailsDatabase

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DetailedBottleLookupCaptureCleanupScheduler.schedule(applicationContext)
        val appInfo = packageManager.getPackageInfo(packageName, 0)
        @Suppress("DEPRECATION")
        LocalDiagnostics.initialize(filesDir, appInfo.versionName ?: "unknown", appInfo.versionCode.toLong())
        val driver = AndroidSqliteDriver(CocktailsDatabase.Schema, this, "cocktails.db")
        val repository = LocalCocktailRepository(CocktailsDatabase(driver))
        repository.initialize(System.currentTimeMillis())
        val referenceReady = mutableStateOf(repository.referenceCatalogueIsAtLeast("bc-liquor-products", "2026-07", 1))
        if (!referenceReady.value) lifecycleScope.launch {
            val installed = withContext(Dispatchers.IO) {
                runCatching { BundledReferenceProducts.install(applicationContext, repository) }.isSuccess
            }
            referenceReady.value = true
            if (!installed) Toast.makeText(applicationContext, "BC reference data could not be installed. Restart to retry.", Toast.LENGTH_LONG).show()
        }
        setContent {
            CocktailsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    if (referenceReady.value) CocktailsApp(repository, onExitApp = ::finish)
                    else Text("Preparing offline product reference…")
                }
            }
        }
    }
}
