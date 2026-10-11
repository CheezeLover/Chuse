package com.chuse.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.chuse.ui.components.ProbabilityRing
import com.chuse.ui.components.formatPercent

/**
 * Yes or no: one situation, one question, one probability.
 *
 * The ring shows P(true) directly, because for a two-way question that number is the whole
 * answer. Confidence and certainty are shown beneath it as separate readings, because they
 * are separate numbers and collapsing them would overstate what the model said.
 */
@Composable
fun YesNoScreen(
    state: AskState,
    snackbarHostState: SnackbarHostState,
    onAsk: (situation: String, question: String) -> Unit,
    onBack: () -> Unit,
    onReset: () -> Unit,
) {
    var situation by rememberSaveable { mutableStateOf("") }
    var question by rememberSaveable { mutableStateOf("") }
    var asked by rememberSaveable { mutableStateOf(false) }

    val situationError = asked && situation.isBlank()
    val questionError = asked && question.isBlank()
    val canAsk = situation.isNotBlank() && question.isNotBlank()

    QuestionScaffold(
        title = stringResource(R.string.home_yesno_title),
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
            placeholder = stringResource(R.string.hint_instructions),
            isError = questionError,
        )
        if (questionError) ErrorText(stringResource(R.string.error_blank_question))
        Gap(20)

        AskButton(state = state, onAsk = {
            asked = true
            if (canAsk) onAsk(situation, question)
        })
        Gap(24)

        ResultReveal(visible = state is AskState.Answered) {
            val answer = (state as? AskState.Answered)?.result as? DecisionResult.Noul
            if (answer != null) YesNoResult(answer)
        }
        Gap(40)
    }
}

@Composable
private fun YesNoResult(answer: DecisionResult.Noul) {
    val isTrue = answer.noul >= 0.5
    val verdict = stringResource(if (isTrue) R.string.result_true else R.string.result_false)

    ResultCard {
        Text(
            text = stringResource(R.string.label_question),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Gap(8)
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        ) {
            ProbabilityRing(
                progress = answer.noul.toFloat(),
                label = verdict,
                caption = formatPercent(answer.noul),
                contentDescription = stringResource(R.string.cd_confidence_gauge),
            )
        }
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