package com.chuse.core

/**
 * Turns a question into an answer, by asking a [CandidateScorer] for the relative likelihood
 * of each option and doing the arithmetic here.
 *
 * All three question types share one path: build options, score, softmax. What differs is
 * only how the resulting distribution is reported — a probability for `noul`, a ranking for
 * `choice`, an expectation for `score`. Keeping the shared path shared is what stops the
 * three modes from drifting apart as they are tuned.
 *
 * [state] is optional. A question on its own is a weak prompt for this model, but the user
 * should not be blocked from asking: the interface explains what a missing state costs
 * rather than refusing the question. Measured on a balanced twenty cases: 18/20 with a state,
 * 12/20 without, and no fallback does better — repeating the question as the state scores
 * 10/20, and deriving the state from the question with [QuestionToStatement] scores 10/20
 * too, because a statement derived from a question asserts the polarity being asked about and
 * so biases the answer towards true. See `docs/laya-measured.md`.
 *
 * The engine is deliberately free of Android types so it can be exercised on the JVM against
 * the live shim (see `ParityTest`).
 */
class LayaEngine(
    private val scorer: CandidateScorer,
    private val temperatures: TemperatureTable = TemperatureTable.Default,
) {

    fun decide(question: Question, state: String = ""): DecisionResult {
        val state = state.trim()

        // One definition of the option order, shared with the result builders below. Having
        // it written twice is how the two copies drift and a probability ends up attached to
        // the wrong label.
        val options = question.wireOptions()

        val temperature = temperatures.forQuestion(question, options.size)
        val request = ScoringRequest(
            state = state,
            instructions = question.instructions,
            type = question.wireType,
            options = options,
        )
        val scores = scorer.score(request, temperature)
        // A scorer returning the wrong count would silently attach every probability to the
        // wrong label, so this is checked rather than trusted.
        require(scores.size == options.size) {
            "scorer returned ${scores.size} scores for ${options.size} options"
        }

        val probabilities = Math.softmax(scores, temperature)

        return when (question) {
            is Question.Noul -> noulResult(question, probabilities)
            is Question.Choice -> choiceResult(question, probabilities)
            is Question.Score -> scoreResult(question, probabilities)
        }
    }

    private fun noulResult(question: Question.Noul, probabilities: DoubleArray): DecisionResult {
        // The key is positional, not the custom criteria: a noul always asks the same
        // question and its two columns mean "true" and "false" in that order. The criteria
        // only colour the prompt, so a custom "holds"/"fails" pair must not move them.
        val trueIndex = question.wireOptions().indexOfFirst { it.key == "true" }.coerceAtLeast(0)
        return DecisionResult.Noul(
            noul = Math.round(probabilities[trueIndex]),
            // For a two-way question both readings collapse to max(p, 1-p): the TypeSafe
            // formula's uniform denominator is 0.5, and entropy over two outcomes is the
            // same quantity.
            confidence = Math.typesafeChoiceConfidence(probabilities.toList()),
            answerConfidence = Math.answerConfidence(probabilities.toList()),
        )
    }

    private fun choiceResult(question: Question.Choice, probabilities: DoubleArray): DecisionResult {
        val options = question.wireOptions()
        val distribution = options.mapIndexed { index, option -> option.key to probabilities[index] }.toMap()
        // First match wins a tie, so an even split resolves to the option the user listed
        // first rather than to map iteration order.
        val top = distribution.values.max()
        val winner = options.first { it.key == distribution.entries.first { it.value == top }.key }
        return DecisionResult.Choice(
            choice = winner.key,
            probabilities = distribution,
            confidence = Math.typesafeChoiceConfidence(probabilities.toList()),
            answerConfidence = Math.answerConfidence(probabilities.toList()),
        )
    }

    private fun scoreResult(question: Question.Score, probabilities: DoubleArray): DecisionResult {
        val byIndex = question.criteria.indices.associateWith { probabilities[it] }
        return DecisionResult.Score(
            score = Math.expectedLevel(probabilities.toList()),
            legend = question.criteria.withIndex().associate { (index, label) -> index to label },
            probabilities = byIndex,
            confidence = Math.typesafeScoreConfidence(probabilities.toList()),
            answerConfidence = Math.answerConfidence(probabilities.toList()),
        )
    }
}

/** The options a question presents to the model, in the order the model reads them. */
internal fun Question.wireOptions(): List<ScoringRequest.Candidate> = when (this) {
    // Keys stay "true"/"false" whatever the user calls the options: the model has two fixed
    // output columns and their meaning is positional, so the criteria are the description,
    // never the key.
    //
    // With no criteria the description falls back to the key itself, giving the symmetric
    // "true: true" / "false: false" the model was trained on. The GGUF's own fallback
    // phrasing biases answers negative when measured — see docs/laya-measured.md.
    is Question.Noul -> listOf(
        ScoringRequest.Candidate("true", criteria?.yes.orEmpty()),
        ScoringRequest.Candidate("false", criteria?.no.orEmpty()),
    )

    is Question.Choice -> criteria.map { ScoringRequest.Candidate(it.label, it.gloss) }

    // Keyed by position, because a score is an expectation over the scale's indices.
    is Question.Score -> criteria.mapIndexed { index, label ->
        ScoringRequest.Candidate(index.toString(), label)
    }
}

/** The question type as the model numbers it. */
internal val Question.wireType: QuestionType
    get() = when (this) {
        is Question.Noul -> QuestionType.Noul
        is Question.Choice -> QuestionType.Choice
        is Question.Score -> QuestionType.Score
    }