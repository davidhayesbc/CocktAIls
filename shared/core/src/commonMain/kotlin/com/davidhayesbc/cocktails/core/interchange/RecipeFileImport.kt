package com.davidhayesbc.cocktails.core.interchange

import com.davidhayesbc.cocktails.core.model.RecipeIngredientDraft
import com.davidhayesbc.cocktails.core.model.RecipeStyle
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

const val MAX_RECIPE_IMPORT_BYTES = 10 * 1024 * 1024

data class RecipeImportTable(
    val headers: List<String>,
    val rows: List<Map<String, JsonElement>>,
)

sealed interface RecipeTableParseResult {
    data class Valid(val table: RecipeImportTable) : RecipeTableParseResult
    data class Invalid(val reason: String) : RecipeTableParseResult
}

data class RecipeImportMapping(
    val titleField: String,
    val ingredientsField: String,
    val instructionsField: String? = null,
    val servingsField: String? = null,
    val styleField: String? = null,
    val familyField: String? = null,
    val sourceRecordIdField: String? = null,
)

data class RecipeImportProvenance(
    val sourceName: String,
    val sourceUrl: String? = null,
    val licenseName: String,
    val attribution: String,
)

data class StagedRecipeImport(
    val rowNumber: Int,
    val title: String,
    val ingredientLines: List<RecipeIngredientDraft>,
    val instructions: String,
    val servings: Double,
    val style: RecipeStyle,
    val familyName: String,
    val familyGroupKey: String,
    val sourceRecordId: String?,
)

data class RecipeImportPreview(
    val recipes: List<StagedRecipeImport>,
    val errors: List<String>,
) {
    val canApply: Boolean get() = recipes.isNotEmpty() && errors.isEmpty()
}

sealed interface RecipeImportApplyResult {
    data class Applied(val imported: Int, val skippedDuplicates: Int) : RecipeImportApplyResult
    data class Invalid(val reason: String) : RecipeImportApplyResult
}

/** Parses untrusted user-provided CSV or an array of JSON recipe objects without touching storage. */
fun parseRecipeImportTable(contents: String, fileName: String): RecipeTableParseResult {
    if (contents.encodeToByteArray().size > MAX_RECIPE_IMPORT_BYTES) {
        return RecipeTableParseResult.Invalid("Recipe import exceeds the 10 MiB limit.")
    }
    val normalizedName = fileName.lowercase()
    return when {
        normalizedName.endsWith(".csv") -> parseCsvTable(contents)
        normalizedName.endsWith(".json") -> parseJsonTable(contents)
        else -> RecipeTableParseResult.Invalid("Choose a .csv or .json recipe file.")
    }
}

