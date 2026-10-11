package com.chuse.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chuse.core.BooleanCriteria
import com.chuse.core.DecisionResult
import com.chuse.core.Question
import com.chuse.data.LayaRepository
import com.chuse.data.ModelStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the screen is doing right now. */
sealed interface AskState {
    data object Idle : AskState
    data object Working : AskState
    data class Answered(val result: DecisionResult) : AskState
    data class Failed(val message: String) : AskState
}

/**
 * Holds the model and the result of the one question on screen.
 *
 * There is no history here by design: [reset] is the only way back to the form, so the app
 * cannot accumulate a conversation it was never meant to have.
 */
class LayaViewModel(private val repository: LayaRepository) : ViewModel() {

    val modelStatus: StateFlow<ModelStatus> = repository.status

    private val _askState = MutableStateFlow<AskState>(AskState.Idle)
    val askState: StateFlow<AskState> = _askState.asStateFlow()

    fun askYesNo(situation: String, question: String) = ask(
        situation,
        Question.Noul(
            id = ANSWER_ID,
            instructions = question,
            criteria = BooleanCriteria(yes = "true", no = "false"),
        ),
    )

    fun askChoice(situation: String, question: String, options: List<Pair<String, String>>) = ask(
        situation,
        Question.Choice(
            id = ANSWER_ID,
            instructions = question,
            criteria = options.map { Question.Choice.Option(it.first, it.second) },
        ),
    )

    fun askScore(situation: String, question: String, levels: List<String>) = ask(
        situation,
        Question.Score(id = ANSWER_ID, instructions = question, criteria = levels),
    )

    fun reset() {
        _askState.value = AskState.Idle
    }

    private fun ask(situation: String, question: Question) {
        if (_askState.value is AskState.Working) return
        _askState.value = AskState.Working
        viewModelScope.launch {
            _askState.value = try {
                val answers = repository.decide(situation, listOf(question))
                AskState.Answered(answers.getValue(ANSWER_ID))
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                // Validation failures arrive as IllegalArgumentException from the engine and
                // belong next to the field that caused them; everything else is a real fault.
                AskState.Failed(failure.message ?: failure::class.java.simpleName)
            }
        }
    }

    private companion object {
        /** One question per screen keeps the plumbing identical across all three modes. */
        const val ANSWER_ID = "answer"
    }
}