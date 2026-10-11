package com.chuse.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chuse.R
import com.chuse.core.DecisionResult
import com.chuse.data.ModelStatus
import kotlin.math.roundToInt

/**
 * The app: one conversation-shaped page with a floating composer.
 *
 * A thread rather than a form-and-result, because that is what people expect from an
 * interface like this — but deliberately *not* a conversation. Asking replaces the exchange
 * rather than appending to it, so there is no scrollback and nothing to read back through.
 * The app is a System 1 instrument, not a chat window, and the distinction shows in the
 * absence of history rather than in a setting.
 */
@Composable
fun ChatScreen(viewModel: LayaViewModel) {
    val modelStatus by viewModel.modelStatus.collectAsState()
    val askState by viewModel.askState.collectAsState()

    var mode by remember { mutableStateOf(Mode.YesNo) }
    var question by remember { mutableStateOf("") }
    var context by remember { mutableStateOf("") }
    var choices by remember { mutableStateOf(listOf("", "")) }
    var levels by remember { mutableStateOf(DefaultLevels) }

    // What was last asked, shown above the answer. Held here rather than read back out of the
    // view model so clearing the answer and clearing the question are one action.
    var asked by remember { mutableStateOf<Exchange?>(null) }

    val answer = (askState as? AskState.Answered)?.result
    val working = askState is AskState.Working
    val listState = rememberLazyListState()

    // Follow the exchange as it changes. animateScrollToItem rather than scrollToItem so a new
    // answer eases in rather than jumping, and so this survives the keyboard opening under it.
    LaunchedEffect(answer, working) {
        if (answer != null || working) {
            listState.animateScrollToItem(0)
        }
    }

    fun ask() {
        val text = question.trim()
        if (working || text.isEmpty()) return
        when (mode) {
            Mode.YesNo -> viewModel.askYesNo(text, context)
            Mode.Choice -> {
                val filled = choices.map { it.trim() }.filter { it.isNotEmpty() }
                if (filled.size < 2) return
                viewModel.askChoice(text, filled.map { it to "" }, context)
            }

            Mode.Score -> {
                val filled = levels.map { it.trim() }.filter { it.isNotEmpty() }
                if (filled.size < 2) return
                viewModel.askScore(text, filled, context)
            }
        }
        asked = Exchange(text, mode)
        question = ""
    }

    fun clear() {
        viewModel.reset()
        asked = null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Only the keyboard inset, and only once. The composer also applies it below, and
            // applying both would pad twice — which is what pushes the send button off the
            // top of the keyboard on a device with a tall one.
            .windowInsetsPadding(WindowInsets.ime),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopBar(status = modelStatus, onClear = ::clear, hasExchange = asked != null)

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                item("thread") {
                    when {
                        working || answer != null -> ExchangeBlock(
                            exchange = asked,
                            working = working,
                            answer = answer,
                        )

                        else -> WelcomeBlock(
                            mode = mode,
                            onSuggestion = { q, c ->
                                question = q
                                context = c
                            },
                        )
                    }
                }
            }

            // The composer is a sibling of the thread rather than an overlay, so the thread
            // always ends above it and the answer can never end up underneath.
            Column(modifier = Modifier.fillMaxWidth()) {
                Composer(
                    mode = mode,
                    onModeChange = {
                        mode = it
                        viewModel.reset()
                    },
                    question = question,
                    onQuestionChange = { question = it },
                    context = context,
                    onContextChange = { context = it },
                    choices = choices,
                    onChoicesChange = { choices = it },
                    levels = levels,
                    onLevelsChange = { levels = it },
                    working = working,
                    canAsk = question.isNotBlank() && context.isNotBlank() && valid(mode, choices, levels),
                    onAsk = ::ask,
                )
            }
        }
    }
}

/** What was asked, so the answer has something to be an answer to. */
private data class Exchange(val question: String, val mode: Mode)

private enum class Mode(val labelRes: Int) {
    YesNo(R.string.mode_yesno),
    Choice(R.string.mode_choice),
    Score(R.string.mode_score),
}

private val DefaultLevels = listOf("Very low", "Low", "Medium", "High", "Very high")

