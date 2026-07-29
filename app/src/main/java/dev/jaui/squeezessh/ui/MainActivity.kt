package dev.jaui.squeezessh.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import dev.jaui.squeezessh.ui.importer.ImportScreen
import dev.jaui.squeezessh.ui.theme.SqueezeSshTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SqueezeSshTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { insets ->
                    // Bis das Address Book steht (M5), ist der Import der einzige Screen.
                    ImportScreen(Modifier.padding(insets))
                }
            }
        }
    }
}
