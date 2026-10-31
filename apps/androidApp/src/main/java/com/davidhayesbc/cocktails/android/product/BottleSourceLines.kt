package com.davidhayesbc.cocktails.android.product

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Local, per-request references to the exact bounded source shown to the model. */
internal class BottleSourceLines(source: String) {
    private val lines = source.lines()
    val promptText: String = lines.mapIndexed { index, line -> "L${index + 1}: $line" }.joinToString("\n")

    /** Only references resolve here; the existing parser still validates every value and quote. */
    fun resolveEvidence(raw: String): String {
        val root = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return raw
        val facts = root["facts"] as? JsonArray ?: return raw
        val resolved = facts.map { element ->
            val fact = element as? JsonObject ?: return@map element
            val reference = (fact["evidence"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val index = reference?.takeIf { Regex("L[1-9][0-9]{0,3}").matches(it) }
                ?.drop(1)?.toIntOrNull()?.minus(1)
            val quote = index?.let(lines::getOrNull)?.takeIf(String::isNotBlank)
            JsonObject(fact + ("evidence" to (quote?.let(::JsonPrimitive) ?: JsonNull)))
        }
        return JsonObject(root + ("facts" to JsonArray(resolved))).toString()
    }
}
