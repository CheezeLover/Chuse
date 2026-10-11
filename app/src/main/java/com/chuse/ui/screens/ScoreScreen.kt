package com.chuse.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chuse.R
import com.chuse.core.DecisionResult
import com.chuse.ui.AskState
import com.chuse.ui.components.ConfidenceReadout
import com.chuse.ui.components.ScoreScale

/**
 * Default scale, chosen to be neutral in English and Chinese and to stay meaningful if the
 * user never edits it. Lowest first, because that is the order the engine expects and the
 * order the score counts from.
 */
private val DEFAULT_LEVELS = listOf("Very low", "Low", "Medium", "High", "Very high")

/**
 * A score on a scale the user defines.
 *
 * The scale is editable because "how bad is this" and "how excited are you" need different
 * words, and the score is only as meaningful as the scale it was read against.
 */
@Composable
fun ScoreScreen(
    state: AskState,
    snackbarHostState: SnackbarHostState,
    onAsk: (situation: String, question: String, levels: List<String>) -> Unit,
    onBack: () -> Unit,
    onReset: () -> Unit,
) {
    var situation by rememberSaveable { mutableStateOf("") }
    var question by rememberSaveable { mutableStateOf("") }
    var levels by remember { mutableStateOf(DEFAULT_LEVELS) }
    var asked by rememberSaveable { mutableStateOf(false) }

    val filled = levels.filter { it.isNotBlank() }
    val hasBlank = filled.size != levels.size

    val situationError = asked && situation.isBlank()
    val questionError = asked && question.isBlank()
    val levelError = asked && filled.size < 2
    val blankLevelError = hasBlank

    val canAsk = situation.isNotBlank() && question.isNotBlank() &&
        filled.size >= 2 && !hasBlank

    QuestionScaffold(
        title = stringResource(R.string.home_score_title),
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
            text = stringResource(R.string.label_scale),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Gap(8)

        levels.forEachIndexed { index, level ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                LabeledField(
                    label = stringResource(R.string.hint_scale_level, index + 1),
                    value = level,
                    onValueChange = { updated ->
                        levels = levels.toMutableList().also { it[index] = updated }
                    },
                    placeholder = stringResource(R.string.hint_scale_level, index + 1),
                    modifier = Modifier.weight(1f),
                    isError = blankLevelError && level.isBlank(),
                )
                RemoveRowButton(
                    description = stringResource(R.string.action_remove_level, index + 1),
                    enabled = levels.size > 2,
                    onClick = { levels = levels.toMutableList().also { it.removeAt(index) } },
                )
            }
        }

        if (levelError) ErrorText(stringResource(R.string.error_needs_two_levels))
        if (blankLevelError) ErrorText(stringResource(R.string.error_blank_level))
        Gap(4)

        AddRowButton(
            label = stringResource(R.string.action_add_level),
            onClick = { levels = levels + "" },
        )
        Gap(20)

        AskButton(state = state, onAsk = {
            asked = true
            if (canAsk) onAsk(situation, question, filled)
        })
        Gap(24)

        ResultReveal(visible = state is AskState.Answered) {
            val answer = (state as? AskState.Answered)?.result as? DecisionResult.Score
            if (answer != null) ScoreResult(answer)
        }
        Gap(40)
    }
}

@Composable
private fun ScoreResult(answer: DecisionResult.Score) {
    val levels = answer.legend.values.toList()
    val probabilities = levels.indices.map { answer.probabilities[it] ?: 0.0 }
    // The scale runs 0..n-1, so the marker is the expected level placed on that range.
    val topIndex = (levels.size - 1).coerceAtLeast(1).toFloat()
    val position = (answer.score / topIndex).toFloat()

    ResultCard {
        Text(
            text = stringResource(R.string.result_score),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Gap(8)
        ScoreScale(
            position = position,
            levels = levels,
            probabilities = probabilities,
            scoreLabel = formatScore(answer.score, levels.size),
        )
        Gap(20)
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

/**
 * Renders the score against the scale, e.g. "2.4 of 4" rather than a bare number — the
 * scale is what makes it mean anything.
 */
private fun formatScore(score: Double, levels: Int): String {
    val rounded = kotlin.math.round(score * 10) / 10
    val text = if (score == rounded) rounded.toInt().toString() else rounded.toString()
    return "$text / ${(levels - 1)}"
}