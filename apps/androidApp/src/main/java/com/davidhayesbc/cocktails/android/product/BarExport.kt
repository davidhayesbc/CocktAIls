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

internal enum class BarExportOperationPhase { IDLE, PREPARING, PICKER, WRITING }
internal enum class BarExportPickerAction { STALE, CANCELLED, SNAPSHOT_UNAVAILABLE, WRITE }
internal enum class BarExportRecovery { NONE, PREPARATION_INTERRUPTED, WRITE_INTERRUPTED, WAITING_FOR_PICKER_RESULT }

internal data class BarExportOperation(
    val requestId: String? = null,
    val phase: BarExportOperationPhase = BarExportOperationPhase.IDLE,
) {
    fun begin(id: String): BarExportOperation? =
        if (phase == BarExportOperationPhase.IDLE && id.isNotBlank()) BarExportOperation(id, BarExportOperationPhase.PREPARING) else null

    fun pickerLaunched(id: String): BarExportOperation? =
        if (requestId == id && phase == BarExportOperationPhase.PREPARING) copy(phase = BarExportOperationPhase.PICKER) else null

    fun pickerResult(id: String?, destinationChosen: Boolean, snapshotAvailable: Boolean): Pair<BarExportOperation, BarExportPickerAction> {
        if (id == null || requestId != id || phase != BarExportOperationPhase.PICKER) return this to BarExportPickerAction.STALE
        if (!destinationChosen) return IDLE to BarExportPickerAction.CANCELLED
        if (!snapshotAvailable) return IDLE to BarExportPickerAction.SNAPSHOT_UNAVAILABLE
        return copy(phase = BarExportOperationPhase.WRITING) to BarExportPickerAction.WRITE
    }

    fun writeFinished(id: String): BarExportOperation? =
        if (requestId == id && phase == BarExportOperationPhase.WRITING) IDLE else null

    fun abort(id: String): BarExportOperation? =
        if (requestId == id && phase != BarExportOperationPhase.IDLE) IDLE else null

    fun recoverAfterActivityRecreation(): Pair<BarExportOperation, BarExportRecovery> = when (phase) {
        BarExportOperationPhase.IDLE -> this to BarExportRecovery.NONE
        BarExportOperationPhase.PREPARING -> IDLE to BarExportRecovery.PREPARATION_INTERRUPTED
        BarExportOperationPhase.PICKER -> this to BarExportRecovery.WAITING_FOR_PICKER_RESULT
        BarExportOperationPhase.WRITING -> IDLE to BarExportRecovery.WRITE_INTERRUPTED
    }

    companion object {
        val IDLE = BarExportOperation()
    }
}

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

internal fun barExportRecoveryMessage(recovery: BarExportRecovery): String? = when (recovery) {
    BarExportRecovery.NONE, BarExportRecovery.WAITING_FOR_PICKER_RESULT -> null
    BarExportRecovery.PREPARATION_INTERRUPTED -> "Bar export preparation was interrupted. No file was opened; try Export Bar again."
    BarExportRecovery.WRITE_INTERRUPTED -> "Bar export was interrupted while writing. Check the chosen file before retrying."
}
