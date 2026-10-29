package com.davidhayesbc.cocktails.android.product

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Combines ordinary diagnostics with only the detailed captures explicitly enabled by the user. */
internal fun combineBottleDiagnostics(standardReport: String, detailedReports: List<String>): String {
    val standard = runCatching { Json.parseToJsonElement(standardReport).jsonObject }
        .getOrElse { JsonObject(mapOf("reportUnavailable" to JsonPrimitive(true))) }
    val details = detailedReports.mapNotNull { raw ->
        runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
    }
    return Json { prettyPrint = true }.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("standardDiagnostics", standard)
            put("detailedCaptureCount", details.size)
            put("detailedCapturesIncluded", details.isNotEmpty())
            put("detailedCaptures", JsonArray(details))
        },
    )
}
