package com.davidhayesbc.cocktails.android

import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import androidx.test.platform.app.InstrumentationRegistry
import com.davidhayesbc.cocktails.core.data.LocalCocktailRepository
import com.davidhayesbc.cocktails.shared.db.CocktailsDatabase

/** Remove only the named synthetic draft; navigation tests share the instrumentation app database. */
internal fun removeIntakeTestDraft(name: String, barcode: String) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val driver = AndroidSqliteDriver(CocktailsDatabase.Schema, context, "cocktails.db")
    try {
        val repository = LocalCocktailRepository(CocktailsDatabase(driver))
        val session = repository.openBottleIntakeSession() ?: return
        val fixtures = session.drafts.filter { it.ingredientName == name && it.barcode == barcode }
        fixtures.forEach { repository.removeBottleIntakeDraft(session.id, it.id) }
        if (fixtures.isNotEmpty() && repository.openBottleIntakeSession()?.drafts?.isEmpty() == true) {
            repository.cancelBottleIntakeSession(session.id)
        }
    } finally {
        driver.close()
    }
}
