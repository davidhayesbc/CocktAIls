package com.davidhayesbc.cocktails.android.product

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Local, per-request references to the exact bounded source shown to the model. */
internal class BottleSourceLines(source: String) {
    // Bound the rendered representation, not just the input: prefixes and UTF-8 count too.
    // Retain only complete lines; the resolver never sees omitted tail lines.
    private val lines = buildList {
        var renderedBytes = 0
        for (line in source.lineSequence()) {
            if (size >= 64) break
            val rendered = "L${size + 1}: $line"
            val bytes = rendered.toByteArray(Charsets.UTF_8).size + if (isEmpty()) 0 else 1
            if (renderedBytes + bytes > 2400) break
            add(line)
            renderedBytes += bytes
        }
    }
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
            val sourceLine = index?.let(lines::getOrNull)?.takeIf(String::isNotBlank)
            val field = (fact["field"] as? JsonPrimitive)?.content
            val value = (fact["value"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            val quote = sourceLine?.takeIf {
                field != "brand" || (value != null && hasExplicitBrandLabel(it, value))
            }
            JsonObject(fact + ("evidence" to (quote?.let(::JsonPrimitive) ?: JsonNull)))
        }
        return JsonObject(root + ("facts" to JsonArray(resolved))).toString()
    }
}
