package dev.jaui.squeezessh.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Prüfpfad der Plugin-API: welches Paket wann was getan hat.
 *
 * JuiceSSH führte dieselbe Tabelle (`plugin_log` mit `packageName` und `message`) und gab sie
 * über den Provider schreibgeschützt heraus. Der Zweck ist nicht Fehlersuche, sondern
 * Nachvollziehbarkeit: ein Plugin hat Zugriff auf Verbindungen und kann Sitzungen aufbauen,
 * und der Nutzer soll hinterher sehen können, was in seinem Namen geschehen ist.
 */
@Entity(
    tableName = "plugin_log",
    indices = [Index("packageName"), Index("createdAt")],
)
data class PluginLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val message: String,
    val createdAt: Long,
)
