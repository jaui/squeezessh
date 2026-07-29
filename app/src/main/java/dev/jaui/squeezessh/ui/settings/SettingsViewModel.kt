package dev.jaui.squeezessh.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jaui.squeezessh.data.settings.AppSettings
import dev.jaui.squeezessh.data.settings.AutoLockDelay
import dev.jaui.squeezessh.data.settings.BellMode
import dev.jaui.squeezessh.data.settings.HorizontalSwipeMode
import dev.jaui.squeezessh.data.settings.KeyboardPosition
import dev.jaui.squeezessh.data.settings.TerminalSettings
import dev.jaui.squeezessh.data.settings.ThemeMode
import dev.jaui.squeezessh.data.repository.KnownHostRepository
import dev.jaui.squeezessh.security.AppLock
import dev.jaui.squeezessh.security.AppLockException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: AppSettings,
    private val appLock: AppLock,
    private val knownHosts: KnownHostRepository,
) : ViewModel() {

    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<SettingsUiState> = combine(
        settings.themeMode,
        settings.terminal,
        settings.appLockEnabled,
        settings.autoLockDelay,
        combine(
            settings.biometricPreferred,
            settings.sshAgentEnabled,
            settings.moshServerCommand,
            message,
        ) { biometric, agent, mosh, msg -> Extras(biometric, agent, mosh, msg) },
    ) { theme, terminal, lockEnabled, lockDelay, extras ->
        SettingsUiState(
            themeMode = theme,
            terminal = terminal,
            appLockEnabled = lockEnabled,
            autoLockDelay = lockDelay,
            biometricPreferred = extras.biometric,
            sshAgentEnabled = extras.agent,
            moshServerCommand = extras.mosh,
            message = extras.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setThemeMode(mode: ThemeMode) = edit { settings.setThemeMode(mode) }

    fun setTerminalTheme(id: String) = edit { settings.setTerminalTheme(id) }

    fun setTerminalFontSize(sizePx: Int) = edit { settings.setTerminalFontSize(sizePx) }

    fun setTerminalMargin(dp: Int) = edit { settings.setTerminalMargin(dp) }

    fun setTerminalType(value: String) = edit { settings.setTerminalType(value) }

    fun setTerminalUtf8(enabled: Boolean) = edit { settings.setTerminalUtf8(enabled) }

    fun setBellMode(mode: BellMode) = edit { settings.setBellMode(mode) }

    fun setKeyboardPosition(position: KeyboardPosition) =
        edit { settings.setKeyboardPosition(position) }

    fun setHorizontalSwipeMode(mode: HorizontalSwipeMode) =
        edit { settings.setHorizontalSwipeMode(mode) }

    fun setSshAgentEnabled(enabled: Boolean) = edit { settings.setSshAgentEnabled(enabled) }

    fun setMoshServerCommand(value: String) = edit { settings.setMoshServerCommand(value) }

    fun setAutoLockDelay(delay: AutoLockDelay) = edit { settings.setAutoLockDelay(delay) }

    fun setBiometricPreferred(enabled: Boolean) = edit { settings.setBiometricPreferred(enabled) }

    fun setPin(pin: String) {
        viewModelScope.launch {
            try {
                appLock.setPin(pin)
                message.value = "App-Sperre ist aktiv."
            } catch (e: AppLockException) {
                message.value = e.message
            }
        }
    }

    fun disableAppLock() = edit {
        appLock.disable()
        message.value = "App-Sperre ist aus."
    }

    /** Entspricht `action:clear_known_hosts` aus JuiceSSHs Einstellungen. */
    fun clearKnownHosts() = edit {
        knownHosts.forgetAll()
        message.value = "Alle bekannten Host-Keys wurden entfernt. Beim nächsten Verbinden " +
            "wird erneut nachgefragt."
    }

    fun dismissMessage() { message.value = null }

    private fun edit(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private data class Extras(
        val biometric: Boolean,
        val agent: Boolean,
        val mosh: String,
        val message: String?,
    )
}

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val terminal: TerminalSettings = TerminalSettings(
        themeId = AppSettings.DEFAULT_TERMINAL_THEME,
        fontSizePx = 32,
        marginDp = 0,
        terminalType = AppSettings.DEFAULT_TERMINAL_TYPE,
        utf8 = true,
        bellMode = BellMode.NONE,
        keyboardPosition = KeyboardPosition.BOTTOM,
        horizontalSwipeMode = HorizontalSwipeMode.NONE,
    ),
    val appLockEnabled: Boolean = false,
    val autoLockDelay: AutoLockDelay = AutoLockDelay.AFTER_30_SECONDS,
    val biometricPreferred: Boolean = true,
    val sshAgentEnabled: Boolean = false,
    val moshServerCommand: String = AppSettings.DEFAULT_MOSH_SERVER,
    val message: String? = null,
)
