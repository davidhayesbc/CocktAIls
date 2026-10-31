package com.davidhayesbc.cocktails.android

import android.os.Bundle
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
        setContent {
            CocktailsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    CocktailsApp(repository, onExitApp = ::finish)
                }
            }
        }
    }
}