fun previewRecipeImport(
    table: RecipeImportTable,
    mapping: RecipeImportMapping,
    provenance: RecipeImportProvenance,
): RecipeImportPreview {
    val errors = mutableListOf<String>()
    if (provenance.sourceName.isBlank()) errors += "Enter a source name."
    if (provenance.licenseName.isBlank()) errors += "Enter the source license or terms."
    if (provenance.attribution.isBlank()) errors += "Enter the required attribution or author information."
    val requiredFields = listOf(mapping.titleField to "title", mapping.ingredientsField to "ingredients")
    requiredFields.forEach { (field, label) ->
        if (field.isBlank() || field !in table.headers) errors += "Map a valid $label field."
    }
    listOf(
        mapping.instructionsField,
        mapping.servingsField,
        mapping.styleField,
        mapping.familyField,
        mapping.sourceRecordIdField,
    ).filterNotNull().filter { it !in table.headers }.forEach { errors += "Mapped field '$it' is not present in the file." }
    if (errors.isNotEmpty()) return RecipeImportPreview(emptyList(), errors.distinct())

    val staged = mutableListOf<StagedRecipeImport>()
    table.rows.forEachIndexed { index, row ->
        val rowNumber = index + 2
        val rowErrors = mutableListOf<String>()
        val title = row[mapping.titleField].asText().trim()
        if (title.isBlank()) rowErrors += "title is empty"
        val lines = parseIngredientLines(row[mapping.ingredientsField], rowErrors)
        val rawServings = mapping.servingsField?.let { row[it].asText().trim() }
        val servings = if (rawServings == null) 1.0 else rawServings.ifEmpty { "1" }.toDoubleOrNull() ?: Double.NaN
        if (!servings.isFinite() || servings <= 0.0) rowErrors += "servings must be a positive number"
        val style = if (mapping.styleField == null) RecipeStyle.OTHER else parseRecipeStyle(row[mapping.styleField].asText())
        if (style == null) rowErrors += "style '${row[mapping.styleField!!].asText()}' is not recognized"
        val familyName = mapping.familyField?.let { row[it].asText().trim() }.orEmpty().ifEmpty { title }
        if (mapping.familyField != null && familyName.isBlank()) rowErrors += "family is empty"
        val externalId = mapping.sourceRecordIdField?.let { row[it].asText().trim().ifEmpty { null } }
        if (rowErrors.isNotEmpty()) {
            errors += "Row $rowNumber: ${rowErrors.joinToString("; ")}."
        } else {
            val groupKey = if (mapping.familyField == null) "row:$rowNumber" else "family:${familyName.lowercase()}"
            staged += StagedRecipeImport(
                rowNumber = rowNumber,
                title = title,
                ingredientLines = lines,
                instructions = mapping.instructionsField?.let { row[it].asText().trim() }.orEmpty(),
                servings = servings,
                style = style ?: RecipeStyle.OTHER,
                familyName = familyName,
                familyGroupKey = groupKey,
                sourceRecordId = externalId,
            )
        }
    }
    mapping.sourceRecordIdField?.let { field ->
        staged.mapNotNull { it.sourceRecordId }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { id ->
            errors += "The file contains duplicate source record ID '$id'."
        }
    }
    if (table.rows.isEmpty()) errors += "The file contains no recipe rows."
    return RecipeImportPreview(staged, errors)
}

private fun parseCsvTable(contents: String): RecipeTableParseResult {
    val text = contents.removePrefix("\uFEFF")
    val rows = mutableListOf<List<String>>()
    val row = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var quoteClosed = false
    var index = 0

    fun finishCell() {
        row += cell.toString()
        cell.clear()
        quoteClosed = false
    }

    fun finishRow() {
        finishCell()
        if (row.any(String::isNotBlank)) rows += row.toList()
        row.clear()
    }

    while (index < text.length) {
        val character = text[index]
        if (quoted) {
            if (character == '"') {
                if (text.getOrNull(index + 1) == '"') {
                    cell.append('"')
                    index++
                } else {
                    quoted = false
                    quoteClosed = true
                }
            } else cell.append(character)
        } else {
            when {
                character == '"' && cell.isEmpty() && !quoteClosed -> quoted = true
                character == ',' -> finishCell()
                character == '\n' -> finishRow()
                character == '\r' -> {
                    finishRow()
                    if (text.getOrNull(index + 1) == '\n') index++
                }
                quoteClosed && character.isWhitespace() -> Unit
                quoteClosed -> return RecipeTableParseResult.Invalid("CSV has characters after a closing quote at character ${index + 1}.")
                character == '"' -> return RecipeTableParseResult.Invalid("CSV contains a quote inside an unquoted field at character ${index + 1}.")
                else -> cell.append(character)
            }
        }
        index++
    }
    if (quoted) return RecipeTableParseResult.Invalid("CSV ends inside a quoted field.")
    if (cell.isNotEmpty() || row.isNotEmpty() || quoteClosed) finishRow()
    if (rows.isEmpty()) return RecipeTableParseResult.Invalid("CSV contains no header row.")

    val headers = rows.first().map(String::trim)
    if (headers.any(String::isBlank)) return RecipeTableParseResult.Invalid("CSV headers must not be empty.")
    if (headers.map(String::lowercase).distinct().size != headers.size) return RecipeTableParseResult.Invalid("CSV contains duplicate headers.")
    val records = mutableListOf<Map<String, JsonElement>>()
    rows.drop(1).forEachIndexed { index, values ->
        if (values.size != headers.size) {
            return RecipeTableParseResult.Invalid("CSV row ${index + 2} has ${values.size} columns; expected ${headers.size}.")
        }
        records += headers.zip(values).associate { (header, value) -> header to JsonPrimitive(value) }
    }
    return RecipeTableParseResult.Valid(RecipeImportTable(headers, records))
}

