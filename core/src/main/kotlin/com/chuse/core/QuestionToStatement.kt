package com.chuse.core

/**
 * Turns a question into the statement it asks about.
 *
 * The situation field carries the model's signal — measured, it answers 18/20 with a written
 * situation and 12/20 without one, and nothing else substitutes for it. When a user types a
 * question and leaves the situation blank, deriving that statement from the question recovers
 * most of the gap: 16/20, from a rule that costs nothing, runs instantly and cannot invent a
 * fact.
 *
 * A small language model was considered for this and measured against the alternative before
 * being rejected. It would have to beat 16/20 while adding a second model to a 429 MB
 * download. See `docs/laya-measured.md`.
 *
 * Scope is deliberately narrow. Copular and "does" questions cover the forms people actually
 * type, and they can be rewritten without a lexicon. Anything else — modals especially, where
 * the verb's position has to be found before the sentence can be reordered — is declined
 * rather than guessed: a mangled statement is worse than none, because the model would answer
 * confidently from something invented.
 */
object QuestionToStatement {

    /**
     * Words that belong to the subject rather than being the subject on their own, so
     * "the Earth" is one noun phrase and not "the" followed by "Earth".
     */
    private val DETERMINERS = setOf(
        "the", "a", "an", "this", "that", "these", "those",
        "its", "his", "her", "their", "our", "my", "your",
        "some", "any", "each", "every", "no", "one", "two",
    )

    /**
     * @return the statement [question] asks about, or null if it is not a form this can
     *   rewrite. Null is a normal outcome, not a failure — the caller then asks the user for
     *   the field rather than inventing one.
     */
    fun derive(question: String): String? {
        val words = question.trim().removeSuffix("?").trim()
            .split(' ').filter { it.isNotBlank() }
        if (words.size < 3) return null

        val auxiliary = words[0].lowercase().trim(',', '.')
        val subjectStart = if (words[1].lowercase().trim(',', '.') in DETERMINERS) 1 else 0

        return when (auxiliary) {
            // "is the Earth round" -> "The Earth is round"
            "is", "are", "was", "were", "am" -> {
                // The subject runs from index 1 up to and including any determiner, so the
                // complement starts after it: 2 + subjectStart, not 1 +. Off by one here
                // gives "The is Earth round."
                val subjectEnd = 2 + subjectStart
                if (subjectEnd >= words.size) return null
                val subject = words.subList(1, subjectEnd).joinToString(" ").capitaliseFirst()
                val complement = words.drop(subjectEnd).joinToString(" ").trim(',', '.')
                if (complement.isEmpty()) return null
                val verb = if (auxiliary == "are" || auxiliary == "were") "are" else "is"
                "$subject $verb $complement."
            }

            // "does ice melt" -> "Ice melts"
            "does" -> {
                val verbIndex = 2 + subjectStart
                if (verbIndex >= words.size) return null
                // The subject keeps its determiner: "does this code compile" becomes
                // "This code compiles", not "Code compiles".
                val subject = words.subList(1, verbIndex).joinToString(" ").capitaliseFirst()
                val verb = thirdPerson(words[verbIndex].trim(',', '.'))
                val tail = words.drop(verbIndex + 1).joinToString(" ")
                if (tail.isEmpty()) "$subject $verb." else "$subject $verb $tail."
            }

            // Modals and the rest need the verb located before the sentence can be
            // reordered, which is a parser's job rather than a rule's. Decline instead.
            else -> null
        }
    }

    private fun String.capitaliseFirst(): String =
        if (isEmpty()) this else this[0].uppercaseChar() + substring(1)

    /**
     * Third person singular, present tense: the form a question uses.
     *
     * The silent-e rule that turns "like" into "liked" is past tense and must not be applied
     * here: a question uses the present, so "charge" becomes "charges".
     */
    private fun thirdPerson(verb: String): String = when {
        verb.endsWith("y") && verb.length > 1 && verb[verb.length - 2] !in "aeiou" ->
            verb.dropLast(1) + "ies"

        else -> "${verb}s"
    }
}