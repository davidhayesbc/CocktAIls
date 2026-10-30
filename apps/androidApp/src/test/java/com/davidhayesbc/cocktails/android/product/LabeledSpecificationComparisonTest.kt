package com.davidhayesbc.cocktails.android.product

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Same frozen inputs and deliberate model abstention on both revisions; NOT model inference. */
class LabeledSpecificationComparisonTest {
    @Test fun pairedDeterministicCoverageWithAbstainingModel() = runBlocking {
        val dataset = Json.parseToJsonElement(javaClass.getResource("/bottle-evaluation/v1/cases.json")!!.readText()).jsonObject
        val rows = buildJsonArray {
            for (element in dataset.getValue("cases").jsonArray) {
                val case = element.jsonObject
                if (case["kind"]?.jsonPrimitive?.content != "reviewed_product") continue
                for (p in case.getValue("pages").jsonArray) {
                    val source = p.jsonObject
                    val page = WebPageText(source["title"]?.jsonPrimitive?.contentOrNull,
                        source.getValue("sourceUrl").jsonPrimitive.content, source.getValue("markdown").jsonPrimitive.content,
                        source.getValue("provider").jsonPrimitive.content)
                    val result = LocalBottleDetailExtractor({ "/model.gguf" }, LocalInferenceRunner { _, _, _, _ -> """{"facts":[]}""" })
                        .extract(page, LOCAL_MODEL_LABEL)
                    val facts = (result as? BottleDetailExtractionResult.Found)?.suggestions?.facts.orEmpty()
                    assertTrue(facts.all { it.field == BottleFactField.QUANTITY || it.field == BottleFactField.ABV_PERCENT })
                    var supported = 0
                    var correct = 0
                    var falsePositive = 0
                    for (field in listOf(BottleFactField.QUANTITY, BottleFactField.ABV_PERCENT)) {
                        val label = case.getValue("sourceFacts").jsonObject.getValue(field.wireValue).jsonObject
                        if (label.getValue("status").jsonPrimitive.content != "SUPPORTED") continue
                        supported++
                        val prediction = facts.singleOrNull { it.field == field } ?: continue
                        fun value(s: String): Double {
                            val n = Regex("[0-9]+(?:[.,][0-9]+)?").find(s)!!.value.replace(',', '.').toDouble()
                            return if (field == BottleFactField.QUANTITY && Regex("(?i)\\bL\\b").containsMatchIn(s)) n * 1000 else n
                        }
                        if (value(prediction.value) == value(label.getValue("value").jsonPrimitive.content)) correct++ else falsePositive++
                    }
                    add(buildJsonObject {
                        put("id", case.getValue("id")); put("split", case.getValue("split"))
                        put("supportedScalarFields", supported); put("correctScalarFields", correct)
                        put("falsePositives", falsePositive)
                    })
                }
            }
        }
        File("build/reports/bottle-evaluation/deterministic-comparison.json").apply {
            parentFile!!.mkdirs()
            writeText(buildJsonObject {
                put("method", "frozen_sources_with_identical_empty_model_response_not_model_quality")
                put("holdoutStatus", "previously_consumed_reused_regression_not_fresh_holdout")
                put("cases", rows)
            }.toString())
        }
        Unit
    }
}