private fun valid(mode: Mode, choices: List<String>, levels: List<String>): Boolean = when (mode) {
    Mode.YesNo -> true
    Mode.Choice -> choices.count { it.isNotBlank() } >= 2
    Mode.Score -> levels.count { it.isNotBlank() } >= 2
}

// ---------------------------------------------------------------------------
// Chrome
// ---------------------------------------------------------------------------

@Composable
private fun TopBar(status: ModelStatus, onClear: () -> Unit, hasExchange: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            // The window draws edge to edge, so the bar has to claim the status bar inset
            // itself. Without this the title sits underneath the clock and the phone icons.
            .statusBarsPadding()
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.2).sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        ModelStatusDot(status)
        AnimatedVisibility(visible = hasExchange, enter = fadeIn(), exit = fadeOut()) {
            IconButton(onClick = onClear) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.action_clear),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Model state as a dot and a word.
 *
 * Deliberately quiet. The only state that must be unmissable is a failed model, which is why
 * that one is coloured and the rest are not — a permanent banner saying "ready" would be
 * noise on every single launch.
 */
@Composable
private fun ModelStatusDot(status: ModelStatus) {
    val (colour, label) = when (status) {
        ModelStatus.Loading -> MaterialTheme.colorScheme.outlineVariant to R.string.model_loading
        is ModelStatus.Ready ->
            if (status.isStandIn) {
                MaterialTheme.colorScheme.tertiary to R.string.model_standin
            } else {
                MaterialTheme.colorScheme.primary to R.string.model_ready
            }

        is ModelStatus.Failed -> MaterialTheme.colorScheme.error to R.string.model_failed_dot
    }

    val animatedColour by animateColorAsState(colour, label = "status")

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (status is ModelStatus.Loading) {
            PulsingDot(color = MaterialTheme.colorScheme.outlineVariant)
        } else {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(animatedColour),
            )
        }
        Spacer(Modifier.width(7.dp))
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A slow breathing dot, used for both "loading" and "thinking". */
@Composable
private fun PulsingDot(color: Color, size: androidx.compose.ui.unit.Dp = 7.dp) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
        label = "pulse-alpha",
    )
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha)),
    )
}

// ---------------------------------------------------------------------------
// The thread
// ---------------------------------------------------------------------------

@Composable
private fun WelcomeBlock(mode: Mode, onSuggestion: (String, String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.prompt_start),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.3).sp,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.prompt_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 280.dp),
        )
        Spacer(Modifier.height(28.dp))
        SuggestionRow(mode, onSuggestion)
    }
}

/**
 * Tappable examples.
 *
 * Worth the space on an empty screen: this model takes a question in any phrasing, so showing
 * three real ones teaches the shape of a good input faster than instructions would.
 */
