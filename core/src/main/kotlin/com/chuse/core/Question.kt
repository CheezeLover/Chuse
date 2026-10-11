package com.chuse.core

/**
 * One typed question, mirroring the wire contract of the laya-shim `/decide` endpoint.
 *
 * The three shapes exist because the shim supports them and nothing else. Note the
 * deliberate asymmetry in [criteria]: `Choice` and `Score` require one, `Noul` does not.
 * Getting that backwards is a validation error upstream, so it is a type error here.
 */
sealed interface Question {
    val id: String
    val instructions: String

    /** `noul` — the probability that [Question.instructions] holds of [state]. */
    data class Noul(
        override val id: String,
        override val instructions: String,
        val criteria: BooleanCriteria? = null,
    ) : Question {
        init {
            require(id.isNotBlank()) { "question id must not be blank" }
            require(instructions.isNotBlank()) { "instructions required on every question type" }
        }
    }

    /**
     * `choice` — rank [criteria] against [state]; ordered, so a list rather than a Map
     * even though the wire format uses a JSON object. Object key order is not defined by
     * JSON, and the ranking order is user-visible, so order is carried here.
     */
    data class Choice(
        override val id: String,
        override val instructions: String,
        val criteria: List<Option>,
    ) : Question {
        init {
            require(id.isNotBlank()) { "question id must not be blank" }
            require(instructions.isNotBlank()) { "instructions required on every question type" }
            require(criteria.size >= 2) { "a choice question needs at least two options" }
            require(criteria.map { it.label }.toSet().size == criteria.size) {
                "option labels must be distinct"
            }
        }

        data class Option(val label: String, val gloss: String)
    }

    /**
     * `score` — an expected level on an ordered scale. [criteria] is lowest-first,
     * which fixes the meaning of the returned number: 0.0 is the bottom of the scale
     * regardless of what the labels say.
     */
    data class Score(
        override val id: String,
        override val instructions: String,
        val criteria: List<String>,
    ) : Question {
        init {
            require(id.isNotBlank()) { "question id must not be blank" }
            require(instructions.isNotBlank()) { "instructions required on every question type" }
            require(criteria.size >= 2) { "a score question needs at least two levels" }
            require(criteria.all { it.isNotBlank() }) { "score level labels must not be blank" }
        }
    }
}

/** The optional true/false glosses a `noul` question may carry. */
data class BooleanCriteria(val yes: String, val no: String)