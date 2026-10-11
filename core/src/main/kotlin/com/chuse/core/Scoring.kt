package com.chuse.core

/**
 * A question asked against some state, handed to the scoring backend.
 *
 * Messages are kept structured rather than flattened into a string because the chat
 * template belongs to the model, not to us: the native layer applies whatever template
 * is baked into the GGUF, so a model with a different template needs no Kotlin change.
 */
data class ScoringRequest(
    val system: String,
    val user: String,
    val candidates: List<String>,
)

/**
 * Scores candidate answers for a request. Implementations return raw, un-normalised
 * log-scores — one per candidate, higher is more likely. Softmax normalisation and all
 * derived figures belong to [LayaEngine], so every backend produces comparable numbers.
 */
fun interface CandidateScorer {
    fun score(request: ScoringRequest): FloatArray

    companion object {
        /**
         * A scorer that answers from a fixed table, for tests and for exercising the UI
         * before a model is present. Candidates outside the table score [unseenScore].
         */
        fun fixed(table: Map<String, Float>, unseenScore: Float = 0f): CandidateScorer {
            require(table.isNotEmpty()) { "fixed scorer needs at least one entry" }
            return CandidateScorer { request ->
                request.candidates.map { table[it] ?: unseenScore }.toFloatArray()
            }
        }
    }
}