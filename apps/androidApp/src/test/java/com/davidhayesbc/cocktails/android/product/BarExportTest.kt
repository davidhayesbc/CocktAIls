package com.davidhayesbc.cocktails.android.product

import com.davidhayesbc.cocktails.core.model.IngredientId
import com.davidhayesbc.cocktails.core.model.InventoryLevel
import com.davidhayesbc.cocktails.core.model.PantryEntry
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BarExportTest {
    @Test
    fun exportsFifteenConfirmedPhysicalBottlesWithDistinctIdsAndStableSchema() {
        val entries = (1..15).map { number ->
            PantryEntry(
                inventoryId = "bottle-$number",
                ingredientId = IngredientId("ingredient-whisky"),
                name = "Whisky",
                category = "Whisky",
                level = InventoryLevel.FULL,
                productId = "product-same",
                productName = "Three Wood",
                brand = "Auchentoshan",
                barcode = "5011333150725",
                bottleSize = "750 mL",
                abvPercent = "43%",
            )
        }

        val root = Json.parseToJsonElement(buildBarExportJson(entries, 1234)).jsonObject
        val bottles = root.getValue("bottles").jsonArray

        assertEquals("com.davidhayesbc.cocktails.bar-export", root.getValue("format").jsonPrimitive.content)
        assertEquals("1", root.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("15", root.getValue("bottleCount").jsonPrimitive.content)
        assertEquals(15, bottles.size)
        assertEquals(15, bottles.map { it.jsonObject.getValue("bottleId").jsonPrimitive.content }.toSet().size)
        assertEquals("5011333150725", bottles.first().jsonObject.getValue("barcode").jsonPrimitive.content)
        assertEquals("5011333150725", bottles.first().jsonObject.getValue("normalizedBarcode").jsonPrimitive.content)
        val size = bottles.first().jsonObject.getValue("bottleSize").jsonObject
        assertEquals("750", size.getValue("value").jsonPrimitive.content)
        assertEquals("mL", size.getValue("unit").jsonPrimitive.content)
        assertEquals("43%", bottles.first().jsonObject.getValue("abvPercent").jsonPrimitive.content)
        assertTrue(root.getValue("scope").jsonPrimitive.content.contains("unfinished intake drafts are excluded"))
        assertFalse("Export excludes private photos, notes and home/account ids", bottles.any { bottle -> bottle.jsonObject.keys.any {
            it.contains("photo", true) || it.contains("note", true) || it.contains("location", true) || it.contains("account", true)
        } })
    }

    @Test
    fun preservesUnicodeAndEscapesNamesAndRepresentsMissingFactsAsNull() {
        val item = PantryEntry(
            inventoryId = "bottle-unicode",
            ingredientId = IngredientId("ingredient-1"),
            name = "Spirit",
            category = "Other",
            level = InventoryLevel.LOW,
            productId = null,
            productName = "Crème \"Étoile\" \\\n Reserve",
            brand = null,
            barcode = null,
        )
        val root = Json.parseToJsonElement(buildBarExportJson(listOf(item), 0)).jsonObject
        val bottle = root.getValue("bottles").jsonArray.single().jsonObject

        assertEquals("Crème \"Étoile\" \\\n Reserve", bottle.getValue("name").jsonPrimitive.content)
        assertEquals("null", bottle.getValue("barcode").toString())
        assertEquals("null", bottle.getValue("normalizedBarcode").toString())
        assertEquals("null", bottle.getValue("abvPercent").toString())
        assertEquals("null", bottle.getValue("bottleSize").toString())
    }

    @Test
    fun emptyBarRoundTripsAndCancelledOrFailedDocumentWritesAreReportedTruthfully() {
        val empty = Json.parseToJsonElement(buildBarExportJson(emptyList(), 10)).jsonObject
        assertEquals("0", empty.getValue("bottleCount").jsonPrimitive.content)
        assertEquals(0, empty.getValue("bottles").jsonArray.size)
        assertEquals("Bar export canceled. No file was written.", barExportCompletionMessage(false, null))

        val bytes = ByteArrayOutputStream()
        assertTrue(writeBarExport(bytes, buildBarExportJson(emptyList(), 10)))
        assertEquals(empty.toString(), Json.parseToJsonElement(bytes.toString(Charsets.UTF_8.name())).toString())
        assertEquals("Bar export saved. It includes confirmed bottles in the selected home; unfinished drafts are excluded.", barExportCompletionMessage(true, true))

        val failing = object : OutputStream() {
            override fun write(value: Int) { throw IOException("synthetic destination failure") }
        }
        assertFalse(writeBarExport(failing, "{}"))
        assertTrue(barExportCompletionMessage(true, false).contains("Bar export failed"))
    }
}
