package com.chuse.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chuse.data.LayaRepository
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

    Box(modifier = Modifier.fillMaxSize()) {
        ChatScreen(viewModel)
    }
}