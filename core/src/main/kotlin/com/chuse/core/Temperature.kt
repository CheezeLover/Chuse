package com.chuse.core

/**
 * Softmax temperatures, which depend on the question type and the number of options.
 *
 * They are not a tuning knob: each is calibrated in the model itself, because the raw scores
 * it produces are only comparable after division by the right one. Skipping this, or using a
 * flat 1.0, gives probabilities that look plausible and are wrong — which is worse than an
 * error, because nothing downstream can tell.
 *
 * The defaults below are the values in `Laya-Q8_0.gguf` from `ggml-org/Laya-GGUF`. They are
 * a copy so the engine is testable and so a build without a model still behaves sensibly;
 * the native layer reads the real values from the GGUF at load time, so a different
 * checkpoint overrides these without a code change.
 */
interface TemperatureTable {
    fun forQuestion(question: Question, optionCount: Int): Float

    companion object {
        val Default: TemperatureTable = LayaTemperatures
    }
}

/** The temperatures shipped with `Laya-Q8_0.gguf`. */
object LayaTemperatures : TemperatureTable {

    private val choice = mapOf(
        2 to 1.906356f,
        5 to 1.760152f,
        10 to 1.000016f,
        11 to 0.100583f,
        Int.MAX_VALUE to 0.100583f,
    )
    private val score = mapOf(
        2 to 1.251430f,
        5 to 1.251430f,
        Int.MAX_VALUE to 1.251430f,
    )
    private val noul = mapOf(
        2 to 1.983400f,
        Int.MAX_VALUE to 1.983400f,
    )

    private val fallbacks = mapOf(
        QuestionType.Choice to 1.636903f,
        QuestionType.Score to 1.251430f,
        QuestionType.Noul to 1.983400f,
    )

    override fun forQuestion(question: Question, optionCount: Int): Float {
        val table = when (question) {
            is Question.Noul -> noul
            is Question.Choice -> choice
            is Question.Score -> score
        }
        val entry = table.entries.firstOrNull { optionCount <= it.key } ?: table.entries.last()
        return entry.value
    }

    /** The per-type value used when no bucket matches, exposed for tests and diagnostics. */
    fun fallback(type: QuestionType): Float = fallbacks.getValue(type)
}