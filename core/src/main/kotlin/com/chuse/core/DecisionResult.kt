package com.chuse.core

import kotlin.math.abs

/**
 * The answer to a [Question]. Field names match the shim's response so the two are
 * directly comparable — the parity tests compare them field by field.
 */
sealed interface DecisionResult {
    val type: String
    val confidence: Double
    val answerConfidence: Double

    /** `noul` — probability the statement holds. */
    data class Noul(
        val noul: Double,
        override val confidence: Double,
        override val answerConfidence: Double,
    ) : DecisionResult {
        override val type: String get() = "noul"
    }

    /** `choice` — the winning label and the full distribution. */
    data class Choice(
        val choice: String,
        val probabilities: Map<String, Double>,
        override val confidence: Double,
        override val answerConfidence: Double,
    ) : DecisionResult {
        override val type: String get() = "choice"
    }

    /** `score` — expected level, plus the scale it was read against. */
    data class Score(
        val score: Double,
        val legend: Map<Int, String>,
        val probabilities: Map<Int, Double>,
        override val confidence: Double,
        override val answerConfidence: Double,
    ) : DecisionResult {
        override val type: String get() = "score"
    }
}

/**
 * Arithmetic shared by all three types, verified against the live shim.
 *
 * These formulas are not guesses — they were recovered by probing `laya-shim 0.4.1` and
 * reproducing its numbers exactly. `confidence` in particular is *not* the top probability:
 * it is one minus normalised entropy, which is why a two-way split of 0.977/0.023
 * reports 0.842 while its `answer_confidence` reports 0.977.
 */
object Math {

    /** Decimal places the shim reports. Kept identical so parity is an exact comparison. */
    const val PRECISION = 4

    fun softmax(scores: FloatArray): DoubleArray {
        require(scores.isNotEmpty()) { "cannot normalise an empty score vector" }
        // Guard the subtraction below against a non-finite backend rather than letting it
        // silently produce NaN probabilities that would reach the UI as garbage.
        require(scores.all { it.isFinite() }) { "scorer returned a non-finite score" }
        val max = scores.max()
        val exps = DoubleArray(scores.size) { kotlin.math.exp((scores[it] - max).toDouble()) }
        val total = exps.sum()
        require(total > 0.0 && total.isFinite()) { "softmax overflowed to a degenerate total" }
        return DoubleArray(scores.size) { exps[it] / total }
    }

    /**
     * One minus normalised entropy: 1.0 when the model is certain, near 0.0 when the
     * distribution is flat. Matches the shim exactly on every probe taken.
     */
    fun confidence(probabilities: List<Double>): Double {
        require(probabilities.size >= 2) { "confidence needs at least two outcomes" }
        val entropy = probabilities.sumOf { if (it > 0.0) -it * kotlin.math.ln(it) else 0.0 }
        val maxEntropy = kotlin.math.ln(probabilities.size.toDouble())
        return round(1.0 - entropy / maxEntropy)
    }

    /** The probability of the most likely outcome, per type. */
    fun answerConfidence(probabilities: List<Double>): Double =
        round(probabilities.max())

    /** Expected level over an ordered scale. The scale is lowest-first, so index is the level. */
    fun expectedLevel(probabilities: List<Double>): Double =
        round(probabilities.mapIndexed { index, p -> index * p }.sum())

    fun round(value: Double): Double {
        // Built by iteration rather than with pow: inside `object Math` a bare `Math.`
        // resolves to this object instead of to kotlin.math, which is a confusing way
        // to fail, and the fully qualified form is shadowed too.
        var factor = 1.0
        repeat(PRECISION) { factor *= 10.0 }
        return kotlin.math.round(value * factor) / factor
    }
}

/** Tolerance for asserting parity between this engine and the live shim. */
internal fun assertClose(
    actual: Double,
    expected: Double,
    tolerance: Double = 1e-4,
    label: String = "",
) {
    check(abs(actual - expected) <= tolerance) {
        "${label.ifEmpty { "value" }}: got $actual, expected $expected (tolerance $tolerance)"
    }
}