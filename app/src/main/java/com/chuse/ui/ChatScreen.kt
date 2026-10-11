package com.chuse.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chuse.R
import com.chuse.core.DecisionResult
import com.chuse.data.ModelStatus
import kotlin.math.roundToInt

/** Percentages are whole numbers: 97.56% would imply precision the model does not have. */
private fun formatPercent(probability: Double): String =
    "${(probability.coerceIn(0.0, 1.0) * 100).roundToInt()}%"

/**
 * The whole app: one page, input at the bottom, answers above.
 *
 * Deliberately not a conversation. Asking clears the input and replaces the answer, so there
 * is always at most one question on screen and no scrollback to read back through — which is
 * the point of a System 1 tool rather than a chat window.
 */
@Composable
fun ChatScreen(viewModel: LayaViewModel) {
    val modelStatus by viewModel.modelStatus.collectAsState()
    val askState by viewModel.askState.collectAsState()

    var mode by remember { mutableStateOf(Mode.YesNo) }
    var question by remember { mutableStateOf("") }
    var state by remember { mutableStateOf("") }
    var choices by remember { mutableStateOf(listOf("", "")) }
    var levels by remember { mutableStateOf(defaultLevels()) }

    val listState = rememberLazyListState()
    val answer = (askState as? AskState.Answered)?.result
    val working = askState is AskState.Working

    // Keep the newest content visible when the answer lands or the input zone expands.
    LaunchedEffect(answer, mode, choices.size, levels.size) {
        listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
    }

    fun ask() {
        if (working || question.isBlank()) return
        when (mode) {
            Mode.YesNo -> viewModel.askYesNo(question, state)
            Mode.Choice -> {
                val filled = choices.filter { it.isNotBlank() }
                if (filled.size >= 2) viewModel.askChoice(question, filled.map { it to "" }, state)
            }

            Mode.Score -> {
                val filled = levels.filter { it.isNotBlank() }
                if (filled.size >= 2) viewModel.askScore(question, filled, state)
            }
        }
    }

    Surface(color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Header(modelStatus, onClear = { viewModel.reset() })

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, bottom = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item("spacer") { Spacer(Modifier.height(4.dp)) }

                if (answer != null) {
                    item("answer") { AnswerBlock(answer) }
                } else {
                    item("hint") { HintBlock(mode) }
                }
            }

            InputZone(
                mode = mode,
                onModeChange = { newMode ->
                    mode = newMode
                    viewModel.reset()
                },
                question = question,
                onQuestionChange = { question = it },
                state = state,
                onStateChange = { state = it },
                choices = choices,
                onChoicesChange = { choices = it },
                levels = levels,
                onLevelsChange = { levels = it },
                canAsk = question.isNotBlank() &&
                    when (mode) {
                        Mode.YesNo -> true
                        Mode.Choice -> choices.count { it.isNotBlank() } >= 2
                        Mode.Score -> levels.count { it.isNotBlank() } >= 2
                    },
                working = working,
                onAsk = ::ask,
            )
        }
    }
}

private enum class Mode(val labelRes: Int) {
    YesNo(R.string.mode_yesno),
    Choice(R.string.mode_choice),
    Score(R.string.mode_score),
}

private fun defaultLevels(): List<String> =
    listOf("Very low", "Low", "Medium", "High", "Very high")

@Composable
private fun Header(status: ModelStatus, onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        ModelDot(status)
        Spacer(Modifier.width(6.dp))
        IconButton(onClick = onClear) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.action_clear),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A quiet status dot rather than a banner.
 *
 * The one thing that must stay unmissable is a missing model, because that changes what the
 * answers are — so that state still speaks up.
 */
