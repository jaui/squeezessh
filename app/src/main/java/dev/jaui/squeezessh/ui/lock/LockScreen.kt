package dev.jaui.squeezessh.ui.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jaui.squeezessh.data.settings.AppSettings
import dev.jaui.squeezessh.security.AppLock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Der Sperrbildschirm.
 *
 * Biometrie wird angeboten, wenn das Geraet sie hat und die Einstellung es vorsieht — der
 * PIN bleibt aber immer erreichbar. Ein Fingerabdrucksensor, der einen nicht erkennt, darf
 * nicht dazu fuehren, dass man nicht mehr an seine Server kommt.
 */
@Composable
fun LockScreen(
    modifier: Modifier = Modifier,
    viewModel: LockViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var biometricOffered by remember { mutableStateOf(false) }

    val activity = context as? FragmentActivity
    val canUseBiometrics = remember {
        activity != null && BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    fun promptBiometrics() {
        val host = activity ?: return
        val prompt = BiometricPrompt(
            host,
            androidx.core.content.ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    viewModel.unlock()
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    // Abbrechen ist kein Fehler — der PIN steht weiterhin bereit.
                    if (code != BiometricPrompt.ERROR_USER_CANCELED &&
                        code != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) {
                        error = message.toString()
                    }
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("SqueezeSSH entsperren")
                .setSubtitle("Oder den PIN eingeben")
                .setNegativeButtonText("PIN verwenden")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .build(),
        )
    }

    LaunchedEffect(canUseBiometrics) {
        if (!biometricOffered && canUseBiometrics && viewModel.biometricPreferred()) {
            biometricOffered = true
            promptBiometrics()
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("SqueezeSSH ist gesperrt", style = MaterialTheme.typography.headlineSmall)
        Text(
            "PIN eingeben, um fortzufahren.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )

        OutlinedTextField(
            value = pin,
            onValueChange = {
                pin = it.filter(Char::isDigit)
                error = null
            },
            label = { Text("PIN") },
            singleLine = true,
            isError = error != null,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Button(
            enabled = pin.length >= AppLock.MIN_PIN_LENGTH,
            onClick = {
                scope.launch {
                    if (viewModel.verify(pin)) {
                        viewModel.unlock()
                    } else {
                        error = "Falscher PIN."
                        pin = ""
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) { Text("Entsperren") }

        if (canUseBiometrics) {
            TextButton(onClick = ::promptBiometrics) { Text("Biometrie verwenden") }
        }
    }
}

@HiltViewModel
class LockViewModel @Inject constructor(
    private val appLock: AppLock,
    private val settings: AppSettings,
) : ViewModel() {

    suspend fun verify(pin: String): Boolean = appLock.verifyPin(pin)

    suspend fun biometricPreferred(): Boolean = settings.biometricPreferred.first()

    fun unlock() = appLock.unlock()
}
