package dev.jaui.squeezessh.ssh

import java.io.BufferedReader

/**
 * Führt einen einzelnen Befehl über einen eigenen Kanal aus.
 *
 * Bewusst nicht über die laufende Shell: dort landete die Ausgabe zwischen dem, was der
 * Nutzer sieht, es gäbe keinen sauberen Rückgabewert und kein erkennbares Ende. Ein
 * `exec`-Kanal liefert beides und stört die Shell nicht.
 *
 * Die Verbindung darunter wird mitbenutzt — ein zweiter Aufbau würde erneut nach Host-Key
 * und Zugangsdaten fragen und auf dem Server als weitere Anmeldung erscheinen.
 */
object SshExec {

    /**
     * @param onLine wird für jede Ausgabezeile gerufen, während der Befehl läuft
     * @return der Rückgabewert des Befehls, oder [UNKNOWN_EXIT_STATUS], wenn der Server
     *   keinen meldet — das ist erlaubt, etwa wenn der Befehl durch ein Signal endet.
     */
    fun run(connection: SshConnection, command: String, onLine: (String) -> Unit): Int {
        connection.target.startSession().use { session ->
            val exec = session.exec(command)
            // stderr wird mitgelesen: eine Fehlermeldung ist für ein Plugin oft die
            // eigentliche Antwort, und der Vertrag kennt nur einen Ausgabekanal.
            exec.inputStream.bufferedReader().use { it.forEachLineSafely(onLine) }
            exec.errorStream.bufferedReader().use { it.forEachLineSafely(onLine) }
            exec.join()
            return exec.exitStatus ?: UNKNOWN_EXIT_STATUS
        }
    }

    /**
     * Ein abgebrochener Kanal wirft beim Lesen. Das ist kein Fehlerfall, sondern das Ende —
     * was bis dahin kam, wurde bereits gemeldet.
     */
    private fun BufferedReader.forEachLineSafely(onLine: (String) -> Unit) {
        runCatching { forEachLine(onLine) }
    }

    const val UNKNOWN_EXIT_STATUS = -1
}
