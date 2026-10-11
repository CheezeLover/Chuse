package com.chuse.core

/**
 * How a question becomes text the model reads.
 *
 * This is the one place the app's phrasing lives, kept separate so it can be swapped in a
 * single edit when the real shim source is available to port from. Everything downstream —
 * scoring, softmax, confidence — is independent of the wording here, so changing this
 * object cannot desynchronise the arithmetic.
 */
interface PromptFormat {
    fun system(): String
    fun user(state: String, question: Question): String

    companion object {
        val Default: PromptFormat = DefaultPromptFormat
    }
}

object DefaultPromptFormat : PromptFormat {

    override fun system(): String =
        "You are Laya, a decision model. You do not explain, advise, or converse. " +
            "You read a situation and answer with exactly one of the labels you are given, " +
            "choosing the one that best fits."

    override fun user(state: String, question: Question): String = buildString {
        appendLine("Situation:")
        appendLine(state.trim())
        appendLine()
        when (question) {
            is Question.Noul -> {
                val criteria = question.criteria
                if (criteria != null) {
                    appendLine("Choose '${criteria.yes}' if this holds:")
                }
                appendLine(question.instructions.trim())
            }

            is Question.Choice -> {
                appendLine(question.instructions.trim())
                appendLine()
                question.criteria.forEach { option ->
                    appendLine("- ${option.label}: ${option.gloss}")
                }
            }

            is Question.Score -> {
                appendLine(question.instructions.trim())
                appendLine()
                appendLine("Scale, from lowest to highest:")
                question.criteria.forEachIndexed { index, label ->
                    appendLine("- ${index + 1}. $label")
                }
            }
        }
        appendLine()
        append("Answer with one label only.")
    }
}