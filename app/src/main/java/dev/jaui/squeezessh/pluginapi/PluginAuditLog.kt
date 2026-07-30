package dev.jaui.squeezessh.pluginapi

import dev.jaui.squeezessh.data.db.dao.PluginLogDao
import dev.jaui.squeezessh.data.db.entity.PluginLogEntity
import dev.jaui.squeezessh.util.TimeSource
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.days

/**
 * Schreibt und liest den Prüfpfad der Plugin-API.
 *
 * Ein Plugin darf Verbindungen lesen und Sitzungen aufbauen. Damit das nachvollziehbar
 * bleibt, wird jeder Zugriff mit Paketnamen vermerkt — und zwar bevor er ausgeführt wird,
 * damit auch ein abgewiesener Versuch sichtbar ist.
 */
@Singleton
class PluginAuditLog @Inject constructor(
    private val dao: PluginLogDao,
    private val time: TimeSource,
) {

    fun observeRecent(limit: Int = 200): Flow<List<PluginLogEntity>> = dao.observeRecent(limit)

    fun observePackages(): Flow<List<String>> = dao.observePackages()

    suspend fun record(packageName: String, message: String) {
        dao.insert(
            PluginLogEntity(
                packageName = packageName,
                message = message,
                createdAt = time.nowMillis(),
            ),
        )
    }

    /** Räumt alte Einträge weg; wird beim Schreiben gelegentlich mitgenommen. */
    suspend fun pruneOld() = dao.deleteOlderThan(time.nowMillis() - RETENTION.inWholeMilliseconds)

    suspend fun clear() = dao.deleteAll()

    private companion object {
        /**
         * 30 Tage. Lang genug, um „was hat das Plugin letzten Monat getan" zu beantworten,
         * kurz genug, dass die Tabelle nicht unbegrenzt wächst.
         */
        val RETENTION = 30.days
    }
}
