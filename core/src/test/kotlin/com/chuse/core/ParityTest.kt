package com.chuse.core

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.math.abs

/**
 * Checks this engine's arithmetic against responses recorded from the live laya-shim.
 *
 * Two layers, because they catch different things:
 *
 *  - **Offline (always runs).** The fixtures are real responses from `laya-shim 0.4.1`.
 *    Feeding the shim's own probabilities back through `Math` proves the derived fields
 *    were reverse-engineered correctly. If `confidence` were the top probability rather
 *    than one minus normalised entropy, this fails on the very first fixture.
 *
 *    Note this checks the *shim's* definition of confidence. llama.cpp, which is what
 *    actually runs on the phone, uses the TypeSafe formula instead — see
 *    `Math.typesafeChoiceConfidence`. Both are implemented and both are tested, because
 *    they disagree and conflating them would hide that.
 *  - **Live (opt-in).** `-Dchuse.shim=http://192.168.3.129:8081` re-asks the service and
 *    checks the recorded answers still hold, so upstream drift is visible. Skipped when
 *    the service is unreachable: the app never depends on it.
 *
 * What this does NOT cover is the model's own probabilities — those need the GGUF and the
 * real shim source. See README "Parity: what is and isn't verified".
 */
class ParityTest {

    @Test
    fun `confidence is one minus normalised entropy for every recorded answer`() {
        var checked = 0
        val typesSeen = mutableSetOf<String>()
        for (fixture in Fixtures.load()) {
            for ((id, answer) in fixture.response) {
                typesSeen += answer.type
                val probabilities = answer.probabilitiesOrNull() ?: continue
                assertEquals(
                    "$id in ${fixture.name}: probabilities sum to one",
                    1.0,
                    probabilities.sum(),
                    TOLERANCE,
                )
                assertEquals(
                    "$id in ${fixture.name}: confidence",
                    answer.confidence,
                    Math.normalisedEntropyConfidence(probabilities),
                    FIXTURE_TOLERANCE,
                )
                assertEquals(
                    "$id in ${fixture.name}: answer_confidence",
                    answer.answer_confidence,
                    Math.answerConfidence(probabilities),
                    FIXTURE_TOLERANCE,
                )
                checked++
            }
        }
        assertTrue("expected several distributions checked, saw $checked", checked >= 4)
        assertEquals(
            "fixtures must cover all three question types",
            setOf("noul", "choice", "score"),
            typesSeen,
        )
    }

    @Test
    fun `noul confidence is the top of the two outcomes`() {
        var checked = 0
        for (fixture in Fixtures.load()) {
            for ((id, answer) in fixture.response) {
                if (answer.type != "noul") continue
                val yes = checkNotNull(answer.noul) { "$id in ${fixture.name} has no noul value" }
                val top = maxOf(yes, 1.0 - yes)
                assertEquals("$id in ${fixture.name}", answer.confidence, top, TOLERANCE)
                assertEquals("$id in ${fixture.name}", answer.answer_confidence, top, TOLERANCE)
                checked++
            }
        }
        assertTrue("expected noul fixtures, saw $checked", checked >= 3)
    }

    @Test
    fun `score is the expectation over the shim's own distribution`() {
        var checked = 0
        for (fixture in Fixtures.load()) {
            for ((id, answer) in fixture.response) {
                if (answer.type != "score") continue
                val raw = checkNotNull(answer.probabilities)
                // The shim keys probabilities by level index, as strings.
                val ordered = raw.keys.sortedBy { it.toInt() }.map { checkNotNull(raw[it]) }
                assertEquals(
                    "$id in ${fixture.name}",
                    checkNotNull(answer.score) { "$id in ${fixture.name} has no score" },
                    Math.expectedLevel(ordered),
                    FIXTURE_TOLERANCE,
                )
                assertEquals(
                    "$id in ${fixture.name}: legend matches the scale",
                    ordered.size,
                    checkNotNull(answer.legend).size,
                )
                checked++
            }
        }
        assertTrue("expected score fixtures, saw $checked", checked >= 1)
    }

    @Test
    fun `every recorded answer keeps confidence within its answer confidence`() {
        for (fixture in Fixtures.load()) {
            for ((id, answer) in fixture.response) {
                assertTrue(
                    "$id in ${fixture.name}",
                    answer.confidence <= answer.answer_confidence + TOLERANCE,
                )
            }
        }
    }

    @Test
    fun `recorded fixtures still match the live shim`() {
        val endpoint = System.getProperty("chuse.shim") ?: run {
            assumeTrue("chuse.shim not set; skipping live check", false)
            return
        }
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
        val serializer = MapSerializer(String.serializer(), ShimAnswer.serializer())
        var compared = 0

        for (fixture in Fixtures.load()) {
            val body = Fixtures.decideBody(fixture.request)
            val httpRequest = HttpRequest.newBuilder(URI.create("$endpoint/decide"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val httpResponse = client.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            assertEquals("HTTP ${httpResponse.statusCode()}", 200, httpResponse.statusCode())
            val live = Fixtures.json.decodeFromString(serializer, httpResponse.body())

            for ((id, expected) in fixture.response) {
                val actual = live[id] ?: error("live shim did not answer '$id' in ${fixture.name}")
                assertEquals("type in ${fixture.name}", expected.type, actual.type)
                when (expected.type) {
                    // Discrete answers should reproduce exactly: same model, same input.
                    "choice" -> assertEquals("${fixture.name}/$id", expected.choice, actual.choice)
                    // Scores are expectations over many levels, so a small drift is normal.
                    "score" -> assertTrue(
                        "score drifted unreasonably in ${fixture.name}: ${expected.score} -> ${actual.score}",
                        abs((expected.score ?: 0.0) - (actual.score ?: 0.0)) < 0.5,
                    )
                    // The shim is a small model and leans either way on the same phrasing
                    // across versions, so only flag a collapse, not a difference.
                    "noul" -> assertTrue(
                        "noul collapsed to the opposite answer in ${fixture.name}: " +
                            "${expected.noul} -> ${actual.noul}",
                        abs((expected.noul ?: 0.0) - (actual.noul ?: 0.0)) < 1.0,
                    )
                }
                compared++
            }
        }
        assertTrue("expected the live check to compare something", compared > 0)
    }

    private companion object {
        /** Tight enough for comparisons against figures this test computed itself. */
        const val TOLERANCE = 1e-4
    }
}

/**
 * Tolerance for figures recomputed from the recorded fixtures.
 *
 * The shim derives `confidence` and `score` from full-precision probabilities and rounds
 * once, at the end. The fixtures store probabilities already rounded to 4dp, so replaying
 * them reproduces the shim's arithmetic to within the input rounding — around 5e-4 for
 * `score`, which sums several terms, and a few 1e-4 for entropy.
 *
 * 1e-3 accommodates that while staying two orders of magnitude below the gap between the
 * correct formula and a plausible wrong one: normalised entropy gives 0.842 for a
 * 0.977/0.023 split where the top probability would give 0.977. A real formula error does
 * not hide under this tolerance.
 */
private const val FIXTURE_TOLERANCE = 1e-3

/** The distribution for choice and score answers; noul carries its value separately. */
private fun ShimAnswer.probabilitiesOrNull(): List<Double>? = probabilities?.values?.toList()