private fun parseJsonTable(contents: String): RecipeTableParseResult {
    return try {
        val root = Json.parseToJsonElement(contents.removePrefix("\uFEFF"))
        val elements = when (root) {
            is JsonArray -> root
            is JsonObject -> root["recipes"] as? JsonArray
                ?: return RecipeTableParseResult.Invalid("JSON must be an array of recipe objects or an object with a 'recipes' array.")
            else -> return RecipeTableParseResult.Invalid("JSON must contain recipe objects.")
        }
        if (elements.any { it !is JsonObject }) return RecipeTableParseResult.Invalid("Every JSON recipe row must be an object.")
        val records = elements.map { it.jsonObject }
        val headers = records.flatMap(JsonObject::keys).distinct()
        RecipeTableParseResult.Valid(RecipeImportTable(headers, records))
    } catch (_: SerializationException) {
        RecipeTableParseResult.Invalid("JSON is malformed.")
    }
}

private fun parseIngredientLines(value: JsonElement?, errors: MutableList<String>): List<RecipeIngredientDraft> {
    val result = mutableListOf<RecipeIngredientDraft>()
    when (value) {
        is JsonArray -> value.forEach { element ->
            when (element) {
                is JsonPrimitive -> element.contentOrNull.orEmpty().split(';', '\n')
                    .mapNotNull { parseTextIngredient(it, errors) }.forEach(result::add)
                is JsonObject -> {
                    val name = (element["name"] ?: element["ingredient"]).asText().trim()
                    val amountValue = (element["amount"] ?: element["quantity"]).asText().trim()
                    val amount = amountValue.takeIf(String::isNotEmpty)?.toDoubleOrNull()
                    val optional = (element["optional"] as? JsonPrimitive)?.booleanOrNull ?: false
                    if (name.isBlank()) errors += "ingredient object has no name"
                    if (amountValue.isNotEmpty() && (amount == null || amount <= 0.0 || !amount.isFinite())) errors += "ingredient '$name' has an invalid amount"
                    if (name.isNotBlank() && (amountValue.isEmpty() || amount != null)) result += RecipeIngredientDraft(
                        name = name,
                        amount = amount,
                        unit = element["unit"].asText().trim().ifEmpty { null },
                        preparation = element["preparation"].asText().trim().ifEmpty { null },
                        optional = optional,
                    )
                }
                else -> errors += "ingredient array contains an unsupported value"
            }
        }
        is JsonPrimitive -> value.contentOrNull.orEmpty().split(';', '\n')
            .mapNotNull { parseTextIngredient(it, errors) }.forEach(result::add)
        else -> Unit
    }
    if (result.isEmpty()) errors += "ingredients are empty"
    return result
}

private fun parseTextIngredient(text: String, errors: MutableList<String>): RecipeIngredientDraft? {
    val parts = text.trim().split('|').map(String::trim)
    val name = parts.firstOrNull().orEmpty()
    if (name.isBlank()) return null
    val amountText = parts.getOrNull(1).orEmpty()
    val amount = amountText.takeIf(String::isNotEmpty)?.toDoubleOrNull()
    if (amountText.isNotEmpty() && (amount == null || amount <= 0.0 || !amount.isFinite())) {
        errors += "ingredient '$name' has an invalid amount"
        return null
    }
    return RecipeIngredientDraft(
        name = name,
        amount = amount,
        unit = parts.getOrNull(2)?.ifEmpty { null },
        optional = parts.getOrNull(3)?.lowercase() in setOf("true", "yes", "optional", "1"),
        preparation = parts.getOrNull(4)?.ifEmpty { null },
    )
}

private fun parseRecipeStyle(raw: String): RecipeStyle? {
    val cleaned = raw.trim()
    if (cleaned.isEmpty()) return RecipeStyle.OTHER
    return RecipeStyle.entries.firstOrNull { it.name.equals(cleaned, ignoreCase = true) || it.label.equals(cleaned, ignoreCase = true) }
}

private fun JsonElement?.asText(): String = when (this) {
    null, JsonNull -> ""
    is JsonPrimitive -> contentOrNull.orEmpty()
    else -> toString()
}
