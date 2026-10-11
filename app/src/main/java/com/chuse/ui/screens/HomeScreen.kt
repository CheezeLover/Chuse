package com.chuse.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chuse.R
import com.chuse.data.ModelStatus

/** The three ways to ask. Nothing else lives here — there is no history and no settings. */
enum class Mode(val icon: ImageVector, val titleRes: Int, val bodyRes: Int) {
    YesNo(Icons.Filled.ToggleOn, R.string.home_yesno_title, R.string.home_yesno_body),
    Choice(Icons.Filled.Rule, R.string.home_choice_title, R.string.home_choice_body),
    Score(Icons.Filled.Balance, R.string.home_score_title, R.string.home_score_body),
}

/**
 * The home screen: pick a mode, then leave. Three large targets rather than a menu, because
 * an app this small should be operable without reading anything.
 */
@Composable
fun HomeScreen(
    modelStatus: ModelStatus,
    onSelect: (Mode) -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(32.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.home_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))

            ModelStatusCard(modelStatus)

            Spacer(Modifier.height(24.dp))
            Mode.entries.forEach { mode ->
                ModeCard(mode = mode, onClick = { onSelect(mode) })
                Spacer(Modifier.height(12.dp))
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ModeCard(mode: Mode, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SurfaceBadge(mode.icon)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(mode.titleRes),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(mode.bodyRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SurfaceBadge(icon: ImageVector) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .padding(12.dp)
                .size(24.dp),
        )
    }
}

/**
 * Says plainly where answers are coming from.
 *
 * A build with no model still works, but it must never look like the model answered — so the
 * stand-in state is stated on the home screen, not buried in a log.
 */
@Composable
private fun ModelStatusCard(status: ModelStatus) {
    val (title, body, accent) = when (status) {
        ModelStatus.Loading -> Triple(
            stringResource(R.string.model_loading),
            null,
            MaterialTheme.colorScheme.secondary,
        )

        is ModelStatus.Ready -> if (status.isStandIn) {
            Triple(
                stringResource(R.string.model_standin_banner),
                stringResource(R.string.model_missing_body),
                MaterialTheme.colorScheme.tertiary,
            )
        } else {
            Triple(stringResource(R.string.model_ready), null, MaterialTheme.colorScheme.primary)
        }

        is ModelStatus.Failed -> Triple(
            stringResource(R.string.model_failed, status.reason),
            stringResource(R.string.model_missing_body),
            MaterialTheme.colorScheme.error,
        )
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = accent.copy(alpha = 0.12f),
        ),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (status is ModelStatus.Loading) {
                    LinearProgressIndicator(
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (body != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}