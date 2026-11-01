package com.davidhayesbc.cocktails.android

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.davidhayesbc.cocktails.android.ui.CocktailsApp
import com.davidhayesbc.cocktails.android.ui.theme.CocktailsTheme
import com.davidhayesbc.cocktails.core.data.LocalCocktailRepository
import com.davidhayesbc.cocktails.shared.db.CocktailsDatabase
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.UUID

/** Test-only injection after activity creation, including recreation; never deletes the household DB. */
internal class CatalogDatabaseFixture : TestRule {
    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            if (description.methodName !in setOf(
                    "bundledCatalogLoadsOfflineAndStaysInstalledAfterActivityRecreation",
                    "combinedStarterCollectionInstallsWithNoticesAndPersistsOffline",
                )) { base.evaluate(); return }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val app = instrumentation.targetContext.applicationContext as Application
            val name = "instrumentation-catalog-${UUID.randomUUID()}.db"
            val drivers = mutableMapOf<Activity, AndroidSqliteDriver>()
            val callbacks = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    if (activity !is MainActivity || activity in drivers) return
                    val driver = AndroidSqliteDriver(CocktailsDatabase.Schema, app, name)
                    drivers[activity] = driver
                    val repository = LocalCocktailRepository(CocktailsDatabase(driver))
                    repository.initialize(System.currentTimeMillis())
                    activity.setContent {
                        CocktailsTheme {
                            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                                CocktailsApp(repository, onExitApp = activity::finish)
                            }
                        }
                    }
                }
                override fun onActivityDestroyed(activity: Activity) { drivers.remove(activity)?.close() }
                override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            }
            instrumentation.runOnMainSync { app.registerActivityLifecycleCallbacks(callbacks) }
            try { base.evaluate() } finally {
                // This outer rule runs after the Compose/activity rule has closed its scenario.
                instrumentation.runOnMainSync {
                    app.unregisterActivityLifecycleCallbacks(callbacks)
                    drivers.values.forEach { it.close() }; drivers.clear()
                }
                check(app.deleteDatabase(name)) { "Could not remove the isolated catalog test database" }
            }
        }
    }
}
