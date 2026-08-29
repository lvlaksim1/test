// SPDX-License-Identifier: Apache-2.0
package __PACKAGE__.timeaccessibility

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat

class TimePairingService : Service() {
    companion object {
        private const val CHANNEL_ID = "time_pairing"
        private const val NOTIFICATION_ID = 7203
        private const val ACTION_START = "time_pairing_start"
        private const val ACTION_SUBMIT = "time_pairing_submit"
        private const val ACTION_CANCEL = "time_pairing_cancel"
        private const val REMOTE_INPUT_KEY = "time_pairing_code"

        fun start(context: Context) {
            val intent = Intent(context, TimePairingService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TimePairingService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SUBMIT -> handlePairingCode(intent)
            ACTION_CANCEL -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> startForeground(NOTIFICATION_ID, buildInputNotification("Откройте окно сопряжения и оставьте его открытым."))
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handlePairingCode(intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(REMOTE_INPUT_KEY)
            ?.toString()
            ?.filter(Char::isDigit)
            ?.take(6)
            .orEmpty()

        if (!code.matches(Regex("^\\d{6}$"))) {
            notifyInput("Введите шестизначный код сопряжения.")
            return
        }

        notifyWorking()
        TimeLocalAdbController.pair(applicationContext, code) { outcome ->
            if (outcome.isSuccess) {
                val manager = getSystemService(NotificationManager::class.java)
                stopForeground(STOP_FOREGROUND_DETACH)
                manager.notify(NOTIFICATION_ID, buildFinishedNotification("Сопряжение выполнено. Системный доступ активен."))
                stopSelf()
            } else {
                notifyInput(outcome.detail)
            }
        }
    }

    private fun notifyInput(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildInputNotification(message))
    }

    private fun notifyWorking() {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Машина времени — сопряжение")
                .setContentText("Проверяем код и подключаем системный доступ…")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build(),
        )
    }

    private fun buildInputNotification(message: String): android.app.Notification {
        val remoteInput = RemoteInput.Builder(REMOTE_INPUT_KEY)
            .setLabel("6-значный код")
            .build()

        val submitIntent = Intent(this, TimePairingService::class.java).setAction(ACTION_SUBMIT)
        val submitPendingIntent = PendingIntent.getService(this, 1, submitIntent, mutablePendingIntentFlags())
        val submitAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Ввести код",
            submitPendingIntent,
        ).addRemoteInput(remoteInput).build()

        val cancelIntent = Intent(this, TimePairingService::class.java).setAction(ACTION_CANCEL)
        val cancelPendingIntent = PendingIntent.getService(
            this,
            2,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Машина времени — сопряжение")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$message\nНе закрывая окно с кодом, раскройте это уведомление и нажмите «Ввести код»."))
            .setContentIntent(developerSettingsPendingIntent())
            .addAction(submitAction)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отмена", cancelPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun buildFinishedNotification(message: String): android.app.Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Машина времени")
            .setContentText(message)
            .setContentIntent(launchAppPendingIntent())
            .setAutoCancel(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun developerSettingsPendingIntent(): PendingIntent {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        return PendingIntent.getActivity(
            this,
            3,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun launchAppPendingIntent(): PendingIntent? {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            4,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun mutablePendingIntentFlags(): Int {
        val mutableFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "Сопряжение системного доступа", NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = "Ввод кода беспроводной отладки без закрытия окна сопряжения"
        manager.createNotificationChannel(channel)
    }
}
