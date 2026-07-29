package dev.jaui.squeezessh.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.jaui.squeezessh.terminal.TerminalView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Alle Einstellungen an einer Stelle.
 *
 * Die Schluesselnamen sind die von JuiceSSH uebernommen (`terminal:font_size` und so
 * weiter). Das ist kein Selbstzweck: ein spaeterer Import kann die Werte dann direkt
 * zuordnen, ohne eine zweite Zuordnungstabelle zu pflegen.
 *
 * Der DataStore kommt von aussen und nicht ueber `Context.preferencesDataStore`: dieses
 * Delegat haelt eine verborgene, prozessweite Instanz, die sich zwischen Tests nicht
 * zuruecksetzen laesst — Zustand aus einem Test schwappte damit in den naechsten.
 */
@Singleton
class AppSettings @Inject constructor(
    private val store: DataStore<Preferences>,
) {

    // --- Darstellung ------------------------------------------------------------------

    val themeMode: Flow<ThemeMode> = store.data.map {
        ThemeMode.fromStorage(it[Keys.THEME_DARK])
    }

    suspend fun setThemeMode(mode: ThemeMode) = store.edit {
        it[Keys.THEME_DARK] = mode.storageValue
    }

    // --- Terminal ---------------------------------------------------------------------

    val terminal: Flow<TerminalSettings> = store.data.map { prefs ->
        TerminalSettings(
            themeId = prefs[Keys.TERMINAL_THEME] ?: DEFAULT_TERMINAL_THEME,
            fontSizePx = prefs[Keys.TERMINAL_FONT_SIZE] ?: TerminalView.DEFAULT_TEXT_SIZE_PX,
            marginDp = prefs[Keys.TERMINAL_MARGIN] ?: 0,
            terminalType = prefs[Keys.TERMINAL_TYPE] ?: DEFAULT_TERMINAL_TYPE,
            utf8 = prefs[Keys.TERMINAL_UTF8] ?: true,
            bellMode = BellMode.fromStorage(prefs[Keys.TERMINAL_BELL_MODE]),
            keyboardPosition = KeyboardPosition.fromStorage(prefs[Keys.TERMINAL_KEYBOARD_POSITION]),
            horizontalSwipeMode = HorizontalSwipeMode.fromStorage(prefs[Keys.TERMINAL_SWIPE_MODE]),
        )
    }

    suspend fun setTerminalTheme(id: String) = store.edit { it[Keys.TERMINAL_THEME] = id }

    suspend fun setTerminalFontSize(sizePx: Int) = store.edit {
        // Dieselben Grenzen wie beim Zoomen mit zwei Fingern — sonst liesse sich hier
        // eine Groesse einstellen, die die Geste sofort wieder zurechtbiegt.
        it[Keys.TERMINAL_FONT_SIZE] =
            sizePx.coerceIn(TerminalView.MIN_TEXT_SIZE_PX, TerminalView.MAX_TEXT_SIZE_PX)
    }

    suspend fun setTerminalMargin(dp: Int) = store.edit {
        it[Keys.TERMINAL_MARGIN] = dp.coerceIn(0, MAX_MARGIN_DP)
    }

    suspend fun setTerminalType(value: String) = store.edit {
        it[Keys.TERMINAL_TYPE] = value.ifBlank { DEFAULT_TERMINAL_TYPE }
    }

    suspend fun setTerminalUtf8(enabled: Boolean) = store.edit { it[Keys.TERMINAL_UTF8] = enabled }

    suspend fun setBellMode(mode: BellMode) = store.edit { it[Keys.TERMINAL_BELL_MODE] = mode.name }

    suspend fun setKeyboardPosition(position: KeyboardPosition) = store.edit {
        it[Keys.TERMINAL_KEYBOARD_POSITION] = position.name
    }

    suspend fun setHorizontalSwipeMode(mode: HorizontalSwipeMode) = store.edit {
        it[Keys.TERMINAL_SWIPE_MODE] = mode.name
    }

    // --- Sicherheit -------------------------------------------------------------------

    val appLockEnabled: Flow<Boolean> = store.data.map { it[Keys.PINLOCK_ENABLED] ?: false }

    val autoLockDelay: Flow<AutoLockDelay> = store.data.map {
        AutoLockDelay.fromSeconds(it[Keys.PINLOCK_DELAY])
    }

    val biometricPreferred: Flow<Boolean> = store.data.map {
        it[Keys.PINLOCK_BIOMETRIC] ?: true
    }

    /** Der versiegelte PIN-Nachweis. Nie der PIN selbst — siehe [dev.jaui.squeezessh.security.AppLock]. */
    val pinProof: Flow<String?> = store.data.map { it[Keys.PINLOCK_PROOF] }

    suspend fun setAppLock(enabled: Boolean, proof: String?) = store.edit { prefs ->
        prefs[Keys.PINLOCK_ENABLED] = enabled
        if (proof == null) prefs.remove(Keys.PINLOCK_PROOF) else prefs[Keys.PINLOCK_PROOF] = proof
    }

    suspend fun setAutoLockDelay(delay: AutoLockDelay) = store.edit {
        it[Keys.PINLOCK_DELAY] = delay.seconds
    }

    suspend fun setBiometricPreferred(enabled: Boolean) = store.edit {
        it[Keys.PINLOCK_BIOMETRIC] = enabled
    }

    val sshAgentEnabled: Flow<Boolean> = store.data.map { it[Keys.SSH_AGENT] ?: false }

    suspend fun setSshAgentEnabled(enabled: Boolean) = store.edit { it[Keys.SSH_AGENT] = enabled }

    // --- Mosh -------------------------------------------------------------------------

    val moshServerCommand: Flow<String> = store.data.map {
        it[Keys.MOSH_SERVER] ?: DEFAULT_MOSH_SERVER
    }

    suspend fun setMoshServerCommand(value: String) = store.edit {
        it[Keys.MOSH_SERVER] = value.ifBlank { DEFAULT_MOSH_SERVER }
    }

    private object Keys {
        val THEME_DARK = stringPreferencesKey("theme:dark")
        val TERMINAL_THEME = stringPreferencesKey("terminal:theme")
        val TERMINAL_FONT_SIZE = intPreferencesKey("terminal:font_size")
        val TERMINAL_MARGIN = intPreferencesKey("terminal:margin")
        val TERMINAL_TYPE = stringPreferencesKey("terminal:type")
        val TERMINAL_UTF8 = booleanPreferencesKey("terminal:utf8")
        val TERMINAL_BELL_MODE = stringPreferencesKey("terminal:bell_mode")
        val TERMINAL_KEYBOARD_POSITION = stringPreferencesKey("terminal:keyboard_position")
        val TERMINAL_SWIPE_MODE = stringPreferencesKey("terminal:horizontal_swipe_mode")
        val PINLOCK_ENABLED = booleanPreferencesKey("pinlock:enabled")
        val PINLOCK_PROOF = stringPreferencesKey("pinlock:proof")
        val PINLOCK_DELAY = intPreferencesKey("pinlock:delay")
        val PINLOCK_BIOMETRIC = booleanPreferencesKey("pinlock:biometric")
        val SSH_AGENT = booleanPreferencesKey("ssh:agent")
        val MOSH_SERVER = stringPreferencesKey("mosh:mosh-server")
    }

    companion object {
        const val DEFAULT_TERMINAL_THEME = "dark"

        /**
         * `xterm-256color` statt JuiceSSHs `linux`: Termux' Emulator kann 256 Farben, und
         * fast jeder Server bringt das Terminfo dafuer mit.
         */
        const val DEFAULT_TERMINAL_TYPE = "xterm-256color"
        const val DEFAULT_MOSH_SERVER = "mosh-server new -s -l LANG=en_US.UTF-8"
        const val MAX_MARGIN_DP = 48

        val TERMINAL_TYPES = listOf("xterm-256color", "xterm", "linux", "screen", "vt100")
    }
}
