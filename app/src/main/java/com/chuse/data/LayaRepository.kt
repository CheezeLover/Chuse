package com.chuse.data

import android.content.Context
import com.chuse.core.CloseableScorer
import com.chuse.core.DecisionResult
import com.chuse.core.LayaEngine
import com.chuse.core.Question
import com.chuse.core.ScoringRequest
import com.chuse.inference.LlamaScorer
import com.chuse.inference.ModelLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable

/** Where answers are coming from, which the UI states plainly rather than hiding. */
sealed interface ModelStatus {
    data object Loading : ModelStatus
    data class Ready(val isStandIn: Boolean) : ModelStatus
    data class Failed(val reason: String) : ModelStatus
}

/**
 * Owns the model and turns questions into answers.
 *
 * Loading happens once, in the background, and the model stays resident afterwards: in a
 * one-shot app, reload latency would otherwise dominate every single interaction.
 */
class LayaRepository(private val loadScorer: (String) -> CloseableScorer) : Closeable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scorer: CloseableScorer = StandInScorer()

    private val _status = MutableStateFlow<ModelStatus>(ModelStatus.Loading)
    val status: StateFlow<ModelStatus> = _status.asStateFlow()

    /**
     * Resolves the model and marks the app ready.
     *
     * A build with no GGUF is a supported state rather than a failure: the interface runs
     * against a stand-in so it can be built, reviewed and demoed before the model lands, and
     * [ModelStatus.Ready.isStandIn] lets the UI say so out loud.
     */
    fun start(modelPath: String?) {
        scope.launch {
            _status.value = if (modelPath == null) {
                // Stated rather than left to the field's initial value: the stand-in has to be
                // a decision, not the side effect of never having assigned `scorer`.
                scorer = StandInScorer()
                ModelStatus.Ready(isStandIn = true)
            } else {
                runCatching { loadScorer(modelPath) }.fold(
                    onSuccess = {
                        scorer = it
                        ModelStatus.Ready(isStandIn = false)
                    },
                    onFailure = { ModelStatus.Failed(it.message ?: it::class.java.simpleName) },
                )
            }
        }
    }

    /**
     * Answers [questions] about [situation], off the main thread.
     *
     * Failures propagate to the caller, which turns them into something the user can act on;
     * guessing here which failures are worth showing would only hide some of them.
     */
    suspend fun decide(
        question: Question,
        state: String = "",
    ): DecisionResult = withContext(Dispatchers.Default) {
        LayaEngine(scorer).decide(question, state)
    }

    override fun close() {
        scorer.close()
    }

    companion object {
        /** Builds a repository and begins loading, given an app context. */
        fun create(context: Context): LayaRepository = LayaRepository { path ->
            LlamaScorer.load(path)
        }.also { it.start(ModelLocator.resolve(context.applicationContext)) }
    }
}

/**
 * Produces plausible-looking scores without a model, so the interface is usable before the
 * GGUF is available.
 *
 * This is emphatically not Laya. It hashes the prompt and each candidate to a fixed value,
 * which gives stable, varied enough results to exercise the layout — and is useless as
 * judgement. [ModelStatus.Ready.isStandIn] exists so the UI never presents it as the model's.
 */
class StandInScorer : CloseableScorer {

    override fun score(request: ScoringRequest, temperature: Float): FloatArray =
        FloatArray(request.options.size) { index ->
            // Hashing the option with the request keeps the ranking stable for a given
            // question while varying it across questions.
            val seed = (request.instructions + request.options[index].key).hashCode()
            // Spread across about two logits, so softmax lands on a decisive but not degenerate
            // distribution — the shape a real model produces, rather than a coin flip.
            ((seed ushr 8) % 1000) / 1000f * 2f - 1f
        }

    override fun close() = Unit
}