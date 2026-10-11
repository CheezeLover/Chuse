package com.chuse.core

/**
 * Turns a question into the statement it asks about.
 *
 * **Measured and not recommended.** The idea was that the situation field carries the model's
 * signal — it answers 18/20 with a written situation and 12/20 without one — so deriving the
 * statement from the question should recover the gap. It does not. `derived_state_real_probe`
 * measures the rule as the app actually uses it, and it scores 10/20 against 14/20 for an
 * empty situation, because the statement it writes always asserts the affirmative of what was
 * asked: "Is the Earth flat?" becomes "The Earth is flat.", and the model — which takes its
 * signal from the situation — believes it and answers true. It trades a lean towards false
 * for a harder lean towards true.
 *
 * The earlier 16/20 for this rule came from `derived_state_probe`, which scored its false cases
 * against a situation containing the correct answer. That is a leak the rule cannot produce in
 * use, and the number did not survive being measured honestly.
 *
 * The missing information is the world, and the only thing available to synthesise from is the
 * question — which carries the very polarity being asked about. No derivation recovers it.
 *
 * Scope is deliberately narrow for the same reason, and the rules are still narrow: copular and
 * "does" questions only, declined rather than guessed, because a mangled statement is worse
 * than none.
 */
object QuestionToStatement {

    /**
     * Words that belong to the subject rather than being the subject on their own, so
     * "the Earth" is one noun phrase and not "the" followed by "Earth".
     *
     * "one" and "two" were here for "Is one of these correct?", but they never worked: the
     * subject ran from index 1 to just past the determiner, giving "One of is these correct."
     * They also broke an ordinary question, since "two" in "Is two plus two equal to four?"
     * made the subject "two plus" and the rewrite came out as "Two plus is two equal to
     * four." Numerals are declined by [NUMERALS] instead.
     */
    private val DETERMINERS = setOf(
        "the", "a", "an", "this", "that", "these", "those",
        "its", "his", "her", "their", "our", "my", "your",
        "some", "any", "each", "every", "no",
    )

    /**
     * Cardinals that can appear in a subject the rule cannot rewrite.
     *
     * "Is two plus two equal to four?" is not the "subject is complement" shape these rules
     * assume — its subject is three words, and no determiner arithmetic finds that. Reading
     * the first word as the whole subject gives "Two is plus two equal to four.", which is
     * worse than declining because the model answers from it confidently. The same applies one
     * word along: "Is the two dogs friendly?" spans "the two", and the rewrite is "The two is
     * dogs friendly." Nothing in the word sequence distinguishes it from "Is the Earth round?",
     * which the rule does handle, so a numeral anywhere in the subject span is declined.
     *
     * Digits are deliberately absent: "Is 100 greater than 50?" does have a one-word subject,
     * and the existing rule already handles it.
     */
    private val NUMERALS = setOf(
        "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
        "eighteen", "nineteen", "twenty", "thirty", "forty", "fifty", "sixty", "seventy",
        "eighty", "ninety", "hundred", "thousand", "million", "billion",
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
                if (hasNumeral(words, 1, subjectEnd)) return null
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
                if (hasNumeral(words, 1, verbIndex)) return null
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

    /** Whether any word of `words[from, until)` is a spelled-out cardinal. */
    private fun hasNumeral(words: List<String>, from: Int, until: Int): Boolean =
        words.subList(from, until).any { it.lowercase().trim(',', '.') in NUMERALS }

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