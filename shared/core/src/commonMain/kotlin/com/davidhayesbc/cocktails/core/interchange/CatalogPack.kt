package com.davidhayesbc.cocktails.core.interchange

import com.davidhayesbc.cocktails.core.model.RecipeStyle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

data class CatalogPack(
    val id: String,
    val version: Int,
    val sourceName: String,
    val licenseName: String,
    val licenseUrl: String,
    val attribution: String,
    val repositoryUrl: String?,
    val repositoryRevision: String,
    val records: List<CatalogRecipe>,
    val licenseNotice: String? = null,
    val uploadedSource: Boolean = false,
)

data class CatalogRecipe(
    val recordId: String,
    val title: String,
    val style: RecipeStyle,
    val instructions: String,
    val servings: Double,
    val permanentUrl: String?,
    val historyUrl: String?,
    val modifications: String,
    val originalIngredients: String,
    val ingredients: List<CatalogIngredient>,
    val familyAliases: List<String> = emptyList(),
    val sourceMetadata: JsonObject? = null,
)

data class CatalogIngredient(
    val name: String,
    val category: String,
    val amount: Double?,
    val unit: String?,
    val preparation: String? = null,
    val optional: Boolean = false,
)

sealed interface CatalogPackDecodeResult {
    data class Valid(val pack: CatalogPack) : CatalogPackDecodeResult
    data class Invalid(val reason: String) : CatalogPackDecodeResult
}

/** Strict, bounded decoder for bundled/offline recipe packs. */
fun decodeCatalogPack(json: String): CatalogPackDecodeResult = try {
    require(json.encodeToByteArray().size <= 1_048_576) { "Catalog pack exceeds the 1 MiB limit." }
    val root = Json.parseToJsonElement(json).jsonObject
    val format = root.getValue("format").jsonPrimitive.content
    require(format in setOf("cocktails.catalog-pack.v1", "cocktails.catalog-pack.v2")) { "Unsupported catalog pack format." }
    val uploaded = format == "cocktails.catalog-pack.v2" && root["sourceKind"]?.jsonPrimitive?.content == "UPLOADED_DATA"
    val licenseNotice = root["licenseNotice"]?.jsonPrimitive?.contentOrNull
    if (uploaded) require(!licenseNotice.isNullOrBlank() && licenseNotice.length <= 20000) { "Uploaded data needs its complete license notice." }
    val id = root.getValue("id").jsonPrimitive.content.trim()
    val version = root.getValue("version").jsonPrimitive.content.toInt()
    val sourceName = root.getValue("sourceName").jsonPrimitive.content.trim()
    val license = root.getValue("license").jsonPrimitive.content.trim()
    val attribution = root.getValue("attribution").jsonPrimitive.content.trim()
    val licenseUrl = root.getValue("licenseUrl").jsonPrimitive.content
    val repositoryUrl = root["repositoryUrl"]?.jsonPrimitive?.contentOrNull
    val repositoryRevision = root.getValue("repositoryRevision").jsonPrimitive.content.trim()
    require(id.isNotBlank() && id.length <= 120 && version > 0) { "Catalog identity or version is invalid." }
    require(sourceName.isNotBlank() && license.isNotBlank() && attribution.isNotBlank() && repositoryRevision.isNotBlank()) { "Catalog attribution and license are required." }
    require(licenseUrl.startsWith("https://") && (repositoryUrl?.startsWith("https://") ?: uploaded)) { "Catalog license and repository links must use HTTPS." }
    val rows = root.getValue("recipes").jsonArray
    require(rows.isNotEmpty() && rows.size <= 100) { "Catalog must contain between 1 and 100 recipes." }
    val recipes = rows.map { item ->
        val row = item.jsonObject
        val recordId = row.getValue("recordId").jsonPrimitive.content.trim()
        val title = row.getValue("title").jsonPrimitive.content.trim()
        val style = RecipeStyle.valueOf(row.getValue("style").jsonPrimitive.content)
        val instructions = row.getValue("instructions").jsonPrimitive.content.trim()
        val servings = row.getValue("servings").jsonPrimitive.doubleOrNull
        val permanentUrl = row["permanentUrl"]?.jsonPrimitive?.contentOrNull
        val historyUrl = row["historyUrl"]?.jsonPrimitive?.contentOrNull
        val modifications = row.getValue("modifications").jsonPrimitive.content
        val originalIngredients = row.getValue("originalIngredients").jsonPrimitive.content.trim()
        val lines = row.getValue("ingredients").jsonArray.map { lineElement ->
            val line = lineElement.jsonObject
            val amountPrimitive = line["amount"]?.jsonPrimitive
            val amount = amountPrimitive?.doubleOrNull
            val unit = line["unit"]?.jsonPrimitive?.contentOrNull
            CatalogIngredient(
                line.getValue("name").jsonPrimitive.content.trim(),
                line.getValue("category").jsonPrimitive.content.trim(),
                amount,
                unit?.trim()?.takeIf(String::isNotEmpty),
                line["preparation"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty),
                line["optional"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
        require(recordId.isNotBlank() && recordId.length <= 200 && title.isNotBlank()) { "Recipe identity or title is invalid." }
        require(instructions.isNotBlank() && instructions.length <= 4000 && modifications.isNotBlank() && originalIngredients.isNotBlank()) { "Recipe method or modification record is invalid." }
        require(servings != null && servings.isFinite() && servings > 0) { "Recipe yield is invalid." }
        require((permanentUrl?.startsWith("https://") ?: uploaded) && (historyUrl?.startsWith("https://") ?: uploaded)) { "Recipe source links must use HTTPS." }
        require(lines.isNotEmpty() && lines.size <= 40 && lines.all { line ->
            line.name.isNotBlank() && line.name.length <= 160 && line.category.isNotBlank() && line.category.length <= 80 &&
                (line.amount == null || line.amount.isFinite() && line.amount > 0) &&
                (line.amount == null || !line.unit.isNullOrBlank())
        }) { "Recipe ingredients are invalid." }
        val aliases = row["familyAliases"]?.jsonArray?.map { it.jsonPrimitive.content.trim() }.orEmpty()
        require(aliases.size <= 10 && aliases.all { it.isNotBlank() && it.length <= 160 }) { "Invalid family aliases." }
        val metadata = row["sourceMetadata"]?.jsonObject
        if (uploaded) {
            require(metadata != null && metadata["rawQuantities"]?.jsonPrimitive?.booleanOrNull == true) { "Uploaded quantities must remain explicitly unscaled." }
            require(lines.all { it.amount == null }) { "Raw source quantities must not become guessed numeric measures." }
            require(metadata["originalRecord"]?.jsonObject?.get("id")?.jsonPrimitive?.content == recordId) { "Original source record ID is required." }
        }
        CatalogRecipe(recordId, title, style, instructions, servings, permanentUrl, historyUrl, modifications, originalIngredients, lines, aliases, metadata)
    }
    require(recipes.map { it.recordId }.distinct().size == recipes.size) { "Catalog contains duplicate source record IDs." }
    CatalogPackDecodeResult.Valid(CatalogPack(id, version, sourceName, license, licenseUrl, attribution, repositoryUrl, repositoryRevision, recipes, licenseNotice, uploaded))
} catch (error: Exception) {
    CatalogPackDecodeResult.Invalid(error.message ?: "Catalog pack is invalid.")
}
