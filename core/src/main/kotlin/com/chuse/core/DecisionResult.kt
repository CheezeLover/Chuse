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

    /**
 * Softmax over raw scores.
 *
 * @param temperature divides the scores before the exponential. Decision models calibrate
 *   this per question type and option count; 1.0 is a plain softmax and is only correct for a
 *   model that does not calibrate its scores.
 */
fun softmax(scores: FloatArray, temperature: Float = 1.0f): DoubleArray {
        require(scores.isNotEmpty()) { "cannot normalise an empty score vector" }
        require(temperature > 0.0f && temperature.isFinite()) {
            "temperature must be positive and finite, got $temperature"
        }
        // Guard the subtraction below against a non-finite backend rather than letting it
        // silently produce NaN probabilities that would reach the UI as garbage.
        require(scores.all { it.isFinite() }) { "scorer returned a non-finite score" }
        val max = scores.max()
        val exps = DoubleArray(scores.size) {
            kotlin.math.exp((scores[it] - max).toDouble() / temperature)
        }
        val total = exps.sum()
        require(total > 0.0 && total.isFinite()) { "softmax overflowed to a degenerate total" }
        return DoubleArray(scores.size) { exps[it] / total }
    }

    /**
 * The probability of the most likely outcome.
 *
 * This has no llama.cpp equivalent — it is a `laya-shim` concept. For a two-way question
 * it coincides with the TypeSafe confidence, but that is arithmetic, not a shared
 * definition. The UI labels the two separately for the same reason.
 */
fun answerConfidence(probabilities: List<Double>): Double =
        round(probabilities.max())

/**
 * Confidence, as published by TypeSafe and implemented by llama.cpp.
 *
 * These are *not* the shim's confidence, which is one minus normalised entropy. For a
 * 0.977 / 0.023 split the TypeSafe formula gives 0.954 and entropy gives 0.842 — two
 * different quantities that share a field name, so they must not be conflated.
 *
 * @param probabilities the distribution over options, in scale order.
 */
fun typesafeChoiceConfidence(probabilities: List<Double>): Double {
        require(probabilities.size >= 2) { "confidence needs at least two outcomes" }
        val n = probabilities.size.toDouble()
        val uniform = 1.0 / n
        val pMax = probabilities.max()
        // A uniform distribution has no winner, which makes this 0 by construction; the
        // guard is only there because 1 - uniform is never exactly 0 for finite n.
        if (uniform >= 1.0) return 1.0
        return round(maxOf(0.0, (pMax - uniform) / (1.0 - uniform)))
    }

    /**
     * Confidence for an ordered scale, per TypeSafe.
     *
     * Mean distance from the modal level, relative to the mean distance of a uniform
     * distribution around the centre of the scale. An even spread across the whole scale
     * scores 0; a spike at any level scores near 1.
     */
    fun typesafeScoreConfidence(probabilities: List<Double>): Double {
        require(probabilities.size >= 2) { "confidence needs at least two outcomes" }
        val n = probabilities.size
        val mode = probabilities.indices.maxByOrNull { probabilities[it] }!!
        var distance = 0.0
        var uniformDistance = 0.0
        for (i in 0 until n) {
            distance += probabilities[i] * kotlin.math.abs(i - mode.toDouble())
            uniformDistance += kotlin.math.abs(i - (n - 1) / 2.0) / n
        }
        if (uniformDistance <= 0.0) return 1.0
        return round(maxOf(0.0, 1.0 - distance / uniformDistance))
    }

    /**
     * One minus normalised entropy.
     *
     * This is what `laya-shim` reports as `confidence`; llama.cpp does not use it. It is
     * kept because the shim remains the reference the parity tests check against, and
     * because the two disagree in a way worth being able to demonstrate.
     */
    fun normalisedEntropyConfidence(probabilities: List<Double>): Double {
        require(probabilities.size >= 2) { "confidence needs at least two outcomes" }
        val entropy = probabilities.sumOf { if (it > 0.0) -it * kotlin.math.ln(it) else 0.0 }
        val maxEntropy = kotlin.math.ln(probabilities.size.toDouble())
        return round(1.0 - entropy / maxEntropy)
    }

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