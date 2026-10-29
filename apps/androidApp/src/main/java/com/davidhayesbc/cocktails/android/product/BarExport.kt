package com.davidhayesbc.cocktails.android.product

import com.davidhayesbc.cocktails.core.model.PantryEntry
import com.davidhayesbc.cocktails.core.model.normalizeGtinForLookup
import java.io.IOException
import java.io.OutputStream
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val BAR_EXPORT_MIME_TYPE = "application/json"
internal const val BAR_EXPORT_FILENAME = "CocktAIls-Bar.json"

/** Stable, explicit export format for confirmed physical bottles in the selected home. */
internal fun buildBarExportJson(entries: List<PantryEntry>, exportedAtMillis: Long): String {
    val bottles = entries.sortedWith(
        compareBy<PantryEntry>({ it.name.lowercase() }, { it.productName.orEmpty().lowercase() }, { it.inventoryId }),
    )
    return buildJsonObject {
        put("format", "com.davidhayesbc.cocktails.bar-export")
        put("schemaVersion", 1)
        put("exportedAtEpochMillis", exportedAtMillis.coerceAtLeast(0))
        put("scope", "Confirmed bottles in the selected home; unfinished intake drafts are excluded.")
        put("bottleCount", bottles.size)
        put("bottles", buildJsonArray {
            bottles.forEach { entry ->
                add(buildJsonObject {
                    put("bottleId", entry.inventoryId)
                    put("name", entry.productName?.takeIf(String::isNotBlank) ?: entry.name)
                    put("productName", entry.productName?.let(::JsonPrimitive) ?: JsonNull)
                    put("brand", entry.brand?.let(::JsonPrimitive) ?: JsonNull)
                    put("category", entry.category)
                    put("barcode", entry.barcode?.let(::JsonPrimitive) ?: JsonNull)
                    put("normalizedBarcode", entry.barcode?.let(::normalizeGtinForLookup)?.let(::JsonPrimitive) ?: JsonNull)
                    put("abvPercent", entry.abvPercent?.let(::JsonPrimitive) ?: JsonNull)
                    put("bottleSize", entry.bottleSize?.let(::bottleSizeJson) ?: JsonNull)
                })
            }
        })
    }.toString()
}

private fun bottleSizeJson(display: String) = buildJsonObject {
    val match = Regex("(?i)^\\s*(\\d{1,4}(?:[.,]\\d{1,2})?)\\s*(mL|L)\\s*$").matchEntire(display)
    put("display", display)
    put("value", match?.groupValues?.get(1)?.replace(',', '.')?.let(::JsonPrimitive) ?: JsonNull)
    put("unit", match?.groupValues?.get(2)?.let { if (it.equals("l", true)) "L" else "mL" }?.let(::JsonPrimitive) ?: JsonNull)
}

/** Writes only to the URI explicitly granted by Android's document picker. */
internal fun writeBarExport(output: OutputStream, json: String): Boolean = try {
    output.use { it.write(json.toByteArray(Charsets.UTF_8)) }
    true
} catch (_: IOException) {
    false
} catch (_: SecurityException) {
    false
}

internal fun barExportCompletionMessage(destinationChosen: Boolean, writeSucceeded: Boolean?): String = when {
    !destinationChosen -> "Bar export canceled. No file was written."
    writeSucceeded == true -> "Bar export saved. It includes confirmed bottles in the selected home; unfinished drafts are excluded."
    else -> "Bar export failed. Your bottles were not changed. Try a different destination."
}
