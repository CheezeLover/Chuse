package com.chuse.core

/**
 * Turns a state and typed questions into answers, by asking a [CandidateScorer] for the
 * relative likelihood of each candidate label and doing the arithmetic here.
 *
 * All three question types share one path: build candidates, score, softmax. What differs
 * is only how the resulting distribution is reported — a probability for `noul`, a ranking
 * for `choice`, an expectation for `score`. Keeping the shared path shared is what stops
 * the three modes from drifting apart as they are tuned.
 *
 * The engine is deliberately free of Android types so it can be exercised on the JVM
 * against the live shim (see `ParityTest`).
 */
class LayaEngine(
    private val scorer: CandidateScorer,
    private val prompts: PromptFormat = PromptFormat.Default,
) {

    fun decide(state: String, questions: List<Question>): Map<String, DecisionResult> {
        require(state.isNotBlank()) { "state must not be blank" }
        require(questions.isNotEmpty()) { "at least one question is required" }
        val duplicates = questions.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) { "duplicate question ids: ${duplicates.joinToString()}" }

        val system = prompts.system()
        return questions.associate { question ->
            question.id to when (question) {
                is Question.Noul -> decideNoul(system, state, question)
                is Question.Choice -> decideChoice(system, state, question)
                is Question.Score -> decideScore(system, state, question)
            }
        }
    }

    private fun decideNoul(system: String, state: String, question: Question.Noul): DecisionResult {
        // The candidate labels are what the model actually emits; the criteria only colour
        // the prompt, so they never reach the scorer.
        val candidates = listOf(
            question.criteria?.yes ?: TRUE_LABEL,
            question.criteria?.no ?: FALSE_LABEL,
        )
        val probabilities = normalise(system, state, question, candidates)
        val yes = probabilities[0]
        return DecisionResult.Noul(
            noul = Math.round(yes),
            // For a two-way question both readings collapse to max(p, 1-p): the TypeSafe
            // formula's uniform denominator is 0.5, and entropy over two outcomes is the
            // same quantity. Stated rather than derived so the two are not silently
            // conflated if either ever changes.
            confidence = Math.typesafeChoiceConfidence(probabilities.toList()),
            answerConfidence = Math.answerConfidence(probabilities.toList()),
        )
    }

    private fun decideChoice(
        system: String,
        state: String,
        question: Question.Choice,
    ): DecisionResult {
        val candidates = question.criteria.map { it.label }
        val probabilities = normalise(system, state, question, candidates)
        // Indexed by position, not by label: the scorer's array lines up with the candidate
        // order, and looking up by label would fail the moment an option repeats.
        val distribution = question.criteria
            .mapIndexed { index, option -> option.label to probabilities[index] }
            .toMap()
        val topProbability = distribution.values.max()
        // First match wins a tie, so an even split resolves to the option the user listed
        // first rather than to map iteration order.
        val winner = question.criteria.first { it.label == distribution.entries.first { it.value == topProbability }.key }
        return DecisionResult.Choice(
            choice = winner.label,
            probabilities = distribution,
            confidence = Math.typesafeChoiceConfidence(probabilities.toList()),
            answerConfidence = Math.answerConfidence(probabilities.toList()),
        )
    }

    private fun decideScore(
        system: String,
        state: String,
        question: Question.Score,
    ): DecisionResult {
        val probabilities = normalise(system, state, question, question.criteria)
        val byIndex = question.criteria.indices.associateWith { probabilities[it] }
        return DecisionResult.Score(
            score = Math.expectedLevel(probabilities.toList()),
            legend = question.criteria.withIndex().associate { (index, label) -> index to label },
            probabilities = byIndex,
            confidence = Math.typesafeScoreConfidence(probabilities.toList()),
            answerConfidence = Math.answerConfidence(probabilities.toList()),
        )
    }

    private fun normalise(
        system: String,
        state: String,
        question: Question,
        candidates: List<String>,
    ): DoubleArray {
        val request = ScoringRequest(
            system = system,
            user = prompts.user(state, question),
            candidates = candidates,
        )
        val scores = scorer.score(request)
        // A scorer that returns the wrong count would silently shift every probability
        // against the wrong label, so this is checked rather than trusted.
        require(scores.size == candidates.size) {
            "scorer returned ${scores.size} scores for ${candidates.size} candidates"
        }
        return Math.softmax(scores)
    }

    companion object {
        const val TRUE_LABEL = "true"
        const val FALSE_LABEL = "false"
    }
}