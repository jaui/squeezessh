package dev.jaui.squeezessh.data.db

import androidx.room.TypeConverter
import dev.jaui.squeezessh.data.model.BackspaceMode
import dev.jaui.squeezessh.data.model.ConnectionType
import dev.jaui.squeezessh.data.model.HostKeyType
import dev.jaui.squeezessh.data.model.PortForwardMode

/**
 * Enums werden als Name gespeichert, nicht als Ordinalzahl — sonst verschiebt eine
 * spaetere Umsortierung im Enum stillschweigend alle bereits gespeicherten Werte.
 */
class Converters {

    @TypeConverter fun connectionTypeToString(value: ConnectionType): String = value.name

    @TypeConverter fun stringToConnectionType(value: String): ConnectionType =
        ConnectionType.fromStorage(value)

    @TypeConverter fun backspaceModeToString(value: BackspaceMode): String = value.name

    @TypeConverter fun stringToBackspaceMode(value: String): BackspaceMode =
        BackspaceMode.fromStorage(value)

    @TypeConverter fun portForwardModeToString(value: PortForwardMode): String = value.name

    @TypeConverter fun stringToPortForwardMode(value: String): PortForwardMode =
        PortForwardMode.fromStorage(value)

    @TypeConverter fun hostKeyTypeToString(value: HostKeyType): String = value.name

    @TypeConverter fun stringToHostKeyType(value: String): HostKeyType =
        HostKeyType.entries.firstOrNull { it.name == value } ?: HostKeyType.UNKNOWN
}
