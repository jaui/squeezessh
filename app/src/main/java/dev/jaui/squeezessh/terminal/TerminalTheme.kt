package dev.jaui.squeezessh.terminal

import android.graphics.Color

/**
 * Farbschema eines Terminals.
 *
 * Termux haelt die Farben als `int[]` mit 259 Eintraegen: 0–15 die Grundfarben, 16–255
 * die 6×6×6-Wuerfel- und Graustufen des 256-Farben-Modus, danach Vordergrund, Hintergrund
 * und Cursor. Die oberen 240 baut der Emulator selbst auf; hier werden nur die 16
 * Grundfarben und die drei Sonderindizes gesetzt.
 */
data class TerminalTheme(
    val id: String,
    val name: String,
    val foreground: Int,
    val background: Int,
    val cursor: Int,
    /** Genau 16 Werte: 8 normale, dann 8 helle. */
    val palette: List<Int>,
) {
    init {
        require(palette.size == PALETTE_SIZE) { "Palette braucht $PALETTE_SIZE Farben" }
    }

    fun applyTo(colors: IntArray) {
        palette.forEachIndexed { index, color -> colors[index] = color }
        colors[INDEX_FOREGROUND] = foreground
        colors[INDEX_BACKGROUND] = background
        colors[INDEX_CURSOR] = cursor
    }

    companion object {
        const val PALETTE_SIZE = 16
        private const val INDEX_FOREGROUND = 256
        private const val INDEX_BACKGROUND = 257
        private const val INDEX_CURSOR = 258

        private fun rgb(hex: String) = Color.parseColor(hex)

        val Dark = TerminalTheme(
            id = "dark",
            name = "Dunkel",
            foreground = rgb("#E0E0E0"),
            background = rgb("#101010"),
            cursor = rgb("#E0E0E0"),
            palette = listOf(
                "#000000", "#CC3333", "#33AA33", "#BBAA33",
                "#3355CC", "#AA33AA", "#33AAAA", "#CCCCCC",
                "#555555", "#FF5555", "#55FF55", "#FFFF55",
                "#5588FF", "#FF55FF", "#55FFFF", "#FFFFFF",
            ).map(::rgb),
        )

        val Light = TerminalTheme(
            id = "light",
            name = "Hell",
            foreground = rgb("#1A1A1A"),
            background = rgb("#FAFAFA"),
            cursor = rgb("#1A1A1A"),
            palette = listOf(
                "#000000", "#C23621", "#25BC24", "#ADAD27",
                "#492EE1", "#D338D3", "#33BBC8", "#CBCCCD",
                "#818383", "#FC391F", "#31E722", "#EAEC23",
                "#5833FF", "#F935F8", "#14F0F0", "#E9EBEB",
            ).map(::rgb),
        )

        /** Solarized von Ethan Schoonover, BSD-lizenziert — auch JuiceSSH hatte es dabei. */
        val SolarizedDark = TerminalTheme(
            id = "solarized_dark",
            name = "Solarized Dark",
            foreground = rgb("#839496"),
            background = rgb("#002B36"),
            cursor = rgb("#93A1A1"),
            palette = listOf(
                "#073642", "#DC322F", "#859900", "#B58900",
                "#268BD2", "#D33682", "#2AA198", "#EEE8D5",
                "#002B36", "#CB4B16", "#586E75", "#657B83",
                "#839496", "#6C71C4", "#93A1A1", "#FDF6E3",
            ).map(::rgb),
        )

        val SolarizedLight = TerminalTheme(
            id = "solarized_light",
            name = "Solarized Light",
            foreground = rgb("#657B83"),
            background = rgb("#FDF6E3"),
            cursor = rgb("#586E75"),
            palette = SolarizedDark.palette,
        )

        val all = listOf(Dark, Light, SolarizedDark, SolarizedLight)

        fun byId(id: String?): TerminalTheme = all.firstOrNull { it.id == id } ?: Dark
    }
}
