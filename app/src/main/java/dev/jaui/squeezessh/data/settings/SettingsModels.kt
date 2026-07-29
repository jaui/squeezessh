package dev.jaui.squeezessh.data.settings

/**
 * Einstellungen, nach den Schluesseln des Originals benannt.
 *
 * JuiceSSH legte sie als `configfield`/`configvalue` in der Datenbank ab — nur damit
 * CloudSync sie mitnehmen konnte. Ohne Sync gehoeren sie in DataStore; die Namen bleiben
 * aber erhalten, damit ein spaeterer Import sie zuordnen kann (siehe
 * docs/juicessh-datenformat.md).
 */
enum class ThemeMode(val storageValue: String) {
    /** Dem System folgen — im Original der Standardwert `-1`. */
    SYSTEM("-1"),
    LIGHT("0"),
    DARK("1"),
    ;

    companion object {
        fun fromStorage(value: String?): ThemeMode =
            entries.firstOrNull { it.storageValue == value } ?: SYSTEM
    }
}

/** Was bei einem Terminal-Piepser passiert. */
enum class BellMode {
    /** Nichts — der Normalfall auf einem Telefon. */
    NONE,
    VIBRATE,
    SOUND,
    ;

    companion object {
        fun fromStorage(value: String?): BellMode =
            entries.firstOrNull { it.name == value } ?: NONE
    }
}

/** Was ein waagerechter Wisch im Terminal tut. */
enum class HorizontalSwipeMode {
    /** Nichts — Wischen bleibt dem Scrollen vorbehalten. */
    NONE,

    /** Zwischen den offenen Sitzungen wechseln. */
    SWITCH_SESSION,

    /** Pfeiltasten senden, etwa um in einer Zeile zu navigieren. */
    ARROW_KEYS,
    ;

    companion object {
        fun fromStorage(value: String?): HorizontalSwipeMode =
            entries.firstOrNull { it.name == value } ?: NONE
    }
}

/** Wo die Zusatztastenleiste sitzt. */
enum class KeyboardPosition {
    BOTTOM,
    TOP,
    ;

    companion object {
        fun fromStorage(value: String?): KeyboardPosition =
            entries.firstOrNull { it.name == value } ?: BOTTOM
    }
}

/**
 * Wie lange die App unbenutzt bleiben darf, bevor sie sich verriegelt.
 *
 * `IMMEDIATELY` ist die sicherste, aber im Alltag laestigste Wahl: schon ein Blick auf
 * eine Benachrichtigung verlangt danach wieder die Anmeldung.
 */
enum class AutoLockDelay(val seconds: Int, val label: String) {
    IMMEDIATELY(0, "sofort"),
    AFTER_30_SECONDS(30, "nach 30 Sekunden"),
    AFTER_2_MINUTES(120, "nach 2 Minuten"),
    AFTER_15_MINUTES(900, "nach 15 Minuten"),
    NEVER(Int.MAX_VALUE, "nie"),
    ;

    companion object {
        fun fromSeconds(seconds: Int?): AutoLockDelay =
            entries.firstOrNull { it.seconds == seconds } ?: AFTER_30_SECONDS
    }
}

data class TerminalSettings(
    val themeId: String,
    val fontSizePx: Int,
    val marginDp: Int,
    val terminalType: String,
    val utf8: Boolean,
    val bellMode: BellMode,
    val keyboardPosition: KeyboardPosition,
    val horizontalSwipeMode: HorizontalSwipeMode,
)
