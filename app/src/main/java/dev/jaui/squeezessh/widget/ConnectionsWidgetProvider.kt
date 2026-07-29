package dev.jaui.squeezessh.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dagger.hilt.android.AndroidEntryPoint
import dev.jaui.squeezessh.R
import dev.jaui.squeezessh.data.repository.ConnectionRepository
import dev.jaui.squeezessh.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Startbildschirm-Widget mit den haeufig genutzten Verbindungen.
 *
 * Bewusst ohne `RemoteViewsService`: eine feste Anzahl Zeilen genuegt fuer ein Widget
 * dieser Groesse und erspart den Umweg ueber einen Adapter-Dienst, der ohnehin nur
 * dieselben Daten liefern wuerde.
 */
@AndroidEntryPoint
class ConnectionsWidgetProvider : AppWidgetProvider() {

    @Inject lateinit var connections: ConnectionRepository

    /**
     * Eigener Scope: ein BroadcastReceiver ist nach [onUpdate] beendet, das Laden aus der
     * Datenbank aber noch nicht. `goAsync` waere die Alternative, ist hier aber unnoetig —
     * das Widget darf einen Moment spaeter fertig werden.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        widgetIds: IntArray,
    ) {
        scope.launch {
            val frequent = connections.observeFrequentlyUsed(MAX_ROWS).first()
            val all = if (frequent.isEmpty()) {
                connections.observeAll().first().take(MAX_ROWS)
            } else {
                frequent
            }

            val views = RemoteViews(context.packageName, R.layout.widget_connections).apply {
                setOnClickPendingIntent(R.id.widget_header, openApp(context, null))

                ROW_IDS.forEachIndexed { index, rowId ->
                    val connection = all.getOrNull(index)
                    if (connection == null) {
                        setViewVisibility(rowId, android.view.View.GONE)
                    } else {
                        setViewVisibility(rowId, android.view.View.VISIBLE)
                        setTextViewText(rowId, connection.displayName)
                        setOnClickPendingIntent(rowId, openApp(context, connection.id))
                    }
                }

                setViewVisibility(
                    R.id.widget_empty,
                    if (all.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE,
                )
            }
            manager.updateAppWidget(widgetIds, views)
        }
    }

    /**
     * @param connectionId `null` oeffnet nur die App. Sonst wird direkt verbunden — dafuer
     *   dient dasselbe `ssh://`-Schema wie fuer Links von aussen, damit es nur einen Weg
     *   in die App gibt.
     */
    private fun openApp(context: Context, connectionId: Long?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            connectionId?.let { putExtra(EXTRA_CONNECTION_ID, it) }
        }
        return PendingIntent.getActivity(
            context,
            // Ohne unterschiedliche Request-Codes wuerden sich die Zeilen denselben
            // PendingIntent teilen und alle dieselbe Verbindung oeffnen.
            connectionId?.toInt() ?: 0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val EXTRA_CONNECTION_ID = "dev.jaui.squeezessh.widget.CONNECTION_ID"

        private const val MAX_ROWS = 4

        private val ROW_IDS = listOf(
            R.id.widget_row_1,
            R.id.widget_row_2,
            R.id.widget_row_3,
            R.id.widget_row_4,
        )

        /** Von aussen aufrufbar, wenn sich die Verbindungen geaendert haben. */
        fun requestUpdate(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                android.content.ComponentName(context, ConnectionsWidgetProvider::class.java),
            )
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, ConnectionsWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                },
            )
        }
    }
}
