package com.chuse.data

import com.chuse.core.BooleanCriteria
import com.chuse.core.CloseableScorer
import com.chuse.core.DecisionResult
import com.chuse.core.Question
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
 * covered by the fact that it compiles and by parity against the shim, not here — it needs a
 * model and a device.
 */
class LayaRepositoryTest {

    private class FakeScorer(
        private val table: Map<String, Float> = emptyMap(),
        private val unseen: Float = 0f,
    ) : CloseableScorer {
        var closed = false
            private set
        var lastCandidates: List<String> = emptyList()
            private set

        override fun score(request: ScoringRequest): FloatArray {
            lastCandidates = request.candidates
            return request.candidates.map { table[it] ?: unseen }.toFloatArray()
        }

        override fun close() {
            closed = true
        }
    }

    private suspend fun awaitReady(repository: LayaRepository): ModelStatus.Ready =
        withTimeout(5_000) { repository.status.first { it !is ModelStatus.Loading } as ModelStatus.Ready }

    @Test
    fun `a bundled model loads and is reported as the real scorer`() = runBlocking {
        val scorer = FakeScorer(mapOf("true" to 5f, "false" to 0f))
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

        val status = withTimeout(5_000) {
            repository.status.first { it !is ModelStatus.Loading }
        }
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
    fun `a yes-no question comes back with the model's probability`() = runBlocking {
        val repository = LayaRepository { FakeScorer(mapOf("true" to 4f, "false" to 0f)) }
        // A path, so the injected scorer is the one in use — with no model the repository
        // deliberately answers from the stand-in instead.
        repository.start("/models/laya.gguf")
        awaitReady(repository)

        val result = repository.decide(
            "The sky is blue.",
            listOf(
                Question.Noul("answer", "Is this true?", BooleanCriteria("true", "false")),
            ),
        )["answer"] as DecisionResult.Noul

        assertTrue("noul was ${result.noul}", result.noul > 0.9)
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
            listOf(
                Question.Choice(
                    "answer",
                    "What do they need?",
                    listOf(
                        Question.Choice.Option("laptop", "A portable computer"),
                        Question.Choice.Option("phone", "A handheld device"),
                        Question.Choice.Option("tablet", "A large touch device"),
                    ),
                ),
            ),
        )["answer"] as DecisionResult.Choice

        // Order matters: the scorer's array lines up positionally with the candidate list, so
        // a reordering here would silently attach probabilities to the wrong labels.
        assertEquals(listOf("laptop", "phone", "tablet"), scorer.lastCandidates)
        assertEquals("laptop", result.choice)
        repository.close()
    }

    @Test
    fun `a score question is measured against the scale the user gave`() = runBlocking {
        val repository = LayaRepository { FakeScorer(mapOf("low" to -2f, "high" to 3f)) }
        repository.start("/models/laya.gguf")
        awaitReady(repository)

        val result = repository.decide(
            "A build failed.",
            listOf(Question.Score("answer", "How bad?", listOf("low", "high"))),
        )["answer"] as DecisionResult.Score

        assertEquals(listOf("low", "high"), result.legend.values.toList())
        assertTrue("score was ${result.score}", result.score > 0.5)
        repository.close()
    }

    @Test
    fun `a blank situation is refused before the model is consulted`() = runBlocking {
        val scorer = FakeScorer()
        val repository = LayaRepository { scorer }
        repository.start("/models/laya.gguf")
        awaitReady(repository)

        val failure = runCatching {
            repository.decide("   ", listOf(Question.Noul("answer", "Is it true?")))
        }.exceptionOrNull()

        assertTrue("expected a rejection, got $failure", failure is IllegalArgumentException)
        assertTrue("the scorer should not have run", scorer.lastCandidates.isEmpty())
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
                listOf(
                    Question.Choice("answer", "Pick.", listOf(Question.Choice.Option("only", "One"))),
                ),
            )
        }.exceptionOrNull()

        assertTrue("expected a rejection, got $failure", failure is IllegalArgumentException)
        repository.close()
    }

    @Test
    fun `the stand-in is stable for the same question and differs across questions`() {
        val scorer = StandInScorer()
        val first = ScoringRequest("sys", "Is the sky blue?", listOf("true", "false"))
        val again = ScoringRequest("sys", "Is the sky blue?", listOf("true", "false"))
        val other = ScoringRequest("sys", "Is the sky green?", listOf("true", "false"))

        val a = scorer.score(first)
        val b = scorer.score(again)
        val c = scorer.score(other)

        // Stability is what makes the stand-in usable for eyeballing the UI: a ranking that
        // reshuffles on every recomposition would make it look broken.
        assertTrue(a.contentEquals(b))
        assertTrue("stand-in gave identical scores for different questions", !a.contentEquals(c))
    }

    @Test
    fun `the stand-in answers every mode without a model`() = runBlocking {
        val repository = LayaRepository { error("no model in this test") }
        repository.start(null)
        awaitReady(repository)

        // Each of the three modes must produce a usable shape with no GGUF present, since a
        // build without one still has to be demoable.
        val noul = repository.decide(
            "state",
            listOf(Question.Noul("a", "True?")),
        )["a"]
        val choice = repository.decide(
            "state",
            listOf(
                Question.Choice(
                    "b", "Pick.",
                    listOf(
                        Question.Choice.Option("x", "X"),
                        Question.Choice.Option("y", "Y"),
                    ),
                ),
            ),
        )["b"]
        val score = repository.decide(
            "state",
            listOf(Question.Score("c", "How?", listOf("low", "high"))),
        )["c"]

        assertTrue(noul is DecisionResult.Noul)
        assertTrue(choice is DecisionResult.Choice)
        assertTrue(score is DecisionResult.Score)
        repository.close()
    }
}