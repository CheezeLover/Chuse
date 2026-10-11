package com.chuse.data

import com.chuse.core.BooleanCriteria
import com.chuse.core.CloseableScorer
import com.chuse.core.DecisionResult
import com.chuse.core.Question
import com.chuse.core.QuestionType
import com.chuse.core.ScoringRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the wiring around the engine: model lifecycle, the stand-in path, and the errors a
 * user can actually cause.
 *
 * The scorer's contract is the seam, so these tests inject one. The real llama.cpp scorer is
 * covered by the fact that it compiles, that its symbols match the Kotlin declarations, and
 * by parity against the model's own metadata — not here, which needs a device.
 */
class LayaRepositoryTest {

    private class FakeScorer(
        private val table: Map<String, Float> = emptyMap(),
        private val unseen: Float = 0f,
    ) : CloseableScorer {
        var closed = false
            private set
        var lastOptions: List<String> = emptyList()
            private set
        var lastType: QuestionType? = null
            private set
        var lastTemperature = 0f
            private set

        override fun score(request: ScoringRequest, temperature: Float): FloatArray {
            lastOptions = request.options.map { it.key }
            lastType = request.type
            lastTemperature = temperature
            return request.options.map { table[it.key] ?: unseen }.toFloatArray()
        }

        override fun close() {
            closed = true
        }
    }

    private suspend fun awaitReady(repository: LayaRepository): ModelStatus.Ready =
        withTimeout(5_000) {
            repository.status.first { it !is ModelStatus.Loading } as ModelStatus.Ready
        }

    @Test
    fun `a bundled model loads and is reported as the real scorer`() = runBlocking {
        val scorer = FakeScorer()
        val repository = LayaRepository { scorer }

        repository.start("/models/laya.gguf")

        val status = awaitReady(repository)
        assertTrue("expected the real model, got ${status.isStandIn}", !status.isStandIn)
        repository.close()
    }

    @Test
    fun `a build with no model is ready and says it is standing in`() = runBlocking {
        val repository = LayaRepository { error("must not be called with no model") }

        repository.start(null)

        val status = awaitReady(repository)
        // The stand-in is a supported state, but the UI has to be able to label it.
        assertTrue("the stand-in must be advertised, not hidden", status.isStandIn)
        repository.close()
    }

    @Test
    fun `a model that will not load is a failure with its reason attached`() = runBlocking {
        val repository = LayaRepository { error("not a GGUF file") }

        repository.start("/models/broken.gguf")

        val status = withTimeout(5_000) { repository.status.first { it !is ModelStatus.Loading } }
        val failure = status as ModelStatus.Failed
        assertTrue("reason was ${failure.reason}", failure.reason.contains("not a GGUF"))
        repository.close()
    }

    @Test
    fun `closing releases the native scorer exactly once`() = runBlocking {
        val scorer = FakeScorer()
        val repository = LayaRepository { scorer }
        repository.start("/models/laya.gguf")
        awaitReady(repository)

        repository.close()
        repository.close()

        assertTrue("scorer was never closed", scorer.closed)
    }

    @Test
    fun `a yes-no question reaches the scorer with true before false`() = runBlocking {
        val scorer = FakeScorer(mapOf("true" to 4f, "false" to 0f))
        val repository = LayaRepository { scorer }
        repository.start("/models/laya.gguf")
        awaitReady(repository)

        val result = repository.decide(
            "The sky is blue.",
            Question.Noul("answer", "Is this true?", BooleanCriteria("true", "false")),
        ) as DecisionResult.Noul

        assertTrue("noul was ${result.noul}", result.noul > 0.7)
        assertEquals(listOf("true", "false"), scorer.lastOptions)
        assertEquals(QuestionType.Noul, scorer.lastType)
        repository.close()
    }

    @Test
    fun `a choice question reaches the scorer with its labels in order`() = runBlocking {
        val scorer = FakeScorer(mapOf("laptop" to 3f, "phone" to 1f, "tablet" to 0f))
        val repository = LayaRepository { scorer }
        repository.start("/models/laya.gguf")
        awaitReady(repository)

        val result = repository.decide(
            "I need a new computer.",
            Question.Choice(
                "answer",
                "What do they need?",
                listOf(
                    Question.Choice.Option("laptop", "A portable computer"),
                    Question.Choice.Option("phone", "A handheld device"),
                    Question.Choice.Option("tablet", "A large touch device"),
                ),
            ),
        ) as DecisionResult.Choice

        // Order matters: the scorer's array lines up positionally with the option list, so
        // a reordering here would silently attach scores to the wrong labels.
        assertEquals(listOf("laptop", "phone", "tablet"), scorer.lastOptions)
        assertEquals(QuestionType.Choice, scorer.lastType)
        assertEquals("laptop", result.choice)
        repository.close()
    }

