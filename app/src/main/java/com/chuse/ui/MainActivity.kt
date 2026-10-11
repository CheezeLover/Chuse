package com.chuse.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chuse.data.LayaRepository
import com.chuse.ui.screens.ChoiceScreen
import com.chuse.ui.screens.HomeScreen
import com.chuse.ui.screens.Mode
import com.chuse.ui.screens.ScoreScreen
import com.chuse.ui.screens.YesNoScreen
import com.chuse.ui.theme.ChuseTheme

class MainActivity : ComponentActivity() {

    // Held by the activity rather than the view model so the model survives configuration
    // changes but not the process — reloading a model per rotation would be absurd.
    private val repository by lazy { LayaRepository.create(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ChuseTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ChuseApp(repository)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) repository.close()
    }
}

@Composable
private fun ChuseApp(repository: LayaRepository) {
    val viewModel: LayaViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                LayaViewModel(repository) as T
        },
    )

    val modelStatus by viewModel.modelStatus.collectAsState()
    val askState by viewModel.askState.collectAsState()

    // Survives rotation so a half-filled form is not lost, and so backing out of a mode
    // returns to the picker rather than to a stale result.
    var mode by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Leaving a mode clears the result: the app keeps no history, so a new question must
    // never be able to show the previous one's answer.
    LaunchedEffect(mode) {
        viewModel.reset()
    }

    val current = mode?.let { name -> Mode.entries.firstOrNull { it.name == name } }

    Box(modifier = Modifier.fillMaxSize()) {
        when (current) {
            null -> HomeScreen(modelStatus = modelStatus, onSelect = { mode = it.name })

            Mode.YesNo -> YesNoScreen(
                state = askState,
                snackbarHostState = snackbarHostState,
                onAsk = viewModel::askYesNo,
                onBack = { mode = null },
                onReset = viewModel::reset,
            )

            Mode.Choice -> ChoiceScreen(
                state = askState,
                snackbarHostState = snackbarHostState,
                onAsk = viewModel::askChoice,
                onBack = { mode = null },
                onReset = viewModel::reset,
            )

            Mode.Score -> ScoreScreen(
                state = askState,
                snackbarHostState = snackbarHostState,
                onAsk = viewModel::askScore,
                onBack = { mode = null },
                onReset = viewModel::reset,
            )
        }
    }
}