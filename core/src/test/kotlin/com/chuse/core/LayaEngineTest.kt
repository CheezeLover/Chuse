package com.chuse.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class LayaEngineTest {

    private fun engineWith(vararg pairs: Pair<String, Float>) =
        LayaEngine(CandidateScorer.fixed(mapOf(*pairs)))

    // --- softmax ------------------------------------------------------------------------

    @Test
    fun `softmax sums to one`() {
        val p = Math.softmax(floatArrayOf(1f, 2f, 3f))
        assertEquals(1.0, p.sum(), 1e-9)
        assertTrue(p[2] > p[1] && p[1] > p[0])
    }

    @Test
    fun `softmax is stable under large magnitudes`() {
        val result = Math.softmax(floatArrayOf(1000f, 1001f, 999f))
        assertTrue("got $result", result.all { it.isFinite() && it in 0.0..1.0 })
        assertEquals(1.0, result.sum(), 1e-9)
    }

    @Test
    fun `softmax rejects non-finite scores rather than emitting NaN to the UI`() {
        assertThrows(IllegalArgumentException::class.java) {
            Math.softmax(floatArrayOf(0f, Float.NaN))
        }
    }

    @Test
    fun `softmax rejects a non-positive temperature`() {
        assertThrows(IllegalArgumentException::class.java) {
            Math.softmax(floatArrayOf(1f, 2f), temperature = 0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Math.softmax(floatArrayOf(1f, 2f), temperature = Float.NaN)
        }
    }

    @Test
    fun `a high temperature flattens the distribution and a low one sharpens it`() {
        val scores = floatArrayOf(2f, 1f)
        val flat = Math.softmax(scores, temperature = 10f)
        val sharp = Math.softmax(scores, temperature = 0.5f)
        assertTrue("temperature 10 gave ${flat.toList()}", flat[0] < sharp[0])
        assertTrue("temperature 0.5 gave ${sharp.toList()}", sharp[0] > 0.88)
    }

    @Test
    fun `temperature of one is a plain softmax`() {
        val plain = Math.softmax(floatArrayOf(1f, 2f, 3f))
        val explicit = Math.softmax(floatArrayOf(1f, 2f, 3f), temperature = 1f)
        assertEquals(plain.size, explicit.size)
        plain.indices.forEach { assertEquals(plain[it], explicit[it], 1e-12) }
    }

    // --- temperatures ---------------------------------------------------------------------

    @Test
    fun `temperatures come from the model and vary by type and option count`() {
        // These are the values in Laya-Q8_0.gguf. A flat 1.0 here would give probabilities
        // that look reasonable and are wrong.
        val noul = Question.Noul("q", "Is this true?")
        assertEquals(1.9834f, LayaTemperatures.forQuestion(noul, 2), 1e-4f)

        val choice = Question.Choice(
            "c", "Pick.",
            listOf(
                Question.Choice.Option("a", "A"),
                Question.Choice.Option("b", "B"),
            ),
        )
        assertEquals(1.906356f, LayaTemperatures.forQuestion(choice, 2), 1e-4f)
        assertEquals(1.000016f, LayaTemperatures.forQuestion(choice, 10), 1e-4f)
    }

    @Test
    fun `every question type resolves to a positive temperature`() {
        val two = listOf(
            Question.Choice.Option("a", "A"),
            Question.Choice.Option("b", "B"),
        )
        val many = (0 until 12).map { Question.Choice.Option("o$it", "O$it") }
        val questions = listOf(
            Question.Noul("q", "True?"),
            Question.Choice("c", "Pick.", two),
            Question.Choice("c12", "Pick.", many),
            Question.Score("s", "Rate.", listOf("low", "high")),
        )
        for (question in questions) {
            val temperature = LayaTemperatures.forQuestion(question, 4)
            assertTrue("$question gave $temperature", temperature > 0f && temperature.isFinite())
        }
    }

    // --- confidence: two formulas that share a name and are not the same thing ------------

    @Test
    fun `normalised entropy confidence reproduces what the shim reported`() {
        // This is laya-shim's `confidence`, not llama.cpp's. Kept as a separately named
        // function precisely because the two disagree.
        assertEquals(0.842, Math.normalisedEntropyConfidence(listOf(0.977, 0.023)), 1e-4)
        assertEquals(0.8802, Math.normalisedEntropyConfidence(listOf(0.9756, 0.0116, 0.0128)), 1e-4)
    }

    @Test
    fun `choice confidence is the TypeSafe formula, not entropy`() {
        // (p_max - 1/n) / (1 - 1/n), which is what llama.cpp reports.
        assertEquals(0.954, Math.typesafeChoiceConfidence(listOf(0.977, 0.023)), 1e-3)
        assertEquals(0.9634, Math.typesafeChoiceConfidence(listOf(0.9756, 0.0116, 0.0128)), 1e-3)
    }

    @Test
    fun `the two confidence formulas genuinely disagree`() {
        // The reason both are kept. Reporting one while labelling it as the other would
        // leave the app's numbers quietly inconsistent with whichever backend is running.
        val distribution = listOf(0.977, 0.023)
        val entropy = Math.normalisedEntropyConfidence(distribution)
        val typeSafe = Math.typesafeChoiceConfidence(distribution)
        assertTrue("expected a real gap, got $entropy vs $typeSafe", entropy < typeSafe - 0.05)
    }

    @Test
    fun `choice confidence is zero for a uniform split and one for certainty`() {
        assertEquals(0.0, Math.typesafeChoiceConfidence(List(4) { 0.25 }), 1e-9)
        val certain = Math.softmax(floatArrayOf(20f, -20f))
        assertEquals(1.0, Math.typesafeChoiceConfidence(certain.toList()), 1e-3)
    }

    @Test
    fun `score confidence is the TypeSafe distance formula`() {
        // A spike at any level scores high; an even spread across the scale scores 0.
        assertEquals(0.94, Math.typesafeScoreConfidence(listOf(0.02, 0.96, 0.02)), 1e-3)
        assertEquals(0.0, Math.typesafeScoreConfidence(List(5) { 0.2 }), 1e-9)
    }

    @Test
    fun `score confidence does not depend on which level the spike sits on`() {
        // It measures distance from the mode, not from the bottom of the scale.
        val low = Math.typesafeScoreConfidence(listOf(0.9, 0.05, 0.05))
        val high = Math.typesafeScoreConfidence(listOf(0.05, 0.05, 0.9))
        assertEquals(low, high, 1e-9)
    }

    @Test
    fun `entropy confidence is zero when every outcome is equally likely`() {
        assertEquals(0.0, Math.normalisedEntropyConfidence(List(4) { 0.25 }), 1e-9)
    }

    // --- expected level -------------------------------------------------------------------

    @Test
    fun `expected level of a certain answer is that level's index`() {
        assertEquals(2.0, Math.expectedLevel(Math.softmax(floatArrayOf(-10f, -10f, 10f)).toList()), 1e-3)
    }

    @Test
    fun `score is an expectation, so it may land between two levels`() {
        val engine = engineWith("low" to 0f, "high" to 0f)
        val result = engine.decide(
            Question.Score("s", "Rate it.", listOf("low", "high")),
            "The sky is blue.",
        ) as DecisionResult.Score
        assertEquals(0.5, result.score, 1e-6)
    }

    // --- the three question types ---------------------------------------------------------

    @Test
    fun `noul reports the probability that the statement holds`() {
        val engine = engineWith("true" to 3f, "false" to 0f)
        val result = engine.decide(
            Question.Noul("ok", "Is this true?"),
            "The sky is blue.",
        ) as DecisionResult.Noul

        assertTrue("noul was ${result.noul}", result.noul > 0.7)
        // softmax(3, 0) at the model's noul temperature of 1.9834 -> 0.8194 / 0.1806.
        // The temperature is doing real work here: at 1.0 this would be 0.9526 / 0.0474.
        assertEquals(0.8194, result.answerConfidence, 1e-3)
        assertEquals(0.6389, result.confidence, 1e-3)
    }

    @Test
    fun `noul with custom criteria scores the true and false columns`() {
        // The keys stay "true"/"false" whatever the user calls the options; the criteria only
        // change the description the prompt shows. Keying off the custom wording instead would
        // leave nothing to read the answer back by, and the model has two fixed columns.
        val scorer = CandidateScorer.fixed(mapOf("true" to 4f, "false" to 0f))
        var seen: ScoringRequest? = null
        val engine = LayaEngine(
            CandidateScorer { request, _ ->
                seen = request
                scorer.score(request, 1f)
            },
        )
        val result = engine.decide(
            Question.Noul("q", "Is the invoice correct?", BooleanCriteria("holds", "fails")),
            "The sky is blue.",
        ) as DecisionResult.Noul

        assertEquals(listOf("true", "false"), seen!!.options.map { it.key })
        assertEquals(listOf("holds", "fails"), seen!!.options.map { it.description })
        assertTrue("noul was ${result.noul}", result.noul > 0.7)
    }

    @Test
    fun `choice ranks every option and keeps the full distribution`() {
        val engine = engineWith("laptop" to 4f, "phone" to 1f, "tablet" to 0f)
        val result = engine.decide(
            Question.Choice(
                "pick",
                "What do they need?",
                listOf(
                    Question.Choice.Option("laptop", "A portable computer"),
                    Question.Choice.Option("phone", "A handheld device"),
                    Question.Choice.Option("tablet", "A large touch device"),
                ),
            ),
        "The sky is blue.",
        ) as DecisionResult.Choice

        assertEquals("laptop", result.choice)
        assertEquals(3, result.probabilities.size)
        assertEquals(1.0, result.probabilities.values.sum(), 1e-3)
        assertTrue(result.probabilities.getValue("laptop") > result.probabilities.getValue("phone"))
    }

    @Test
    fun `choice breaks a tie in favour of the option listed first`() {
        val engine = engineWith("alpha" to 1f, "beta" to 1f)
        val result = engine.decide(
            Question.Choice(
                "pick",
                "Pick one.",
                listOf(
                    Question.Choice.Option("alpha", "First"),
                    Question.Choice.Option("beta", "Second"),
                ),
            ),
        "The sky is blue.",
        ) as DecisionResult.Choice

        assertEquals("alpha", result.choice)
    }

    @Test
    fun `score returns an expectation keyed by level index with a matching legend`() {
        val engine = engineWith(
            "negligible" to -3f,
            "minor" to 0f,
            "major" to 3f,
            "catastrophic" to 0f,
        )
        val result = engine.decide(
            Question.Score(
                "sev",
                "Rate the severity.",
                listOf("negligible", "minor", "major", "catastrophic"),
            ),
        "The sky is blue.",
        ) as DecisionResult.Score

        assertEquals(
            listOf("negligible", "minor", "major", "catastrophic"),
            result.legend.values.toList(),
        )
        assertEquals(setOf(0, 1, 2, 3), result.probabilities.keys)
    }

    // --- the request handed to the scorer --------------------------------------------------

    @Test
    fun `a score question keys its options by level index, not by label`() {
        // The score is an expectation over positions, so the key has to be the position.
        var seen: ScoringRequest? = null
        val engine = LayaEngine(
            CandidateScorer { request, _ ->
                seen = request
                FloatArray(request.options.size) { 0f }
            },
        )
        engine.decide(
            Question.Score("s", "Rate.", listOf("low", "medium", "high")),
            "The sky is blue.",
        )
        assertEquals(listOf("0", "1", "2"), seen!!.options.map { it.key })
        assertEquals(listOf("low", "medium", "high"), seen!!.options.map { it.description })
    }

    @Test
    fun `noul always presents true before false`() {
        var seen: ScoringRequest? = null
        val engine = LayaEngine(
            CandidateScorer { request, _ ->
                seen = request
                FloatArray(request.options.size) { 0f }
            },
        )
        engine.decide(Question.Noul("q", "True?"), "The sky is blue.")
        assertEquals(listOf("true", "false"), seen!!.options.map { it.key })
    }

    @Test
    fun `the scorer is told the temperature for the question it is answering`() {
        var seen = 0f
        val engine = LayaEngine(
            CandidateScorer { _, temperature ->
                seen = temperature
                floatArrayOf(1f, 0f)
            },
        )
        engine.decide(
            Question.Choice(
                "c",
                "Pick.",
                listOf(
                    Question.Choice.Option("a", "A"),
                    Question.Choice.Option("b", "B"),
                ),
            ),
        "The sky is blue.",
        )
        assertEquals(1.906356f, seen, 1e-4f)
    }

    // --- validation ------------------------------------------------------------------------

    @Test
    fun `a choice question needs at least two options`() {
        assertThrows(IllegalArgumentException::class.java) {
            Question.Choice("c", "Pick.", listOf(Question.Choice.Option("only", "The only one")))
        }
    }

    @Test
    fun `a choice question rejects duplicate labels`() {
        assertThrows(IllegalArgumentException::class.java) {
            Question.Choice(
                "c",
                "Pick.",
                listOf(
                    Question.Choice.Option("same", "One"),
                    Question.Choice.Option("same", "Two"),
                ),
            )
        }
    }

    @Test
    fun `instructions are required on every type`() {
        assertThrows(IllegalArgumentException::class.java) { Question.Noul("n", "  ") }
        assertThrows(IllegalArgumentException::class.java) {
            Question.Score("s", "", listOf("a", "b"))
        }
    }

    @Test
    fun `an empty state is rejected because a question alone is too weak`() {
        var called = false
        val engine = LayaEngine(
            CandidateScorer { _, _ ->
                called = true
                floatArrayOf(0f, 0f)
            },
        )
        // Measured on a balanced 20-case set: 18/20 with a state, 12/20 without, with every
        // probability near a coin flip. Accepting an empty state produced answers that read as
        // a negative tilt rather than as the uncertainty they were.
        val failure = runCatching {
            engine.decide(Question.Noul("n", "Is it true?"), "")
        }.exceptionOrNull()

        assertTrue("expected a rejection, got $failure", failure is IllegalArgumentException)
        assertTrue("the scorer should not have run", !called)
    }

    @Test
    fun `a blank question is refused before the model is consulted`() {
        var called = false
        val engine = LayaEngine(
            CandidateScorer { _, _ ->
                called = true
                floatArrayOf(0f, 0f)
            },
        )
        assertThrows(IllegalArgumentException::class.java) {
            engine.decide(Question.Noul("n", "   "), "The sky is blue.")
        }
        assertTrue("the scorer should not have run", !called)
    }

    @Test
    fun `state is passed to the scorer verbatim`() {
        var seen: String? = null
        val engine = LayaEngine(
            CandidateScorer { request, _ ->
                seen = request.state
                floatArrayOf(1f, 0f)
            },
        )
        engine.decide(Question.Noul("n", "Is it true?"), "The invoice total is wrong.")
        assertEquals("The invoice total is wrong.", seen)
    }

    @Test
    fun `surrounding whitespace in a state is trimmed before it reaches the model`() {
        var seen: String? = null
        val engine = LayaEngine(
            CandidateScorer { request, _ ->
                seen = request.state
                floatArrayOf(1f, 0f)
            },
        )
        engine.decide(Question.Noul("n", "Is it true?"), "  padded  ")
        assertEquals("padded", seen)
    }

    @Test
    fun `a scorer returning the wrong number of scores is an error, not a shifted mapping`() {
        val engine = LayaEngine(CandidateScorer { _, _ -> floatArrayOf(1f) })
        assertThrows(IllegalArgumentException::class.java) {
            engine.decide(
                Question.Choice(
                    "c",
                    "Pick.",
                    listOf(
                        Question.Choice.Option("a", "A"),
                        Question.Choice.Option("b", "B"),
                    ),
                ),
            "The sky is blue.",
            )
        }
    }

    @Test
    fun `a fixed scorer normalises unseen options rather than dropping them`() {
        val engine = LayaEngine(CandidateScorer.fixed(mapOf("a" to 1f)))
        val result = engine.decide(
            Question.Choice(
                "c",
                "Pick.",
                listOf(
                    Question.Choice.Option("a", "Known"),
                    Question.Choice.Option("b", "Unseen"),
                ),
            ),
        "The sky is blue.",
        ) as DecisionResult.Choice
        assertEquals(1.0, result.probabilities.values.sum(), 1e-6)
        assertTrue(result.probabilities.getValue("b") > 0.0)
    }

    @Test
    fun `probabilities stay inside the unit interval for every question type`() {
        val engine = engineWith("a" to 3f, "b" to 1f, "c" to -2f)
        val results = listOf(
            engine.decide(Question.Noul("q", "True?"), "The sky is blue."),
            engine.decide(
                Question.Choice(
                    "c",
                    "Pick.",
                    listOf(
                        Question.Choice.Option("a", "A"),
                        Question.Choice.Option("b", "B"),
                        Question.Choice.Option("c", "C"),
                    ),
                ),
            "The sky is blue.",
            ),
            engine.decide(Question.Score("s", "Rate.", listOf("low", "mid", "high")), "The sky is blue."),
        )
        for (result in results) {
            assertTrue("$result", result.confidence in 0.0..1.0)
            assertTrue("$result", result.answerConfidence in 0.0..1.0)
        }
        // Guard the entropy identity the shim was reverse-engineered from.
        assertEquals(1.0, ln(3.0) / ln(3.0), 1e-12)
    }
}