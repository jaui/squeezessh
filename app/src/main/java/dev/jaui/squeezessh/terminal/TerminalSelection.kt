package dev.jaui.squeezessh.terminal

import com.termux.terminal.TerminalEmulator

/**
 * Ein markierter Bereich im Terminal.
 *
 * Zeilen sind absolut gezaehlt wie [TerminalRenderer][com.termux.view.TerminalRenderer] sie
 * erwartet: 0 ist die oberste sichtbare Zeile bei ungescrolltem Bildschirm, negative Werte
 * liegen im Verlauf. Eine Auswahl bleibt dadurch an ihrem Text kleben, wenn gescrollt wird.
 */
data class TerminalSelection(
    val startColumn: Int,
    val startRow: Int,
    val endColumn: Int,
    val endRow: Int,
) {
    /** Anfang vor Ende, egal in welche Richtung gezogen wurde. */
    fun normalized(): TerminalSelection = when {
        startRow < endRow -> this
        startRow > endRow -> TerminalSelection(endColumn, endRow, startColumn, startRow)
        startColumn <= endColumn -> this
        else -> TerminalSelection(endColumn, endRow, startColumn, startRow)
    }

    fun withStart(column: Int, row: Int) = copy(startColumn = column, startRow = row)

    fun withEnd(column: Int, row: Int) = copy(endColumn = column, endRow = row)

    companion object {

        /**
         * Markiert das Wort unter der angetippten Zelle.
         *
         * Bei einem Long-Press auf ein einzelnes Zeichen anzufangen waere zwar korrekt,
         * aber unbrauchbar: gemeint ist praktisch immer ein Wort, ein Pfad oder eine
         * Adresse.
         */
        fun wordAt(emulator: TerminalEmulator, column: Int, row: Int): TerminalSelection {
            // Endspalte ist inklusive. Nachlaufende Leerzeichen liefert der Puffer nicht
            // mit, die Zeile ist hier also bereits am rechten Rand beschnitten.
            //
            // Die Indizierung nimmt an, dass ein Zeichen einer Spalte entspricht. Bei
            // doppelt breiten Zeichen (CJK) verschiebt sich die Wortgrenze dadurch —
            // fuer Pfade, Hostnamen und Fehlermeldungen, um die es hier geht, stimmt es.
            val line = emulator.screen
                .getSelectedText(0, row, emulator.mColumns - 1, row)
                .trimEnd('\n')
            if (line.isEmpty() || column >= line.length || line[column].isWordBoundary()) {
                return TerminalSelection(column, row, column, row)
            }

            var start = column
            while (start > 0 && !line[start - 1].isWordBoundary()) start--
            var end = column
            while (end < line.length - 1 && !line[end + 1].isWordBoundary()) end++

            return TerminalSelection(start, row, end, row)
        }

        /**
         * Was als Wortgrenze gilt.
         *
         * Punkte, Schraegstriche, Bindestriche und Doppelpunkte gehoeren bewusst **nicht**
         * dazu: sonst zerfaellt genau das, was man im Terminal markieren will — Pfade,
         * Hostnamen, `host:port`.
         */
        private fun Char.isWordBoundary(): Boolean =
            this.isWhitespace() || this in "\"'`()[]{}<>|&;*?!,"
    }
}
