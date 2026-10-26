package com.davidhayesbc.cocktails.android.product

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal enum class BottleDraftField(val storedValue: String) {
    INGREDIENT_NAME("ingredient_name"),
    BRAND("brand"),
    PRODUCT_NAME("product_name"),
}

/** Stores only bounded review evidence, never fetched page text, credentials or household export data. */
internal interface PendingBottleEvidenceStorage {
    fun read(draftId: String): String?
    fun write(draftId: String, value: String): Boolean
    fun remove(draftId: String): Boolean
}

internal class SharedPreferencesPendingBottleEvidenceStorage(context: Context) : PendingBottleEvidenceStorage {
    private val preferences = context.getSharedPreferences("cocktails_pending_bottle_evidence", Context.MODE_PRIVATE)

    override fun read(draftId: String): String? = preferences.getString(draftId, null)

    override fun write(draftId: String, value: String): Boolean = preferences.edit().putString(draftId, value).commit()

    override fun remove(draftId: String): Boolean = preferences.edit().remove(draftId).commit()
}

internal class PendingBottleEvidenceStore(private val storage: PendingBottleEvidenceStorage) {
    fun save(draftId: String, suggestions: BottleDetailSuggestions): Boolean {
        if (!draftId.matches(Regex("[A-Za-z0-9-]{1,80}"))) return false
        val sourceUrl = normalizePublicHttpsUrl(suggestions.sourceUrl) ?: return false
        val json = buildJsonObject {
            put("version", 1)
            put("provider", suggestions.provider.take(80))
            put("model", suggestions.model.take(128))
            put("source_url", sourceUrl)
            suggestions.targetBarcode?.let { put("target_barcode", it) }
            suggestions.identityEvidence?.let { put("identity_evidence", it.take(MAX_EVIDENCE_QUOTE_CHARS)) }
            put("facts", buildJsonArray {
                suggestions.facts.take(MAX_FACTS).forEach { fact ->
                    add(buildJsonObject {
                        put("field", fact.field.wireValue)
                        put("value", fact.value.take(MAX_FACT_VALUE_CHARS))
                        put("quote", fact.quote.take(MAX_EVIDENCE_QUOTE_CHARS))
                    })
                }
            })
            put("unresolved", buildJsonArray {
                suggestions.unresolvedFields.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.wireValue)) }
            })
            put("edited_fields", buildJsonArray {
                editedFields(draftId).forEach { add(JsonPrimitive(it.storedValue)) }
            })
        }.toString()
        return json.length <= MAX_PENDING_EVIDENCE_CHARS && storage.write(draftId, json)
    }

    fun markEdited(draftId: String, field: BottleDraftField): Boolean {
        if (!draftId.matches(Regex("[A-Za-z0-9-]{1,80}"))) return false
        val existing = storage.read(draftId)?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val edited = editedFields(draftId) + field
        val next = buildJsonObject {
            existing?.forEach { (key, value) -> if (key != "edited_fields") put(key, value) }
            if (existing == null) put("version", 1)
            put("edited_fields", buildJsonArray { edited.forEach { add(JsonPrimitive(it.storedValue)) } })
        }.toString()
        return next.length <= MAX_PENDING_EVIDENCE_CHARS && storage.write(draftId, next)
    }

    fun editedFields(draftId: String): Set<BottleDraftField> = runCatching {
        val raw = storage.read(draftId)?.takeIf { it.length <= MAX_PENDING_EVIDENCE_CHARS } ?: return emptySet()
        val root = Json.parseToJsonElement(raw).jsonObject
        root["edited_fields"]?.jsonArray.orEmpty().mapNotNull { item ->
            item.jsonPrimitive.contentOrNull?.let { stored -> BottleDraftField.entries.firstOrNull { it.storedValue == stored } }
        }.toSet()
    }.getOrDefault(emptySet())

    fun read(draftId: String): BottleDetailSuggestions? = runCatching {
        val raw = storage.read(draftId)?.takeIf { it.length <= MAX_PENDING_EVIDENCE_CHARS } ?: return null
        val root = Json.parseToJsonElement(raw).jsonObject
        if (root["version"]?.jsonPrimitive?.contentOrNull != "1") return null
        val provider = root["provider"]?.jsonPrimitive?.contentOrNull?.takeIf { it.length <= 80 } ?: return null
        val model = root["model"]?.jsonPrimitive?.contentOrNull?.takeIf { it.length <= 128 } ?: return null
        val sourceUrl = root["source_url"]?.jsonPrimitive?.contentOrNull?.let(::normalizePublicHttpsUrl) ?: return null
        val facts = root["facts"]?.jsonArray?.mapNotNull { item ->
            val fact = item.jsonObject
            val field = fact["field"]?.jsonPrimitive?.contentOrNull?.let { wire -> BottleFactField.entries.firstOrNull { it.wireValue == wire } } ?: return@mapNotNull null
            val value = fact["value"]?.jsonPrimitive?.contentOrNull?.takeIf { it.length in 1..MAX_FACT_VALUE_CHARS } ?: return@mapNotNull null
            val quote = fact["quote"]?.jsonPrimitive?.contentOrNull?.takeIf { it.length in 1..MAX_EVIDENCE_QUOTE_CHARS } ?: return@mapNotNull null
            BottleFactEvidence(field, value, quote)
        } ?: return null
        val unresolved = root["unresolved"]?.jsonArray.orEmpty().mapNotNull { item ->
            item.jsonPrimitive.contentOrNull?.let { wire -> BottleFactField.entries.firstOrNull { it.wireValue == wire } }
        }.toSet()
        if (facts.size > MAX_FACTS || facts.isEmpty() && unresolved.isEmpty()) return null
        BottleDetailSuggestions(
            provider = provider,
            model = model,
            sourceUrl = sourceUrl,
            facts = facts,
            unresolvedFields = unresolved,
            targetBarcode = root["target_barcode"]?.jsonPrimitive?.contentOrNull,
            identityEvidence = root["identity_evidence"]?.jsonPrimitive?.contentOrNull,
        )
    }.getOrNull()

    fun remove(draftId: String): Boolean = storage.remove(draftId)

    private companion object {
        const val MAX_PENDING_EVIDENCE_CHARS = 16_384
        const val MAX_FACTS = 4
        const val MAX_FACT_VALUE_CHARS = 120
        const val MAX_EVIDENCE_QUOTE_CHARS = 320
    }
}
