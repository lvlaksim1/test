package __PACKAGE__.timeaccessibility

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class TimeCycleForegroundService : Service() {
    companion object {
        private const val CHANNEL_ID = "time_cycle_active"
        private const val NOTIFICATION_ID = 7202
        private const val ACTION_START = "time_cycle_start"

        fun start(context: Context) {
            val intent = Intent(context, TimeCycleForegroundService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TimeCycleForegroundService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!TimeCycleStore.isRunning(applicationContext)) {
            stopSelf()
            return START_NOT_STICKY
        }
        TimeCycleRunner.start(applicationContext)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "Машина времени", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Выполнение активного цикла изменения системного времени"
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
        .setContentTitle("Машина времени")
        .setContentText("Цикл выполняется в фоновом режиме")
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(buildLaunchIntent())
        .build()

    private fun buildLaunchIntent(): PendingIntent? {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, 0, launchIntent, flags)
    }
}
