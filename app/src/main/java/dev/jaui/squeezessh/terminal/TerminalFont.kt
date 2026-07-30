package dev.jaui.squeezessh.terminal

import android.content.Context
import android.graphics.Typeface

/**
 * Auswaehlbare Terminal-Schriften.
 *
 * JuiceSSH lieferte ein halbes Dutzend Nerd Fonts mit, rund 18 MB. Die sind hier bewusst
 * **nicht** aus der APK uebernommen: mitgeliefert ist eine Schrift direkt von ihrem
 * Ursprung (JetBrains Mono, SIL OFL 1.1, siehe `assets/fonts/JetBrainsMono-OFL.txt`), der
 * Rest kommt vom System. Weitere lassen sich nach demselben Muster ergaenzen — eine
 * Konstante hier und die Datei in `assets/fonts`.
 */
enum class TerminalFont(val id: String, val label: String, private val assetPath: String?) {

    /** Die Monospace-Schrift des Geraets. Kostet nichts an APK-Groesse. */
    SYSTEM("system", "System", null),

    JETBRAINS_MONO("jetbrains_mono", "JetBrains Mono", "fonts/JetBrainsMono-Regular.ttf"),

    /** Serifen-Monospace des Systems; auf manchen Geraeten faellt sie auf Monospace zurueck. */
    SERIF_MONO("serif_mono", "Serif Mono", null),
    ;

    fun typeface(context: Context): Typeface = when (this) {
        SYSTEM -> Typeface.MONOSPACE
        SERIF_MONO -> Typeface.create("serif-monospace", Typeface.NORMAL)
        JETBRAINS_MONO -> assetPath
            // Faellt die Datei aus, ist Monospace die bessere Antwort als ein Absturz.
            ?.let { runCatching { Typeface.createFromAsset(context.assets, it) }.getOrNull() }
            ?: Typeface.MONOSPACE
    }

    companion object {
        fun byId(id: String?): TerminalFont = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}
