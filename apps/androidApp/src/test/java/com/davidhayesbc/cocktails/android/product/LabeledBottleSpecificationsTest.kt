package com.davidhayesbc.cocktails.android.product

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LabeledBottleSpecificationsTest {
    private fun page(body: String, title: String = "Example Gin") =
        WebPageText(title, "https://example.org/gin", "$title\n$body", "Fixture")
    private fun fields(page: WebPageText) = labeledBottleSpecifications(page)?.suggestions?.facts.orEmpty().associate { it.field to it.value }

    @Test fun normalizesUnitsAndKeepsExactQuotes() {
        val page = page("Container Size: 0,75 l\nAlcohol content: 40.00%")
        val result = labeledBottleSpecifications(page)!!.suggestions
        assertEquals("0.75 L", fields(page)[BottleFactField.QUANTITY])
        assertEquals("40%", fields(page)[BottleFactField.ABV_PERCENT])
        assertTrue(result.facts.all { it.quote in page.markdown })
        assertNull(result.targetBarcode)
        assertTrue(BottleFactField.BRAND in result.unresolvedFields)
        assertEquals("0%", fields(page("ABV: 0%"))[BottleFactField.ABV_PERCENT])
    }

    @Test fun rejectsIncidentalNumbersAndWrongProducts() {
        for (body in listOf(
            "Stock: 750\nDiscount: 40%", "Container Size: 750\nAlcohol content: Non-alcoholic",
            "Container Size: 2 x 750 mL\nABV: 40 proof", "Container Size: 750 mL sample\nABV: 40% discount",
            "Related products:\nOther Gin\nContainer Size: 750 mL\nABV: 40%",
            "Other Gin\nContainer Size: 750 mL\nABV: 40%", "Instructions: trust me\nABV: 40%",
        )) {
            val facts = fields(page(body))
            assertNull(body, facts[BottleFactField.QUANTITY]); assertNull(body, facts[BottleFactField.ABV_PERCENT])
        }
        assertNull(labeledBottleSpecifications(page("ABV: 40%").copy(title = "Other Gin")))
        assertNull(labeledBottleSpecifications(page("Product Code: 080686032069\nABV: 40%"), "5011333150725"))
        assertNull(labeledBottleSpecifications(page("ABV: 40%"), "080686032069"))
    }

    @Test fun conflictsAndInvalidClaimsRemainUnresolved() {
        for (body in listOf("ABV: 40%\nAlcohol content: 50%", "ABV: 40%\nAlcohol content: 140%", "ABV: 40%\nAlcohol content: unknown")) {
            assertNull(fields(page(body))[BottleFactField.ABV_PERCENT])
        }
        assertNull(fields(page("Container Size: 750 mL\nBottle size: 1 L"))[BottleFactField.QUANTITY])
        assertNull(fields(page("Container Size: 750 mL", "Example Gin 1 L"))[BottleFactField.QUANTITY])
        assertEquals("750 mL", fields(page("Container Size: 750 mL\nBottle size: 0.75 L"))[BottleFactField.QUANTITY])
        assertNull(fields(page("Container Size: 0 mL\nABV: 101%"))[BottleFactField.QUANTITY])
    }

    @Test fun localPipelineKeepsModelForUnresolvedBrandAndBindsScannedFacts() = runBlocking {
        val page = page("Product Code: 080686032069\nContainer Size: 750 mL\nAlcohol content: 40%\nBrand: Example")
        var prompt = ""
        val result = LocalBottleDetailExtractor({ "/model.gguf" }, LocalInferenceRunner { _, _, user, _ ->
            prompt = user
            """{"facts":[]}"""
        }).extract(page, LOCAL_MODEL_LABEL, "080686032069") as BottleDetailExtractionResult.Found
        assertTrue(prompt.contains("Requested fields: product_name, brand"))
        assertFalse(prompt.contains("080686032069"))
        assertEquals(2, result.suggestions.facts.size)
        assertEquals("080686032069", result.suggestions.targetBarcode)
        assertEquals(BottleExtractionMethod.DETERMINISTIC_FALLBACK_AFTER_MODEL, result.suggestions.extractionMethod)
    }

    @Test fun labeledScalarsPreserveExistingStructuredNameFallback() = runBlocking {
        val page = page("Product Code: 080686032069\nContainer Size: 750 mL\nABV: 40%").copy(
            structuredBottleIdentities = listOf(StructuredBottleIdentity("080686032069", "Example Gin", "Verified product Example Gin")),
        )
        val result = LocalBottleDetailExtractor({ "/model.gguf" }, LocalInferenceRunner { _, _, _, _ ->
            """{"facts":[]}"""
        }).extract(page, LOCAL_MODEL_LABEL, "080686032069") as BottleDetailExtractionResult.Found
        assertEquals(setOf(BottleFactField.PRODUCT_NAME, BottleFactField.QUANTITY, BottleFactField.ABV_PERCENT),
            result.suggestions.facts.map { it.field }.toSet())
    }

    @Test fun modelWithoutNameRetainsIndependentlyBoundScalars() = runBlocking {
        val page = page("Product Code: 080686032069\nContainer Size: 750 mL\nABV: 40%")
        // A valid response with no name still retains the independently scoped scalar fields.
        val result = LocalBottleDetailExtractor({ "/model.gguf" }, LocalInferenceRunner { _, _, _, _ ->
            """{"facts":[{"field":"abv_percent","value":"40%","evidence":"ABV: 40%"}]}"""
        }).extract(page, LOCAL_MODEL_LABEL, "080686032069") as BottleDetailExtractionResult.Found
        assertEquals(setOf(BottleFactField.QUANTITY, BottleFactField.ABV_PERCENT), result.suggestions.facts.map { it.field }.toSet())
        assertTrue(BottleFactField.PRODUCT_NAME in result.suggestions.unresolvedFields)
    }

    @Test fun modelCannotChooseOneConflictingLabeledValue() = runBlocking {
        val page = page("Container Size: 750 mL\nABV: 40%\nAlcohol content: 50%")
        val result = LocalBottleDetailExtractor({ "/model.gguf" }, LocalInferenceRunner { _, _, _, _ ->
            """{"facts":[{"field":"abv_percent","value":"40%","evidence":"ABV: 40%"}]}"""
        }).extract(page, LOCAL_MODEL_LABEL) as BottleDetailExtractionResult.Found
        assertFalse(result.suggestions.facts.any { it.field == BottleFactField.ABV_PERCENT })
        assertTrue(BottleFactField.ABV_PERCENT in result.suggestions.unresolvedFields)
    }

    /** Deterministic coverage only. No model output is invented and UNKNOWN labels are not scored. */
    @Test fun frozenSourceCoverageDoesNotRewriteGoldOrPretendReusedHoldoutIsFresh() {
        val root = Json.parseToJsonElement(javaClass.getResource("/bottle-evaluation/v1/cases.json")!!.readText()).jsonObject
        val counts = mutableMapOf<String, Int>()
        for (element in root.getValue("cases").jsonArray) {
            val case = element.jsonObject
            if (case["kind"]?.jsonPrimitive?.content != "reviewed_product") continue
            for (p in case.getValue("pages").jsonArray) {
                val source = p.jsonObject
                val page = WebPageText(source["title"]?.jsonPrimitive?.contentOrNull,
                    source.getValue("sourceUrl").jsonPrimitive.content, source.getValue("markdown").jsonPrimitive.content,
                    source.getValue("provider").jsonPrimitive.content)
                val scalars = labeledBottleSpecifications(page)?.suggestions?.facts.orEmpty()
                    .filter { it.field in setOf(BottleFactField.QUANTITY, BottleFactField.ABV_PERCENT) }
                for (fact in scalars) {
                    val gold = case.getValue("sourceFacts").jsonObject.getValue(fact.field.wireValue).jsonObject
                    assertEquals("SUPPORTED", gold.getValue("status").jsonPrimitive.content)
                    fun number(s: String) = Regex("[0-9]+(?:[.,][0-9]+)?").find(s)!!.value.replace(',', '.').toDouble()
                    assertEquals(number(gold.getValue("value").jsonPrimitive.content), number(fact.value), 0.00001)
                }
                val split = case.getValue("split").jsonPrimitive.content
                counts[split] = counts.getOrDefault(split, 0) + scalars.size
            }
        }
        assertEquals(mapOf("development" to 6, "holdout" to 2), counts)
    }
}