@Composable
private fun SuggestionRow(mode: Mode, onSuggestion: (String, String) -> Unit) {
    // Both halves of each example, because both fields are required. An example that only
    // filled the question would teach the shape that produces near-chance answers.
    val suggestions = when (mode) {
        Mode.YesNo -> listOf(
            Triple(R.string.example_yesno_q1, R.string.example_yesno_c1, R.string.example_yesno_1),
            Triple(R.string.example_yesno_q2, R.string.example_yesno_c2, R.string.example_yesno_2),
            Triple(R.string.example_yesno_q3, R.string.example_yesno_c3, R.string.example_yesno_3),
        )

        Mode.Choice -> listOf(
            Triple(R.string.example_choice_q1, R.string.example_choice_c1, R.string.example_choice_1),
            Triple(R.string.example_choice_q2, R.string.example_choice_c2, R.string.example_choice_2),
        )

        Mode.Score -> listOf(
            Triple(R.string.example_score_q1, R.string.example_score_c1, R.string.example_score_1),
            Triple(R.string.example_score_q2, R.string.example_score_c2, R.string.example_score_2),
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        suggestions.forEach { (questionRes, contextRes, labelRes) ->
            // Resolved here rather than inside the click handler: stringResource is
            // @Composable and the lambda is not a composable scope.
            val question = stringResource(questionRes)
            val context = stringResource(contextRes)
            Surface(
                onClick = { onSuggestion(question, context) },
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.widthIn(max = 340.dp),
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                    Text(
                        text = stringResource(labelRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = context,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * One exchange: the question as a bubble, the answer beneath it.
 *
 * Showing the question matters more than it sounds. An answer with no question attached reads
 * as a verdict on everything, and this model is far more equivocal than a bare True/False
 * would suggest — the bubble is what keeps the number honest about what it refers to.
 */
@Composable
private fun ExchangeBlock(exchange: Exchange?, working: Boolean, answer: DecisionResult?) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        exchange?.let { asked ->
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 12 },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(
                            topStart = 20.dp,
                            topEnd = 20.dp,
                            bottomStart = 20.dp,
                            bottomEnd = 6.dp,
                        ),
                        modifier = Modifier.widthIn(max = 300.dp),
                    ) {
                        Column {
                            Text(
                                text = asked.question,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                            )
                            // Name the mode only when it added something beyond the question,
                            // so the bubble stays a plain question for a plain yes/no.
                            if (asked.mode != Mode.YesNo) {
                                Text(
                                    text = stringResource(
                                        when (asked.mode) {
                                            Mode.Choice -> R.string.bubble_choice
                                            Mode.Score -> R.string.bubble_score
                                            Mode.YesNo -> R.string.mode_yesno
                                        },
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                        .copy(alpha = 0.65f),
                                    modifier = Modifier.padding(
                                        start = 16.dp, end = 16.dp, bottom = 9.dp,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }

        when {
            working -> ThinkingBlock()
            answer != null -> AnswerBlock(answer)
        }
    }
}

@Composable
private fun ThinkingBlock() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PulsingDot(color = MaterialTheme.colorScheme.primary, size = 8.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.thinking),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// Answers
// ---------------------------------------------------------------------------

/**
 * The answer, large.
 *
 * Not a sentence. Laya returns a distribution rather than a conclusion, and the temptation to
 * render "Yes, definitely" over a 54% is exactly the failure this app should not have.
 */
@Composable
private fun AnswerBlock(answer: DecisionResult) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(tween(260)) + slideInVertically(tween(300)) { it / 10 },
        ) {
            when (answer) {
                is DecisionResult.Noul -> VerdictAnswer(answer)
                is DecisionResult.Choice -> RankingAnswer(answer)
                is DecisionResult.Score -> ScoreAnswer(answer)
            }
        }
        ConfidenceRow(answer)
    }
}

@Composable
private fun VerdictAnswer(answer: DecisionResult.Noul) {
    val isTrue = answer.noul >= 0.5
    val verdict = stringResource(if (isTrue) R.string.result_true else R.string.result_false)
    val shown by animateFloatAsState(
        answer.noul.toFloat(),
        spring(dampingRatio = 0.8f, stiffness = 120f),
        label = "noul",
    )

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = verdict,
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1).sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = formatPercent(shown.toDouble()),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        // A bar under the verdict, so the number has a shape as well as a value.
        VerdictBar(answer.noul)
    }
}

@Composable
private fun RankingAnswer(answer: DecisionResult.Choice) {
    val ranked = answer.probabilities.entries.sortedByDescending { it.value }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ranked.forEachIndexed { index, (label, probability) ->
            val shown by animateFloatAsState(
                probability.toFloat(),
                spring(dampingRatio = 0.85f, stiffness = 140f),
                label = "rank",
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatPercent(probability),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (index == 0) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(shown.coerceIn(0f, 1f))
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                if (index == 0) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun ScoreAnswer(answer: DecisionResult.Score) {
    val levels = answer.legend.values.toList()
    if (levels.isEmpty()) return
    val top = (levels.size - 1).coerceAtLeast(1).toFloat()
    val position = (answer.score / top).toFloat().coerceIn(0f, 1f)
    val shown by animateFloatAsState(
        position,
        spring(dampingRatio = 0.8f, stiffness = 130f),
        label = "score",
    )
    val nearest = (answer.score.roundToInt()).coerceIn(0, levels.lastIndex)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = answer.score.roundToInt().toString(),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1).sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.result_of, top.roundToInt().toString()),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 7.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = levels.getOrElse(nearest) { "" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 7.dp),
            )
        }
        // A tick per level, filled to the score, so a hedged answer is visible as a gap.
        ScoreTrack(levels.size, shown)
    }
}

/**
 * One segment per level, filled up to the score.
 *
 * Segments rather than a single bar because a score of 3 out of 4 is not the same claim as
 * "75% of something" — the scale has meaning the bar would flatten away.
 */
@Composable
private fun ScoreTrack(levelCount: Int, position: Float) {
    val steps = levelCount.coerceAtLeast(1).toFloat()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        repeat(levelCount.coerceAtLeast(1)) { index ->
            // How far the score has travelled into this segment, 0..1.
            val fill = (position * steps - index).coerceIn(0f, 1f)
            Box(
                Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fill)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

/**
 * A single bar under a verdict.
 *
 * Always fills from the left, so it reads as "how much true" regardless of which way the
 * answer went — a bar that filled from the right would make a false answer look like a full
 * one at a glance.
 */
@Composable
private fun VerdictBar(proportion: Double) {
    val shown by animateFloatAsState(
        proportion.toFloat().coerceIn(0f, 1f),
        spring(dampingRatio = 0.85f, stiffness = 140f),
        label = "verdict-bar",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(shown)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

/**
 * The two readings.
 *
 * They are different quantities under similar names — the probability the answer is right, and
 * how far the distribution sits from a coin flip — so both are shown rather than picking one
 * and calling it confidence.
 */
@Composable
private fun ConfidenceRow(answer: DecisionResult) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Metric(
            value = formatPercent(answer.answerConfidence),
            label = stringResource(R.string.confidence_label),
            hint = stringResource(R.string.confidence_hint),
            modifier = Modifier.weight(1f),
        )
        Metric(
            value = formatPercent(answer.confidence),
            label = stringResource(R.string.certainty_label),
            hint = stringResource(R.string.certainty_hint),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Metric(value: String, label: String, hint: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 14.sp,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// The composer
// ---------------------------------------------------------------------------

/**
 * The input zone, pinned to the bottom and grown when a mode needs more.
 *
 * A segmented control rather than chips: the three modes are mutually exclusive peers, and a
 * segmented control says that where three separate chips say "pick any".
 */
@Composable
private fun Composer(
    mode: Mode,
    onModeChange: (Mode) -> Unit,
    question: String,
    onQuestionChange: (String) -> Unit,
    context: String,
    onContextChange: (String) -> Unit,
    choices: List<String>,
    onChoicesChange: (List<String>) -> Unit,
    levels: List<String>,
    onLevelsChange: (List<String>) -> Unit,
    working: Boolean,
    canAsk: Boolean,
    onAsk: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            // The keyboard inset is handled once, on the screen's root Box. Applying it here
            // as well would double the gap and lift the send button clear of the keyboard.
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        ModeSelector(mode, onModeChange, Modifier.padding(horizontal = 4.dp, vertical = 6.dp))

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

        // Always shown, and required. It was hidden behind a toggle on the strength of a
        // mismeasured claim that the question alone sufficed; measured properly, the question
        // alone is near chance. See docs/laya-measured.md.
        AnimatedVisibility(
            visible = true,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            CompactField(
                value = context,
                onValueChange = onContextChange,
                placeholder = stringResource(R.string.hint_optional_context),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                // Context is prose, not a label, so it wraps rather than scrolling sideways.
                singleLine = false,
                imeAction = ImeAction.Default,
            )
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .padding(start = 18.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)
                    // heightIn caps the field so a long question grows the composer to a
                    // limit rather than unbounded. Without it the input can push the mode
                    // selector and the thread off the top of a small screen.
                    .heightIn(max = 148.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        // Scrolls internally once the field hits the cap, so a multi-line
                        // question stays usable instead of shoving the layout around.
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (question.isEmpty()) {
                        Text(
                            text = stringResource(R.string.hint_question),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                    BasicTextField(
                        value = question,
                        onValueChange = onQuestionChange,
                        textStyle = LocalTextStyle.current.merge(
                            TextStyle(color = MaterialTheme.colorScheme.onSurface),
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(
                            onGo = {
                                if (canAsk) {
                                    onAsk()
                                    keyboard?.hide()
                                }
                            },
                        ),
                        // Reclaimed after the keyboard hides, so the next question can be typed
                        // straight away. Without this, focus is dropped and the first tap only
                        // reopens the keyboard.
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .padding(vertical = 12.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                SendButton(
                    working = working,
                    enabled = canAsk && !working,
                    onClick = {
                        onAsk()
                        keyboard?.hide()
                    },
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ModeSelector(mode: Mode, onChange: (Mode) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(3.dp)) {
            Mode.entries.forEach { candidate ->
                val selected = candidate == mode
                val background by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
                    label = "segment-bg",
                )
                val content by animateColorAsState(
                    if (selected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    label = "segment-fg",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(17.dp))
                        .background(background)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onChange(candidate) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(candidate.labelRes),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = content,
                    )
                }
            }
        }
    }
}

@Composable
private fun SendButton(
    working: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container by animateColorAsState(
        when {
            working -> MaterialTheme.colorScheme.errorContainer
            enabled -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        label = "send-bg",
    )
    val tint by animateColorAsState(
        when {
            working -> MaterialTheme.colorScheme.onErrorContainer
            enabled -> MaterialTheme.colorScheme.onPrimary
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        },
        label = "send-fg",
    )

    Surface(
        onClick = onClick,
        enabled = enabled && !working,
        color = container,
        shape = CircleShape,
        modifier = modifier.size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (working) {
                Icon(
                    Icons.Filled.Stop,
                    contentDescription = stringResource(R.string.action_clear),
                    tint = tint,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Icon(
                    Icons.Filled.ArrowUpward,
                    contentDescription = stringResource(R.string.action_ask),
                    tint = tint,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun CompactField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    imeAction: ImeAction = ImeAction.Next,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = LocalTextStyle.current.merge(
                TextStyle(color = MaterialTheme.colorScheme.onSurface),
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            // Single line by default. With maxLines > 1 the enter key inserts a newline, which
            // is wrong for a one-word choice and traps the user in the field.
            maxLines = if (singleLine) 1 else 3,
            singleLine = singleLine,
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
            keyboardActions = keyboardActions,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 11.dp),
            decorationBox = { inner ->
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
                inner()
            },
        )
    }
}

@Composable
private fun ChoiceFields(choices: List<String>, onChange: (List<String>) -> Unit) {
    Column(
        modifier = Modifier
            .padding(horizontal = 4.dp, vertical = 4.dp)
            // Capped and scrollable: an unbounded list of choice fields would grow the composer
            // until the thread and the mode selector were pushed off the screen entirely.
            .heightIn(max = 168.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        choices.forEachIndexed { index, value ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The placeholder is passed into CompactField rather than drawn as a sibling
                // Text. Drawn separately it sits behind the field and the two overlap.
                CompactField(
                    value = value,
                    onValueChange = { updated ->
                        onChange(choices.toMutableList().also { it[index] = updated })
                    },
                    placeholder = stringResource(R.string.label_choice, index + 1),
                    modifier = Modifier.weight(1f),
                )
                if (choices.size > 2) {
                    IconButton(
                        onClick = { onChange(choices.filterIndexed { i, _ -> i != index }) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.action_remove_choice, index + 1),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
        AddRowButton(R.string.action_add_choice) { onChange(choices + "") }
    }
}

@Composable
private fun LevelFields(levels: List<String>, onChange: (List<String>) -> Unit) {
    Column(
        modifier = Modifier
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .heightIn(max = 148.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            levels.forEachIndexed { index, value ->
                CompactField(
                    value = value,
                    onValueChange = { updated ->
                        onChange(levels.toMutableList().also { it[index] = updated })
                    },
                    placeholder = "${index + 1}",
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.label_scale_order),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(start = 6.dp),
            )
            IconButton(onClick = { onChange(levels + "") }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.action_add_level),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun AddRowButton(labelRes: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Percentages are whole numbers: "97.56%" would imply precision the model does not have. */
private fun formatPercent(probability: Double): String =
    "${(probability.coerceIn(0.0, 1.0) * 100).roundToInt()}%"