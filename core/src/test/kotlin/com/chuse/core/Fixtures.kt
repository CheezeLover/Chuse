package com.chuse.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Shapes for the recorded laya-shim responses in `fixtures.json`.
 *
 * The shim answers with one object per question, discriminated by `type`, and the fields
 * differ per variant (`noul` only on noul, `probabilities` only on the other two).
 * [ShimAnswer] keeps them nullable rather than modelling a sealed hierarchy, so a fixture
 * round-trips exactly as recorded and an unknown key upstream stays an unknown key here
 * instead of becoming a parse failure.
 */
@Serializable
data class Fixture(
    val name: String,
    val request: FixtureRequest,
    val response: Map<String, ShimAnswer>,
)

@Serializable
data class FixtureRequest(
    val state: String,
    val questions: JsonObject,
)

@Serializable
data class ShimAnswer(
    val type: String,
    val noul: Double? = null,
    val score: Double? = null,
    val choice: String? = null,
    val probabilities: Map<String, Double>? = null,
    val confidence: Double,
    val answer_confidence: Double,
    @SerialName("legend") val legend: Map<String, String>? = null,
)

internal object Fixtures {
    val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun load(): List<Fixture> {
        val stream = checkNotNull(Fixtures::class.java.getResourceAsStream("/fixtures.json")) {
            "fixtures.json missing from test resources"
        }
        val text = stream.bufferedReader().use { it.readText() }
        return json.decodeFromString(text)
    }

    /** Builds a `/decide` request body, so a live call and a recorded one travel alike. */
    fun decideBody(request: FixtureRequest): String =
        JsonObject(
            mapOf(
                "state" to JsonPrimitive(request.state),
                "questions" to request.questions,
            ),
        ).toString()
}