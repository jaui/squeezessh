package dev.jaui.squeezessh.data.model

/**
 * Ein Verbindungsziel, wie es jemand eintippt oder wie es aus einem `ssh://`-Link kommt.
 *
 * Bewusst ohne Datenbankbezug: das hier ist reine Textverarbeitung und deshalb ohne
 * Android-Umgebung testbar.
 */
data class ConnectionTarget(
    val host: String,
    val port: Int = DEFAULT_PORT,
    val username: String? = null,
) {
    /** Wieder in die Kurzschreibweise, ohne Standardwerte. */
    override fun toString(): String = buildString {
        username?.let { append(it).append('@') }
        append(if (host.contains(':')) "[$host]" else host)
        if (port != DEFAULT_PORT) append(':').append(port)
    }

    companion object {
        const val DEFAULT_PORT = 22

        /**
         * Zerlegt eine Eingabe.
         *
         * Verstanden werden `host`, `user@host`, `user@host:port` und dieselben Formen mit
         * vorangestelltem `ssh://`. IPv6-Adressen gehoeren in eckige Klammern, sonst waere
         * der Doppelpunkt nicht vom Port zu unterscheiden.
         *
         * @return `null`, wenn kein brauchbarer Host uebrig bleibt.
         */
        fun parse(input: String?): ConnectionTarget? {
            var rest = input?.trim().orEmpty()
            if (rest.isEmpty()) return null

            SCHEMES.firstOrNull { rest.startsWith(it, ignoreCase = true) }?.let {
                rest = rest.substring(it.length)
            }
            // Ein Pfad hinter dem Host gehoert nicht zu einer SSH-Verbindung.
            rest = rest.substringBefore('/')
            if (rest.isEmpty()) return null

            // Nur am letzten @ trennen: Benutzernamen duerfen selbst eins enthalten,
            // etwa bei Konten der Form vorname.nachname@firma.
            val username = rest.substringBeforeLast('@', missingDelimiterValue = "")
                .takeIf { it.isNotEmpty() && rest.contains('@') }
            if (rest.contains('@')) rest = rest.substringAfterLast('@')
            if (rest.isEmpty()) return null

            val (host, port) = splitHostAndPort(rest) ?: return null
            if (host.isEmpty()) return null

            return ConnectionTarget(host = host, port = port, username = username)
        }

        private fun splitHostAndPort(value: String): Pair<String, Int>? {
            if (value.startsWith('[')) {
                val closing = value.indexOf(']')
                if (closing < 0) return null
                val host = value.substring(1, closing)
                val remainder = value.substring(closing + 1)
                val port = when {
                    remainder.isEmpty() -> DEFAULT_PORT
                    remainder.startsWith(':') -> remainder.drop(1).toPortOrNull() ?: return null
                    else -> return null
                }
                return host to port
            }

            // Mehr als ein Doppelpunkt heisst IPv6 ohne Klammern — dann gibt es keinen Port.
            if (value.count { it == ':' } > 1) return value to DEFAULT_PORT

            val host = value.substringBefore(':')
            val port = value.substringAfter(':', missingDelimiterValue = "")
                .takeIf { it.isNotEmpty() }
                ?.toPortOrNull()
                ?: if (value.contains(':')) return null else DEFAULT_PORT

            return host to port
        }

        private fun String.toPortOrNull(): Int? = toIntOrNull()?.takeIf { it in 1..65535 }

        private val SCHEMES = listOf("ssh://", "sftp://", "mosh://", "telnet://")
    }
}