@Composable
private fun ModelDot(status: ModelStatus) {
    data class Dot(val colour: Color, val labelRes: Int)

    val dot = when (status) {
        ModelStatus.Loading -> Dot(MaterialTheme.colorScheme.outline, R.string.model_loading)
        is ModelStatus.Ready ->
            if (status.isStandIn) Dot(MaterialTheme.colorScheme.tertiary, R.string.model_standin)
            else Dot(MaterialTheme.colorScheme.primary, R.string.model_ready)

        is ModelStatus.Failed -> Dot(MaterialTheme.colorScheme.error, R.string.model_failed_dot)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (status is ModelStatus.Loading) {
            CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp)
        } else {
            Box(
                Modifier
                    .size(8.dp)
                    .background(dot.colour, RoundedCornerShape(4.dp)),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(dot.labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HintBlock(mode: Mode) {
    val text = when (mode) {
        Mode.YesNo -> R.string.hint_yesno
        Mode.Choice -> R.string.hint_choice
        Mode.Score -> R.string.hint_score
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.prompt_start),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The answer, large.
 *
 * Deliberately not a sentence: Laya returns a distribution, not a conclusion, and dressing a
 * coin flip up as a verdict is the one thing worth avoiding here. The percentage carries the
 * weight of the claim.
 */
@Composable
private fun AnswerBlock(answer: DecisionResult) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (answer) {
            is DecisionResult.Noul -> VerdictAnswer(answer)
            is DecisionResult.Choice -> RankingAnswer(answer)
            is DecisionResult.Score -> ScoreAnswer(answer)
        }
        Spacer(Modifier.height(14.dp))
        ConfidenceRow(answer)
    }
}

@Composable
private fun VerdictAnswer(answer: DecisionResult.Noul) {
    val isTrue = answer.noul >= 0.5
    val verdict = stringResource(if (isTrue) R.string.result_true else R.string.result_false)
    // Animated so the number lands rather than blinking into place.
    val shown by animateFloatAsState(answer.noul.toFloat(), tween(600), label = "noul")

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = verdict,
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = formatPercent(shown.toDouble()),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RankingAnswer(answer: DecisionResult.Choice) {
    val ranked = answer.probabilities.entries.sortedByDescending { it.value }
    Column(modifier = Modifier.fillMaxWidth()) {
        ranked.forEachIndexed { index, (label, probability) ->
            val animated by animateFloatAsState(
                probability.toFloat(), tween(700), label = "rank",
            )
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatPercent(probability),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(5.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(5.dp),
                        ),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(animated.coerceIn(0f, 1f))
                            .height(10.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(5.dp)),
                    )
                }
            }
        }
    }
}

@Composable
private fun ScoreAnswer(answer: DecisionResult.Score) {
    val levels = answer.legend.values.toList()
    val rounded = answer.score.roundToInt().coerceIn(0, levels.lastIndex)
    val shown by animateFloatAsState(answer.score.toFloat(), tween(700), label = "score")

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = shown.roundToInt().toString(),
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.result_of, levels.lastIndex.toString()),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = levels.getOrElse(rounded) { levels.firstOrNull().orEmpty() },
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** The two readings, side by side. They are different numbers and mean different things. */
@Composable
private fun ConfidenceRow(answer: DecisionResult) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                RoundedCornerShape(16.dp),
            )
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Metric(
            formatPercent(answer.answerConfidence),
            stringResource(R.string.confidence_label),
            stringResource(R.string.confidence_hint),
            Modifier.weight(1f),
        )
        Metric(
            formatPercent(answer.confidence),
            stringResource(R.string.certainty_label),
            stringResource(R.string.certainty_hint),
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun Metric(value: String, label: String, hint: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The input zone, which grows when a mode needs more than one field.
 *
 * Keeping everything below the answer means the layout never shifts under the reader: the
 * question and its answer are always in the same place relative to each other.
 */
@Composable
private fun InputZone(
    mode: Mode,
    onModeChange: (Mode) -> Unit,
    question: String,
    onQuestionChange: (String) -> Unit,
    state: String,
    onStateChange: (String) -> Unit,
    choices: List<String>,
    onChoicesChange: (List<String>) -> Unit,
    levels: List<String>,
    onLevelsChange: (List<String>) -> Unit,
    canAsk: Boolean,
    working: Boolean,
    onAsk: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Mode.entries.forEach { candidate ->
                    val selected = candidate == mode
                    FilterChip(
                        selected = selected,
                        onClick = { onModeChange(candidate) },
                        label = { Text(stringResource(candidate.labelRes)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }

            AnimatedVisibility(
                visible = mode == Mode.Choice,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                ChoiceFields(choices, onChoicesChange)
            }

            AnimatedVisibility(
                visible = mode == Mode.Score,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                LevelFields(levels, onLevelsChange)
            }

            AnimatedVisibility(
                visible = state.isNotEmpty() || mode != Mode.YesNo,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state,
                        onValueChange = onStateChange,
                        label = { Text(stringResource(R.string.label_optional_context)) },
                        placeholder = { Text(stringResource(R.string.hint_optional_context)) },
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 1,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = question,
                    onValueChange = onQuestionChange,
                    placeholder = { Text(stringResource(R.string.hint_question)) },
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                )
                Spacer(Modifier.width(8.dp))
                SendButton(enabled = canAsk && !working, working = working, onClick = onAsk)
            }
        }
    }
}

@Composable
private fun SendButton(enabled: Boolean, working: Boolean, onClick: () -> Unit) {
    val container = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (enabled) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(52.dp)
            .background(container, RoundedCornerShape(26.dp)),
    ) {
        if (working) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = content)
        }
    }
}

@Composable
private fun ChoiceFields(choices: List<String>, onChange: (List<String>) -> Unit) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        choices.forEachIndexed { index, value ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { updated ->
                        onChange(choices.toMutableList().also { it[index] = updated })
                    },
                    placeholder = {
                        Text(stringResource(R.string.label_choice, index + 1))
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.weight(1f),
                )
                if (choices.size > 2) {
                    IconButton(onClick = { onChange(choices.filterIndexed { i, _ -> i != index }) }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.action_remove_choice, index + 1),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        AddRowButton(R.string.action_add_choice) {
            onChange(choices + "")
        }
    }
}

@Composable
private fun LevelFields(levels: List<String>, onChange: (List<String>) -> Unit) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            levels.forEachIndexed { index, value ->
                OutlinedTextField(
                    value = value,
                    onValueChange = { updated ->
                        onChange(levels.toMutableList().also { it[index] = updated })
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.label_scale_order),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onChange(levels + "") }) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.action_add_level),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AddRowButton(labelRes: Int, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(labelRes), style = MaterialTheme.typography.labelLarge)
    }
}