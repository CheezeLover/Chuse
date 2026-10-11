package com.chuse.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chuse.R
import com.chuse.core.DecisionResult
import com.chuse.ui.AskState
import com.chuse.ui.components.ConfidenceReadout
import com.chuse.ui.components.RankedRow

/** One editable choice: a name the model ranks, and a description to rank it by. */
private data class ChoiceDraft(val label: String = "", val gloss: String = "")

/**
 * Rank a list of choices against a question.
 *
 * Choices are validated before they are sent — a duplicate name would produce a distribution
 * keyed on a value that appears twice, so the engine rejects it rather than answering with a
 * result the user cannot read.
 */
@Composable
fun ChoiceScreen(
    state: AskState,
    snackbarHostState: SnackbarHostState,
    onAsk: (situation: String, question: String, options: List<Pair<String, String>>) -> Unit,
    onBack: () -> Unit,
    onReset: () -> Unit,
) {
    var situation by rememberSaveable { mutableStateOf("") }
    var question by rememberSaveable { mutableStateOf("") }
    var choices by remember {
        mutableStateOf(listOf(ChoiceDraft(), ChoiceDraft()))
    }
    var asked by rememberSaveable { mutableStateOf(false) }

    val filled = choices.filter { it.label.isNotBlank() }
    val labels = filled.map { it.label.trim() }
    val duplicate = labels.size != labels.toSet().size

    val situationError = asked && situation.isBlank()
    val questionError = asked && question.isBlank()
    val choiceError = asked && filled.size < 2
    val duplicateError = duplicate

    val canAsk = situation.isNotBlank() && question.isNotBlank() &&
        filled.size >= 2 && !duplicate

    QuestionScaffold(
        title = stringResource(R.string.home_choice_title),
        state = state,
        onBack = onBack,
        onReset = onReset,
        snackbarHostState = snackbarHostState,
    ) {
        FailureSnackbar(state, snackbarHostState)

        LabeledField(
            label = stringResource(R.string.label_situation),
            value = situation,
            onValueChange = { situation = it },
            placeholder = stringResource(R.string.hint_situation),
            singleLine = false,
            minLines = 3,
            isError = situationError,
        )
        if (situationError) ErrorText(stringResource(R.string.error_blank_situation))
        Gap()

        LabeledField(
            label = stringResource(R.string.label_question),
            value = question,
            onValueChange = { question = it },
            placeholder = stringResource(R.string.hint_question),
            isError = questionError,
        )
        if (questionError) ErrorText(stringResource(R.string.error_blank_question))
        Gap(20)

        Text(
            text = stringResource(R.string.label_choices),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Gap(8)

        choices.forEachIndexed { index, draft ->
            Column(modifier = Modifier.padding(bottom = 12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LabeledField(
                        label = stringResource(R.string.label_choice, index + 1),
                        value = draft.label,
                        onValueChange = { updated ->
                            choices = choices.toMutableList().also { it[index] = draft.copy(label = updated) }
                        },
                        placeholder = stringResource(R.string.label_choice, index + 1),
                        modifier = Modifier.weight(1f),
                        isError = duplicateError && draft.label.isNotBlank(),
                    )
                    Spacer(Modifier.width(4.dp))
                    RemoveRowButton(
                        description = stringResource(R.string.action_remove_choice, index + 1),
                        enabled = choices.size > 2,
                        onClick = { choices = choices.toMutableList().also { it.removeAt(index) } },
                    )
                }
                Spacer(Modifier.padding(top = 6.dp))
                LabeledField(
                    label = stringResource(R.string.label_description),
                    value = draft.gloss,
                    onValueChange = { updated ->
                        choices = choices.toMutableList().also { it[index] = draft.copy(gloss = updated) }
                    },
                    placeholder = "",
                )
            }
        }

        if (choiceError) ErrorText(stringResource(R.string.error_needs_two_choices))
        if (duplicateError) ErrorText(stringResource(R.string.error_duplicate_choice))
        Gap(4)

        AddRowButton(
            label = stringResource(R.string.action_add_choice),
            onClick = { choices = choices + ChoiceDraft() },
        )
        Gap(20)

        AskButton(state = state, onAsk = {
            asked = true
            if (canAsk) {
                onAsk(
                    situation,
                    question,
                    filled.map { it.label.trim() to it.gloss.trim() },
                )
            }
        })
        Gap(24)

        ResultReveal(visible = state is AskState.Answered) {
            val answer = (state as? AskState.Answered)?.result as? DecisionResult.Choice
            if (answer != null) ChoiceResult(answer)
        }
        Gap(40)
    }
}

@Composable
private fun ChoiceResult(answer: DecisionResult.Choice) {
    // Ranked by probability, highest first; the model's ordering of the criteria is not the
    // ranking, and showing it in input order would bury the answer.
    val ranked = answer.probabilities.entries.sortedByDescending { it.value }

    ResultCard {
        Text(
            text = stringResource(R.string.result_ranked),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Gap(8)
        ranked.forEachIndexed { index, (label, probability) ->
            RankedRow(
                rank = index + 1,
                label = label,
                detail = "",
                probability = probability,
            )
        }
        Gap(16)
        ConfidenceReadout(
            answerConfidence = answer.answerConfidence,
            certainty = answer.confidence,
            confidenceLabel = stringResource(R.string.confidence_label),
            confidenceHint = stringResource(R.string.confidence_hint),
            certaintyLabel = stringResource(R.string.certainty_label),
            certaintyHint = stringResource(R.string.certainty_hint),
        )
    }
}