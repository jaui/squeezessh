package dev.jaui.squeezessh.data.model

/**
 * Transporttyp einer Verbindung.
 *
 * JuiceSSH kannte zusaetzlich MOSH, TELNET und eine lokale Shell ueber mitgelieferte
 * GPL-Binaries. Die Werte bleiben erhalten, damit ein Import nichts verliert; ausgewertet
 * wird vorerst nur [SSH]. Siehe docs/roadmap.md.
 */
enum class ConnectionType {
    SSH,
    MOSH,
    TELNET,
    LOCAL,
    ;

    companion object {
        fun fromStorage(value: String?): ConnectionType =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: SSH
    }
}

/** Was die Backspace-Taste sendet — Server erwarten hier historisch Unterschiedliches. */
enum class BackspaceMode {
    /** ASCII DEL (0x7F) — der Normalfall. */
    DELETE,

    /** ASCII BS (0x08). */
    BACKSPACE,
    ;

    companion object {
        fun fromStorage(value: String?): BackspaceMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: DELETE
    }
}

/** Richtung eines Port-Forwards. */
enum class PortForwardMode {
    /** Lokaler Port -> Ziel hinter dem Server (`ssh -L`). */
    LOCAL,

    /** Port auf dem Server -> Ziel im lokalen Netz (`ssh -R`). */
    REMOTE,

    /** Lokaler SOCKS5-Proxy (`ssh -D`). */
    DYNAMIC,
    ;

    companion object {
        fun fromStorage(value: String?): PortForwardMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: LOCAL
    }
}

/** Typ eines Host-Keys, wie er in den Known Hosts abgelegt wird. */
enum class HostKeyType {
    SSH_RSA,
    SSH_ED25519,
    ECDSA_SHA2_NISTP256,
    ECDSA_SHA2_NISTP384,
    ECDSA_SHA2_NISTP521,
    SSH_DSS,
    UNKNOWN,
    ;

    /** Bezeichner wie im SSH-Protokoll bzw. in `known_hosts`. */
    val wireName: String
        get() = when (this) {
            SSH_RSA -> "ssh-rsa"
            SSH_ED25519 -> "ssh-ed25519"
            ECDSA_SHA2_NISTP256 -> "ecdsa-sha2-nistp256"
            ECDSA_SHA2_NISTP384 -> "ecdsa-sha2-nistp384"
            ECDSA_SHA2_NISTP521 -> "ecdsa-sha2-nistp521"
            SSH_DSS -> "ssh-dss"
            UNKNOWN -> "unknown"
        }

    companion object {
        fun fromWireName(value: String?): HostKeyType =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) }
                ?: entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: UNKNOWN
    }
}
