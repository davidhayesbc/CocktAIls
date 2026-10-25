package com.davidhayesbc.cocktails.core.interchange

import com.davidhayesbc.cocktails.core.model.RecipeStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RecipeFileImportTest {
    private val provenance = RecipeImportProvenance(
        sourceName = "Dave's recipe file",
        licenseName = "Personal notes",
        attribution = "Dave",
    )

    @Test
    fun csvSupportsQuotedCommasEscapedQuotesAndMultilineMethods() {
        val table = assertIs<RecipeTableParseResult.Valid>(parseRecipeImportTable(
            "title,ingredients,instructions,servings,style\r\n\"Gin, Tonic\",\"Gin | 45 | ml; Tonic water | 120 | ml\",\"Pour \"\"slowly\"\".\nAdd ice.\",1,Long",
            "drinks.csv",
        )).table
        val preview = previewRecipeImport(table, RecipeImportMapping("title", "ingredients", "instructions", "servings", "style"), provenance)

        assertTrue(preview.canApply)
        assertEquals("Gin, Tonic", preview.recipes.single().title)
        assertEquals("Pour \"slowly\".\nAdd ice.", preview.recipes.single().instructions)
        assertEquals(listOf(45.0, 120.0), preview.recipes.single().ingredientLines.map { it.amount })
        assertEquals(listOf("ml", "ml"), preview.recipes.single().ingredientLines.map { it.unit })
        assertEquals(RecipeStyle.LONG, preview.recipes.single().style)
    }

    @Test
    fun jsonSupportsNestedIngredientObjectsAndOptionalLines() {
        val table = assertIs<RecipeTableParseResult.Valid>(parseRecipeImportTable(
            """[{"name":"Daiquiri","family":"Daiquiri","yield":2,"style":"sour","ingredients":[{"name":"Rum","amount":60,"unit":"ml"},{"name":"Lime juice","amount":25,"unit":"ml","optional":true}]}]""",
            "recipes.json",
        )).table
        val preview = previewRecipeImport(
            table,
            RecipeImportMapping("name", "ingredients", servingsField = "yield", styleField = "style", familyField = "family"),
            provenance,
        )

        assertTrue(preview.canApply)
        assertEquals("family:daiquiri", preview.recipes.single().familyGroupKey)
        assertEquals(2.0, preview.recipes.single().servings)
        assertEquals(true, preview.recipes.single().ingredientLines[1].optional)
    }

    @Test
    fun malformedCsvAndUnknownMappedReferencesAreRejected() {
        assertIs<RecipeTableParseResult.Invalid>(parseRecipeImportTable("title,ingredients\n\"unfinished,rum", "bad.csv"))
        assertIs<RecipeTableParseResult.Invalid>(parseRecipeImportTable("title,title\nMartini,Gin", "bad.csv"))
        val table = assertIs<RecipeTableParseResult.Valid>(parseRecipeImportTable("title,ingredients\nMartini,Gin", "ok.csv")).table
        val preview = previewRecipeImport(table, RecipeImportMapping("name", "ingredients"), provenance)
        assertFalse(preview.canApply)
        assertTrue(preview.errors.any { it.contains("Map a valid title") })
    }

    @Test
    fun invalidAmountsStylesAndUnresolvedIngredientReferencesBlockTheWholePreview() {
        val table = assertIs<RecipeTableParseResult.Valid>(parseRecipeImportTable(
            """[{"title":"Broken","ingredients":[{"ingredientId":"ingredient-not-in-file","amount":1}],"style":"molecular"}]""",
            "bad.json",
        )).table
        val preview = previewRecipeImport(table, RecipeImportMapping("title", "ingredients", styleField = "style"), provenance)
        assertFalse(preview.canApply)
        assertTrue(preview.errors.any { it.contains("ingredient object has no name") })
        assertTrue(preview.errors.any { it.contains("style 'molecular' is not recognized") })
    }

    @Test
    fun malformedJsonAndInvalidServingsAreRejectedInsteadOfDefaulted() {
        assertIs<RecipeTableParseResult.Invalid>(parseRecipeImportTable("{not-json", "bad.json"))
        val table = assertIs<RecipeTableParseResult.Valid>(parseRecipeImportTable(
            """[{"title":"Martini","ingredients":["Gin"],"yield":"many"}]""",
            "bad-yield.json",
        )).table
        val preview = previewRecipeImport(table, RecipeImportMapping("title", "ingredients", servingsField = "yield"), provenance)
        assertFalse(preview.canApply)
        assertTrue(preview.errors.any { it.contains("servings must be a positive number") })
    }

    @Test
    fun duplicateSourceRecordIdsAndMissingProvenanceBlockApply() {
        val table = assertIs<RecipeTableParseResult.Valid>(parseRecipeImportTable(
            "title,ingredients,id\nMartini,Gin,book-1\nMartinez,Tequila,book-1",
            "duplicates.csv",
        )).table
        val mapping = RecipeImportMapping("title", "ingredients", sourceRecordIdField = "id")
        val duplicatePreview = previewRecipeImport(table, mapping, provenance)
        assertFalse(duplicatePreview.canApply)
        assertTrue(duplicatePreview.errors.any { it.contains("duplicate source record ID") })

        val missingAttribution = previewRecipeImport(table, mapping, provenance.copy(attribution = ""))
        assertTrue(missingAttribution.errors.any { it.contains("attribution") })
    }
}
