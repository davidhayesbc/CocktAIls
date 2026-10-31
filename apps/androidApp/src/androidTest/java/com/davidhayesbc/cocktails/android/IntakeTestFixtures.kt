package com.davidhayesbc.cocktails.android

import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.platform.app.InstrumentationRegistry
import com.davidhayesbc.cocktails.core.data.LocalCocktailRepository
import com.davidhayesbc.cocktails.shared.db.CocktailsDatabase
import org.junit.Assert.assertEquals

/** Keep route failures useful even when CI only exposes the assertion's first line. */
internal fun ComposeTestRule.assertIntakeRoute(expected: String, action: String) {
    val titles = onAllNodesWithTag("bottle-intake-route-title").fetchSemanticsNodes()
    val actual = titles.joinToString(" | ") { node ->
        node.config[SemanticsProperties.Text].joinToString { it.text }
    }.ifEmpty { "<missing route title>" }
    val manualErrors = onAllNodesWithTag("batch-manual-error").fetchSemanticsNodes().joinToString(" | ") { node ->
        node.config[SemanticsProperties.Text].joinToString { it.text }
    }.ifEmpty { "none" }
    assertEquals("Intake route $action; manual submission error=$manualErrors", expected, actual)
}

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
