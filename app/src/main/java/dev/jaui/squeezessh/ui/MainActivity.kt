package dev.jaui.squeezessh.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import dev.jaui.squeezessh.BuildConfig
import dev.jaui.squeezessh.ui.theme.SqueezeSshTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SqueezeSshTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { insets ->
                    PlaceholderScreen(Modifier.padding(insets))
                }
            }
        }
    }
}

/** Platzhalter bis M5 (Address Book) steht. */
@Composable
private fun PlaceholderScreen(modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(24.dp)) {
        Text("SqueezeSSH", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Version ${BuildConfig.VERSION_NAME} — Gerüst steht.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
