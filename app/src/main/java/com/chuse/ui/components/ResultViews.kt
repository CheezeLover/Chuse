package com.chuse.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chuse.ui.theme.TabularNumberStyle
import kotlin.math.roundToInt

/**
 * A ring that fills to [progress], with [label] in the middle.
 *
 * Drawn rather than assembled from Material's progress indicators because the arc needs a
 * rounded cap and a gap at the bottom, which no stock indicator gives. The sweep is animated
 * because the number it represents arrives a beat after the tap and a static ring makes that
 * read as a stall.
 */
@Composable
fun ProbabilityRing(
    progress: Float,
    label: String,
    caption: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentDescription: String? = null,
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 750),
        label = "ring",
    )

    Box(
        modifier = modifier
            .size(168.dp)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(168.dp)) {
            val stroke = 14.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            val gapDegrees = 42f
            val sweepDegrees = 360f - gapDegrees

            drawArc(
                color = trackColor,
                startAngle = 180f + gapDegrees / 2f,
                sweepAngle = sweepDegrees,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = color,
                startAngle = 180f + gapDegrees / 2f,
                sweepAngle = sweepDegrees * animated,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = caption,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One ranked row: position, label, probability bar, and the number.
 *
 * The bar animates in because a ranking that reorders with a jump cut is hard to follow —
 * the motion is what shows which rows moved past which.
 */
@Composable
fun RankedRow(
    rank: Int,
    label: String,
    detail: String,
    probability: Double,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    // The engine speaks Double throughout; the animation is the one place a Float is needed.
    val animated by animateFloatAsState(
        targetValue = probability.toFloat().coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 700),
        label = "rank-bar",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = rank.toString(),
            style = MaterialTheme.typography.titleMedium.merge(TabularNumberStyle),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = formatPercent(probability),
                    style = MaterialTheme.typography.titleMedium.merge(TabularNumberStyle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animated)
                        .height(8.dp)
                        .background(accent, RoundedCornerShape(4.dp)),
                )
            }
        }
    }
}

/**
 * A scale with a marker at [position] and the whole distribution beneath it.
 *
 * The distribution matters as much as the score: a score of 3 out of 4 means something very
 * different when the model put nearly everything on one level than when it hedged evenly.
 */
@Composable
fun ScoreScale(
    position: Float,
    levels: List<String>,
    probabilities: List<Double>,
    scoreLabel: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    val animatedPosition by animateFloatAsState(
        targetValue = position.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 750),
        label = "score-marker",
    )
    val top = probabilities.maxOrNull() ?: 0.0

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = scoreLabel,
            style = MaterialTheme.typography.displayMedium.merge(TabularNumberStyle),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))

        // The track, with a marker at the expected level.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedPosition)
                    .height(36.dp)
                    .background(accent.copy(alpha = 0.35f), RoundedCornerShape(18.dp)),
            )
            Box(
                modifier = Modifier
                    .padding(start = 0.dp)
                    .offsetFraction(animatedPosition)
                    .size(20.dp)
                    .background(accent, RoundedCornerShape(10.dp)),
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = levels.firstOrNull().orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = levels.lastOrNull().orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))
        // How the score was reached, level by level.
        levels.forEachIndexed { index, level ->
            val share = ((probabilities.getOrNull(index) ?: 0.0) / top).toFloat()
            val animatedShare by animateFloatAsState(
                targetValue = share.coerceIn(0f, 1f),
                animationSpec = tween(durationMillis = 700),
                label = "scale-share",
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = level,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(96.dp),
                    maxLines = 1,
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(10.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(5.dp)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(animatedShare)
                            .height(10.dp)
                            .background(accent, RoundedCornerShape(5.dp)),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = formatPercent(probabilities.getOrNull(index) ?: 0.0),
                    style = MaterialTheme.typography.bodySmall.merge(TabularNumberStyle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(44.dp),
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

/**
 * The two derived figures side by side.
 *
 * They are different numbers and are labelled as such: `answer_confidence` is the probability
 * the chosen answer is right, while `confidence` is one minus normalised entropy and answers
 * a different question — how far the distribution is from a coin flip. Showing only the first
 * would hide the model's actual decisiveness.
 */
@Composable
fun ConfidenceReadout(
    answerConfidence: Double,
    certainty: Double,
    certaintyLabel: String,
    certaintyHint: String,
    confidenceLabel: String,
    confidenceHint: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        Metric(
            value = formatPercent(answerConfidence),
            label = confidenceLabel,
            hint = confidenceHint,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Metric(
            value = formatPercent(certainty),
            label = certaintyLabel,
            hint = certaintyHint,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Metric(
    value: String,
    label: String,
    hint: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(16.dp),
            )
            .padding(14.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall.merge(TabularNumberStyle),
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

/** Percentages are always whole numbers here; showing 97.56% implies precision that is not there. */
fun formatPercent(probability: Double): String =
    "${(probability.coerceIn(0.0, 1.0) * 100).roundToInt()}%"

/** Places a child at a fraction of its parent's width. */
private fun Modifier.offsetFraction(fraction: Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val x = ((constraints.maxWidth - placeable.width) * fraction).roundToInt()
    layout(placeable.width, placeable.height) { placeable.placeRelative(x, 0) }
}