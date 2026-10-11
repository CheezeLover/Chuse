package com.chuse.core

/** The three question types Laya answers, matching the GGUF's `decision` output columns. */
enum class QuestionType(val wireCode: Int) {
    Choice(0),
    Score(1),
    Noul(2),
}

/**
 * One question to put to the model.
 *
 * This mirrors what a decision model actually consumes — a state, an instruction, and the
 * options being compared — rather than a prompt string. The rendering is the backend's job,
 * because the template belongs to the model: Laya's layout is fixed in its GGUF, and a
 * different checkpoint should need no Kotlin change.
 *
 * [options] is parallel to nothing: it is the ordered list of candidates, and for a `Score`
 * question each entry's `key` is the level's index rather than a user-facing label.
 */
data class ScoringRequest(
    val state: String,
    val instructions: String,
    val type: QuestionType,
    val options: List<Candidate>,
) {
    init {
        require(state.isNotBlank()) { "state must not be blank" }
        require(instructions.isNotBlank()) { "instructions must not be blank" }
        require(options.size >= 2) { "a decision needs at least two options" }
    }

    /** One candidate: its key is what the model reads, its description is optional context. */
    data class Candidate(val key: String, val description: String = "")
}

/**
 * Scores candidate answers for a request.
 *
 * Implementations return raw, un-normalised scores — one per option, in option order,
 * higher is more likely. Softmax normalisation and every derived figure belong to
 * [LayaEngine], so a stand-in and the real model produce comparable numbers.
 *
 * The temperature is passed in rather than applied here because it is a property of the
 * model, read from its GGUF metadata and varying by question type and option count.
 */
fun interface CandidateScorer {
    fun score(request: ScoringRequest, temperature: Float): FloatArray

    companion object {
        /**
         * A scorer that answers from a fixed table, for tests and for exercising the UI
         * before a model is present. Options outside the table score [unseenScore].
         */
        fun fixed(table: Map<String, Float>, unseenScore: Float = 0f): CandidateScorer {
            require(table.isNotEmpty()) { "fixed scorer needs at least one entry" }
            return CandidateScorer { request, _ ->
                request.options.map { table[it.key] ?: unseenScore }.toFloatArray()
            }
        }
    }
}