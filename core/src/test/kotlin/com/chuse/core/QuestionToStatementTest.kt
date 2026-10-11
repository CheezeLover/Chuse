package com.chuse.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuestionToStatementTest {

    @Test
    fun `a simple is question becomes a statement`() {
        assertEquals("Water is wet.", QuestionToStatement.derive("Is water wet?"))
    }

    @Test
    fun `a determiner belongs to the subject, not the predicate`() {
        // The case that makes the naive rewrite produce "The is Earth round."
        assertEquals("The Earth is round.", QuestionToStatement.derive("Is the Earth round?"))
        assertEquals(
            "This sentence is grammatically correct.",
            QuestionToStatement.derive("Is this sentence grammatically correct?"),
        )
        assertEquals("The meeting is at 3pm.", QuestionToStatement.derive("Is the meeting at 3pm?"))
    }

    @Test
    fun `does takes a third person verb`() {
        assertEquals(
            "Ice melts at room temperature.",
            QuestionToStatement.derive("Does ice melt at room temperature?"),
        )
        assertEquals("This code compiles.", QuestionToStatement.derive("Does this code compile?"))
    }

    @Test
    fun `verb inflection is present tense, not past`() {
        // The silent-e rule belongs to past tense ("like" -> "liked") and must not be applied
        // here: a question uses the present, so "charge" becomes "charges".
        assertEquals("The device charges.", QuestionToStatement.derive("Does the device charge?"))
        assertEquals("The policy applies.", QuestionToStatement.derive("Does the policy apply?"))
        assertEquals("The box opens.", QuestionToStatement.derive("Does the box open?"))
        assertEquals("Ice melts.", QuestionToStatement.derive("Does ice melt?"))
    }

    @Test
    fun `a bare number subject is left alone`() {
        assertEquals("100 is greater than 50.", QuestionToStatement.derive("Is 100 greater than 50?"))
    }

    @Test
    fun `the trailing question mark is optional`() {
        assertEquals(
            QuestionToStatement.derive("Is water wet?"),
            QuestionToStatement.derive("Is water wet"),
        )
    }

    @Test
    fun `whitespace in the question does not survive into the statement`() {
        assertEquals("Water is wet.", QuestionToStatement.derive("Is   water    wet?"))
    }

    @Test
    fun `modals are declined rather than guessed`() {
        // Reordering "can a phone run" needs the verb's position found first, which is a
        // parser's job. Guessing produced "A can phone run a model offline."
        assertNull(QuestionToStatement.derive("Can a phone run a model offline?"))
        assertNull(QuestionToStatement.derive("Will this compile?"))
        assertNull(QuestionToStatement.derive("Should I deploy on Friday?"))
    }

    @Test
    fun `wh questions are declined`() {
        assertNull(QuestionToStatement.derive("What is the capital of France?"))
        assertNull(QuestionToStatement.derive("Which option is best?"))
        assertNull(QuestionToStatement.derive("How severe is this?"))
        assertNull(QuestionToStatement.derive("Why did the build fail?"))
    }

    @Test
    fun `fragments with nothing to rewrite are declined`() {
        // Returning null is deliberate: a mangled statement is worse than none, because the
        // model would answer from something invented and look confident doing it.
        assertNull(QuestionToStatement.derive("Is?"))
        assertNull(QuestionToStatement.derive("Is"))
        assertNull(QuestionToStatement.derive(""))
        assertNull(QuestionToStatement.derive("   "))
        assertNull(QuestionToStatement.derive("Rank these options"))
    }

    @Test
    fun `a derived statement is always terminated with a full stop`() {
        for (q in listOf("Is water wet?", "Does ice melt?", "Is the Earth round?")) {
            val derived = QuestionToStatement.derive(q)
            assertEquals("$q -> $derived", true, derived!!.endsWith("."))
        }
    }

    @Test
    fun `no derived statement leaks the original punctuation`() {
        for (q in listOf("Is water wet?", "Does ice melt?", "Is the Earth round?")) {
            val derived = QuestionToStatement.derive(q)!!
            assertEquals("$q -> $derived", false, derived.contains('?'))
        }
    }
}