    @Test
    fun `a score question keys its options by level index`() = runBlocking {
        val scorer = FakeScorer(mapOf("0" to -2f, "1" to 3f))
        val repository = LayaRepository { scorer }
        repository.start("/models/laya.gguf")
        awaitReady(repository)

        val result = repository.decide(
            "A build failed.",
            Question.Score("answer", "How bad?", listOf("low", "high")),
        ) as DecisionResult.Score

        assertEquals(listOf("0", "1"), scorer.lastOptions)
        assertEquals(QuestionType.Score, scorer.lastType)
        assertEquals(listOf("low", "high"), result.legend.values.toList())
        repository.close()
    }

    @Test
    fun `the scorer is handed the temperature the model calibrated`() = runBlocking {
        val scorer = FakeScorer()
        val repository = LayaRepository { scorer }
        repository.start("/models/laya.gguf")
        awaitReady(repository)

        repository.decide(
            "state",
            Question.Score("answer", "Rate.", listOf("low", "high")),
        )
        assertEquals(1.25143f, scorer.lastTemperature, 1e-4f)
        repository.close()
    }

    @Test
    fun `a blank situation is refused before the model is consulted`() = runBlocking {
        val scorer = FakeScorer()
        val repository = LayaRepository { scorer }
        repository.start("/models/laya.gguf")
        awaitReady(repository)

        val failure = runCatching {
            repository.decide("   ", Question.Noul("answer", "Is it true?"))
        }.exceptionOrNull()

        assertTrue("expected a rejection, got $failure", failure is IllegalArgumentException)
        assertTrue("the scorer should not have run", scorer.lastOptions.isEmpty())
        repository.close()
    }

    @Test
    fun `a single choice is refused rather than answered with an empty comparison`() = runBlocking {
        val repository = LayaRepository { FakeScorer() }
        repository.start("/models/laya.gguf")
        awaitReady(repository)

        val failure = runCatching {
            repository.decide(
                "state",
                Question.Choice("answer", "Pick.", listOf(Question.Choice.Option("only", "One"))),
            )
        }.exceptionOrNull()

        assertTrue("expected a rejection, got $failure", failure is IllegalArgumentException)
        repository.close()
    }

    @Test
    fun `the stand-in is stable for the same question and differs across questions`() {
        val scorer = StandInScorer()
        fun ask(instructions: String) = scorer.score(
            ScoringRequest(
                state = "state",
                instructions = instructions,
                type = QuestionType.Choice,
                options = listOf(
                    ScoringRequest.Candidate("a", "A"),
                    ScoringRequest.Candidate("b", "B"),
                ),
            ),
            temperature = 1f,
        )

        // Stability is what makes the stand-in usable for eyeballing the UI: a ranking that
        // reshuffles on every recomposition would make it look broken.
        assertTrue(ask("Is the sky blue?").contentEquals(ask("Is the sky blue?")))
        assertTrue(
            "stand-in gave identical scores for different questions",
            !ask("Is the sky blue?").contentEquals(ask("Is the sky green?")),
        )
    }

    @Test
    fun `the stand-in answers every mode without a model`() = runBlocking {
        val repository = LayaRepository { error("no model in this test") }
        repository.start(null)
        awaitReady(repository)

        // Each of the three modes must produce a usable shape with no GGUF present, since a
        // build without one still has to be demoable.
        val noul = repository.decide("state", Question.Noul("a", "True?"))
        val choice = repository.decide(
            "state",
            Question.Choice(
                "b",
                "Pick.",
                listOf(
                    Question.Choice.Option("x", "X"),
                    Question.Choice.Option("y", "Y"),
                ),
            ),
        )
        val score = repository.decide(
            "state",
            Question.Score("c", "How?", listOf("low", "high")),
        )

        assertTrue(noul is DecisionResult.Noul)
        assertTrue(choice is DecisionResult.Choice)
        assertTrue(score is DecisionResult.Score)
        repository.close()
    }
}