package dev.jaui.squeezessh.sftp

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
 * Zeigt laufende Uebertragungen in der Statusleiste und laesst sie von dort steuern.
 *
 * Zweck ist nicht nur die Anzeige: ohne Vordergrunddienst wuerde Android den Prozess
 * einsammeln, sobald der Nutzer die App verlaesst — und eine halb uebertragene Datei
 * zurueckbleiben. Der Dienst beendet sich, sobald nichts mehr laeuft.
 */
@AndroidEntryPoint
class SftpTransferService : LifecycleService() {

    @Inject lateinit var transfers: SftpTransferManager

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        lifecycleScope.launch {
            transfers.transfers.collectLatest { list ->
                val running = list.filterNot { it.isFinished }
                if (running.isEmpty()) stopSelf() else showNotification(running)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_PAUSE -> intent.transferId()?.let(transfers::pause)
            ACTION_RESUME -> intent.transferId()?.let(transfers::resume)
            ACTION_CANCEL -> intent.transferId()?.let(transfers::cancel)
        }

        val running = transfers.transfers.value.filterNot { it.isFinished }
        if (running.isEmpty()) stopSelf() else showNotification(running)
        return START_NOT_STICKY
    }

    private fun Intent.transferId(): Long? =
        getLongExtra(EXTRA_TRANSFER_ID, -1L).takeIf { it >= 0 }

    /**
     * Zuletzt angezeigter Stand, um unnoetige Aktualisierungen zu vermeiden.
     *
     * Der Fortschritt trifft pro 32-KiB-Haeppchen ein — bei einer grossen Datei werden
     * daraus tausende Notification-Updates, die Android ohnehin wegwirft. Angezeigt wird
     * nur, was sich sichtbar geaendert hat: Zustand, Prozentwert, oder bei unbekannter
     * Gesamtgroesse die uebertragenen Mebibyte.
     */
    private var lastShown: String? = null

    private fun showNotification(running: List<SftpTransfer>) {
        val signature = running.joinToString("|") { transfer ->
            val step = transfer.progress
                ?.let { "${(it * 100).toInt()}%" }
                ?: "${transfer.transferredBytes / (1024 * 1024)}MiB"
            "${transfer.id}:${transfer.state}:$step"
        }
        if (signature == lastShown) return
        lastShown = signature

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(running),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    /**
     * Bei mehreren Uebertragungen zeigt die Notification die erste im Detail und den Rest
     * als Anzahl. Eine Gruppe aus mehreren Notifications waere zwar vollstaendiger, dafuer
     * aber schwerer zu bedienen — die Knoepfe muessten sich dann auf die richtige beziehen.
     */
    private fun buildNotification(running: List<SftpTransfer>): Notification {
        val first = running.first()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(
                if (first.direction == TransferDirection.DOWNLOAD) {
                    "Lädt herunter: ${first.name}"
                } else {
                    "Lädt hoch: ${first.name}"
                },
            )
            .setContentText(
                buildString {
                    append(formatBytes(first.transferredBytes))
                    if (first.totalBytes > 0) append(" / ").append(formatBytes(first.totalBytes))
                    if (first.state == TransferState.PAUSED) append("  ·  angehalten")
                    if (running.size > 1) append("  ·  +${running.size - 1} weitere")
                },
            )
            .setSmallIcon(R.drawable.ic_notification_transfer)
            .setContentIntent(open)
            .setOngoing(first.state == TransferState.RUNNING)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        val progress = first.progress
        if (progress == null) {
            // Ohne bekannte Gesamtgroesse ist ein Balken mit Prozentwert eine Luege.
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(PROGRESS_MAX, (progress * PROGRESS_MAX).toInt(), false)
        }

        if (first.state == TransferState.RUNNING) {
            builder.addAction(0, "Pause", action(ACTION_PAUSE, first.id))
        } else {
            builder.addAction(0, "Weiter", action(ACTION_RESUME, first.id))
        }
        builder.addAction(0, "Abbrechen", action(ACTION_CANCEL, first.id))

        return builder.build()
    }

    private fun action(action: String, transferId: Long): PendingIntent =
        PendingIntent.getService(
            this,
            // Aktion und Uebertragung muessen in den Request-Code eingehen, sonst
            // ueberschreiben sich die Intents gegenseitig.
            (action.hashCode() xor transferId.toInt()),
            Intent(this, SftpTransferService::class.java).apply {
                this.action = action
                putExtra(EXTRA_TRANSFER_ID, transferId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_transfers),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_transfers_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KiB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MiB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GiB".format(bytes / (1024.0 * 1024 * 1024))
    }

    companion object {
        private const val CHANNEL_ID = "sftp_transfers"
        private const val NOTIFICATION_ID = 2
        private const val PROGRESS_MAX = 1000

        private const val ACTION_PAUSE = "dev.jaui.squeezessh.transfer.PAUSE"
        private const val ACTION_RESUME = "dev.jaui.squeezessh.transfer.RESUME"
        private const val ACTION_CANCEL = "dev.jaui.squeezessh.transfer.CANCEL"
        private const val EXTRA_TRANSFER_ID = "transferId"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SftpTransferService::class.java))
        }
    }
}
