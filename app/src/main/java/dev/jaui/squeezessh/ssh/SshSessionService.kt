package dev.jaui.squeezessh.ssh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.jaui.squeezessh.R
import dev.jaui.squeezessh.ui.MainActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Haelt den Prozess am Leben, solange Sitzungen offen sind.
 *
 * Ohne diesen Dienst sammelt Android die App beim Wechsel in den Hintergrund ein und alle
 * Verbindungen brechen weg — genau das, was bei einem SSH-Client am meisten stoert.
 * Der Dienst beendet sich selbst, sobald die letzte Sitzung geschlossen ist; eine dauerhaft
 * sichtbare Notification ohne Grund waere nur laestig.
 */
@AndroidEntryPoint
class SshSessionService : LifecycleService() {

    @Inject lateinit var sessionManager: SshSessionManager

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        lifecycleScope.launch {
            sessionManager.openSessions.collectLatest { sessions ->
                if (sessions.isEmpty()) {
                    stopSelf()
                } else {
                    startForeground(sessions.values.toList())
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(sessionManager.openSessions.value.values.toList())
        return START_STICKY
    }

    private fun startForeground(sessions: List<SshSession>) {
        val notification = buildNotification(sessions)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun buildNotification(sessions: List<SshSession>): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = resources.getQuantityString(
            R.plurals.sessions_active,
            sessions.size,
            sessions.size,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(sessions.joinToString(", ") { it.title })
            .setSmallIcon(R.drawable.ic_notification_session)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_sessions),
            // LOW: die Notification ist eine Statusanzeige, kein Ereignis.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_sessions_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "ssh_sessions"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SshSessionService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SshSessionService::class.java))
        }
    }
}
