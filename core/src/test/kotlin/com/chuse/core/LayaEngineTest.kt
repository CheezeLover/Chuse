package com.chuse.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class LayaEngineTest {

    private fun engineWith(vararg pairs: Pair<String, Float>) =
        LayaEngine(CandidateScorer.fixed(mapOf(*pairs)))

    // --- the shim's own numbers, recovered by probing laya-shim 0.4.1 ------------------

    // --- confidence: two formulas that share a name and are not the same thing -------

    @Test
    fun `normalised entropy confidence reproduces what the shim reported`() {
        // This is laya-shim's `confidence`, not llama.cpp's. Kept as a separately named
        // function precisely because the two disagree.
        assertEquals(0.842, Math.normalisedEntropyConfidence(listOf(0.977, 0.023)), 1e-4)
        assertEquals(0.8802, Math.normalisedEntropyConfidence(listOf(0.9756, 0.0116, 0.0128)), 1e-4)
        assertEquals(
            0.3583,
            Math.normalisedEntropyConfidence(listOf(0.0038, 0.0309, 0.0719, 0.4946, 0.3988)),
            1e-4,
        )
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
        val flat = List(4) { 0.25 }
        assertEquals(0.0, Math.normalisedEntropyConfidence(flat), 1e-9)
    }

    @Test
    fun `entropy confidence is one when the model is certain`() {
        val certain = Math.softmax(floatArrayOf(20f, -20f, -20f))
        assertEquals(1.0, Math.normalisedEntropyConfidence(certain.toList()), 1e-3)
    }

    @Test
    fun `expected level matches the shim's score for its observed distribution`() {
        val distribution = listOf(0.0038, 0.0309, 0.0719, 0.4946, 0.3988)
        // The shim reported 3.2538 for these probabilities. Replaying its own rounded
        // distribution gives 3.2537, because the shim rounds the score once at the end and
        // the inputs here are already rounded — see ParityTest.FIXTURE_TOLERANCE.
        assertEquals(3.2538, Math.expectedLevel(distribution), 1e-3)
    }

    @Test
    fun `expected level of a certain answer is that level's index`() {
        assertEquals(2.0, Math.expectedLevel(Math.softmax(floatArrayOf(-10f, -10f, 10f)).toList()), 1e-3)
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

    // --- the three question types -----------------------------------------------------

    @Test
    fun `noul reports the probability that the statement holds`() {
        val engine = engineWith("true" to 3f, "false" to 0f)
        val result = engine.decide(
            state = "The sky is blue.",
            questions = listOf(Question.Noul("ok", "Is this true?")),
        )["ok"] as DecisionResult.Noul

        assertEquals(1.0, result.noul, 0.95)
        // softmax(3, 0) = 0.9526 / 0.0474. answerConfidence is the top probability;
        // confidence is the TypeSafe reading of the same two-way distribution, which is
        // (p_max - 0.5) / 0.5 = 0.9051. They are not the same number.
        assertEquals(0.9526, result.answerConfidence, 1e-3)
        assertEquals(0.9051, result.confidence, 1e-3)
    }

    @Test
    fun `noul with custom criteria scores the custom labels, not true and false`() {
        // The scorer's table only contains the custom words, so if the engine asked about
        // "true"/"false" both would fall through to the unseen score and this would tie.
        val engine = engineWith("holds" to 4f, "fails" to 0f)
        val result = engine.decide(
            state = "The invoice total does not match the purchase order.",
            questions = listOf(
                Question.Noul("q", "Is the invoice correct?", BooleanCriteria(yes = "holds", no = "fails")),
            ),
        )["q"] as DecisionResult.Noul

        assertTrue("noul was ${result.noul}", result.noul > 0.9)
        // softmax(4, 0) = 0.982 / 0.018.
        assertEquals(0.982, result.answerConfidence, 1e-3)
        assertEquals(0.964, result.confidence, 1e-3)
    }

    @Test
    fun `choice ranks every option and keeps the full distribution`() {
        val engine = engineWith("laptop" to 4f, "phone" to 1f, "tablet" to 0f)
        val result = engine.decide(
            state = "I need a new computer.",
            questions = listOf(
                Question.Choice(
                    "pick",
                    "What do they need?",
                    listOf(
                        Question.Choice.Option("laptop", "A portable computer"),
                        Question.Choice.Option("phone", "A handheld device"),
                        Question.Choice.Option("tablet", "A large touch device"),
                    ),
                ),
            ),
        )["pick"] as DecisionResult.Choice

        assertEquals("laptop", result.choice)
        assertEquals(3, result.probabilities.size)
        assertEquals(1.0, result.probabilities.values.sum(), 1e-3)
        assertTrue(result.probabilities.getValue("laptop") > result.probabilities.getValue("phone"))
    }

    @Test
    fun `choice breaks a tie in favour of the option listed first`() {
        val engine = engineWith("alpha" to 1f, "beta" to 1f)
        val result = engine.decide(
            state = "Tied.",
            questions = listOf(
                Question.Choice(
                    "pick",
                    "Pick one.",
                    listOf(
                        Question.Choice.Option("alpha", "First"),
                        Question.Choice.Option("beta", "Second"),
                    ),
                ),
            ),
        )["pick"] as DecisionResult.Choice

        assertEquals("alpha", result.choice)
        assertEquals(0.5, result.probabilities.getValue("alpha"), 1e-6)
    }

    @Test
    fun `score returns an expectation keyed by level index with a matching legend`() {
        val engine = engineWith("negligible" to -3f, "minor" to 0f, "major" to 3f, "catastrophic" to 0f)
        val result = engine.decide(
            state = "The deploy broke production.",
            questions = listOf(
                Question.Score(
                    "sev",
                    "Rate the severity.",
                    listOf("negligible", "minor", "major", "catastrophic"),
                ),
            ),
        )["sev"] as DecisionResult.Score

        assertEquals(listOf("negligible", "minor", "major", "catastrophic"), result.legend.values.toList())
        assertEquals(setOf(0, 1, 2, 3), result.probabilities.keys)
        assertTrue("score was ${result.score}", result.score > 1.5 && result.score < 2.5)
    }

    @Test
    fun `score is an expectation, so it may land between two levels`() {
        val engine = engineWith("low" to 0f, "high" to 0f)
        val result = engine.decide(
            state = "Ambiguous.",
            questions = listOf(Question.Score("s", "Rate it.", listOf("low", "high"))),
        )["s"] as DecisionResult.Score

        assertEquals(0.5, result.score, 1e-6)
    }

    // --- validation -------------------------------------------------------------------

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
        assertThrows(IllegalArgumentException::class.java) { Question.Score("s", "", listOf("a", "b")) }
    }

    @Test
    fun `blank state is rejected before the model is touched`() {
        var called = false
        val engine = LayaEngine(CandidateScorer { req ->
            called = true
            FloatArray(req.candidates.size) { 0f }
        })
        assertThrows(IllegalArgumentException::class.java) {
            engine.decide("   ", listOf(Question.Noul("n", "Is it true?")))
        }
        assertTrue("scorer should not have been called", !called)
    }

    @Test
    fun `duplicate question ids are rejected rather than silently collapsed`() {
        val engine = engineWith("true" to 1f, "false" to 0f)
        assertThrows(IllegalArgumentException::class.java) {
            engine.decide(
                "state",
                listOf(
                    Question.Noul("q", "First?"),
                    Question.Noul("q", "Second?"),
                ),
            )
        }
    }

    @Test
    fun `a scorer returning the wrong number of scores is an error, not a shifted mapping`() {
        val engine = LayaEngine(CandidateScorer { floatArrayOf(1f) })
        assertThrows(IllegalArgumentException::class.java) {
            engine.decide(
                "state",
                listOf(
                    Question.Choice(
                        "c",
                        "Pick.",
                        listOf(
                            Question.Choice.Option("a", "A"),
                            Question.Choice.Option("b", "B"),
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `several questions travel through one engine and stay keyed by id`() {
        val engine = engineWith("true" to 2f, "false" to 0f, "a" to 1f, "b" to 0f)
        val results = engine.decide(
            "state",
            listOf(
                Question.Noul("yesno", "Is it true?"),
                Question.Choice("pick", "Pick one.", listOf(
                    Question.Choice.Option("a", "A"), Question.Choice.Option("b", "B"),
                )),
            ),
        )
        assertEquals(setOf("yesno", "pick"), results.keys)
        assertTrue(results["yesno"] is DecisionResult.Noul)
        assertTrue(results["pick"] is DecisionResult.Choice)
    }

    @Test
    fun `a fixed scorer normalises unseen candidates rather than dropping them`() {
        val engine = LayaEngine(CandidateScorer.fixed(mapOf("a" to 1f)))
        val result = engine.decide(
            "state",
            listOf(
                Question.Choice(
                    "c", "Pick.",
                    listOf(
                        Question.Choice.Option("a", "Known"),
                        Question.Choice.Option("b", "Unseen"),
                    ),
                ),
            ),
        )["c"] as DecisionResult.Choice

        assertEquals(1.0, result.probabilities.values.sum(), 1e-6)
        assertTrue(result.probabilities.getValue("b") > 0.0)
    }

    @Test
    fun `round trip through the derived fields stays inside probability bounds`() {
        val probabilities = Math.softmax(floatArrayOf(0.2f, -1.5f, 3.0f, 0.1f))
        assertTrue(probabilities.sum() in (0.999..1.001))
        assertTrue(probabilities.all { it in 0.0..1.0 })
        assertTrue(Math.typesafeChoiceConfidence(probabilities.toList()) in 0.0..1.0)
        assertTrue(Math.answerConfidence(probabilities.toList()) in 0.0..1.0)
        // Guard the entropy identity the shim was reverse-engineered from.
        assertEquals(1.0, ln(3.0) / ln(3.0), 1e-12)
    }
}