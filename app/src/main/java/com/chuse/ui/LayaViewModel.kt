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
 * Holds the model and the result of the question on screen.
 *
 * There is no history by design: [reset] is the only way back, so the app cannot accumulate a
 * conversation it was never meant to have.
 */
class LayaViewModel(private val repository: LayaRepository) : ViewModel() {

    val modelStatus: StateFlow<ModelStatus> = repository.status

    private val _askState = MutableStateFlow<AskState>(AskState.Idle)
    val askState: StateFlow<AskState> = _askState.asStateFlow()

    /** Asks a yes/no question. [state] may be empty — the model answers either way. */
    fun askYesNo(question: String, state: String = "") = ask(
        Question.Noul(id = "answer", instructions = question, criteria = BooleanCriteria()),
        state,
    )

    fun askChoice(
        question: String,
        options: List<Pair<String, String>>,
        state: String = "",
    ) = ask(
        Question.Choice(
            id = "answer",
            instructions = question,
            criteria = options.map { Question.Choice.Option(it.first, it.second) },
        ),
        state,
    )

    fun askScore(question: String, levels: List<String>, state: String = "") =
        ask(Question.Score(id = "answer", instructions = question, criteria = levels), state)

    fun reset() {
        _askState.value = AskState.Idle
    }

    private fun ask(question: Question, state: String) {
        if (_askState.value is AskState.Working) return
        _askState.value = AskState.Working
        viewModelScope.launch {
            _askState.value = try {
                AskState.Answered(repository.decide(question, state))
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                // Validation failures arrive as IllegalArgumentException from the engine and
                // belong next to the field that caused them; everything else is a real fault.
                AskState.Failed(failure.message ?: failure::class.java.simpleName)
            }
        }
    }
}