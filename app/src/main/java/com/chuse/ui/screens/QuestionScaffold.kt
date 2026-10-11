package com.chuse.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chuse.R
import com.chuse.ui.AskState

/** The frame every mode shares: a title bar, a scrolling body, and an error surface. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestionScaffold(
    title: String,
    state: AskState,
    onBack: () -> Unit,
    onReset: () -> Unit,
    snackbarHostState: SnackbarHostState,
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    if (state is AskState.Answered) {
                        IconButton(onClick = onReset) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.action_ask_again),
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            content()
        }
    }
}

/** The prominent call to action, which shows a spinner and refuses re-entry while working. */
@Composable
fun AskButton(
    state: AskState,
    onAsk: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onAsk,
        enabled = state !is AskState.Working,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        if (state is AskState.Working) {
            CircularProgressIndicator(
                modifier = Modifier.width(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text(stringResource(R.string.action_ask))
        }
    }
}

/** "Ask again", shown under a result to return to the form with nothing carried over. */
@Composable
fun AskAgainButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
    ) {
        Icon(Icons.Filled.Refresh, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.action_ask_again))
    }
}

/** The card that holds a single question's result. */
@Composable
fun ResultCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(modifier = Modifier.padding(20.dp)) { content() }
    }
}

/** Reveals the result once it exists, so it grows in rather than appearing mid-tap. */
@Composable
fun ResultReveal(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        content()
    }
}

/** A labelled text field with consistent spacing. */
@Composable
fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    isError: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = singleLine,
        minLines = minLines,
        isError = isError,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    )
}

/** A dismiss button that appears on each editable row. */
@Composable
fun RemoveRowButton(
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            imageVector = Icons.Filled.RemoveCircleOutline,
            contentDescription = description,
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        )
    }
}

/** "Add another" affordance, styled as a quiet dashed-looking button. */
@Composable
fun AddRowButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Icon(Icons.Filled.Add, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

/** Reports a failure to the user as a snackbar rather than inline text that reflows the form. */
@Composable
fun FailureSnackbar(state: AskState, snackbarHostState: SnackbarHostState) {
    val failed = state as? AskState.Failed
    // Resolved outside the effect so the message goes through resources rather than by hand,
    // which is what keeps it translatable.
    val text = if (failed != null) stringResource(R.string.error_ask_failed, failed.message) else ""
    LaunchedEffect(state) {
        if (failed != null) {
            // Suspends until the snackbar times out, so the next failure gets its own message.
            snackbarHostState.showSnackbar(text)
        }
    }
}

/** A field-level error, shown under the input that caused it. */
@Composable
fun ErrorText(message: String) {
    Spacer(Modifier.height(6.dp))
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

/** Vertical breathing room between form sections. */
@Composable
fun Gap(height: Int = 16) {
    Spacer(Modifier.height(height.dp))
}

/** Centres a row's children. */
@Composable
fun RowAlignment(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        content()
    }
}

/** Three evenly spaced metrics, used for the model's own readings. */
@Composable
fun StatRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}