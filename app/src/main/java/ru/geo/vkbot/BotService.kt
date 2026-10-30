package ru.geo.vkbot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Фоновый сервис с постоянным уведомлением — держит бота живым. */
class BotService : Service() {

    private var scope: CoroutineScope? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ConfigStore.init(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ConfigStore.update { it.copy(enabled = false) }
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground("Запуск…")
        if (scope == null) {
            val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            scope = s
            acquireWakeLock()
            BotLog.add("▶️ Бот запущен")
            BotEngine(s).start()
            s.launch {
                BotLog.status.combine(BotLog.sent) { st, n -> "$st · отправлено $n" }
                    .collect { updateNotification(it) }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        BotLog.status.value = "Остановлен"
        BotLog.add("⏹ Бот остановлен")
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vkgeobot:poll").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL, "Работа бота", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BotService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bot)
            .setContentTitle("Гео-бот ВК")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_bot), "Остановить", stop,
                ).build(),
            )
            .build()
    }

    private fun startInForeground(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        private const val CHANNEL = "bot"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "ru.geo.vkbot.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, BotService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BotService::class.java))
        }
    }
}

/** Автозапуск после перезагрузки или обновления, если бот был включён. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ConfigStore.init(context)
        if (ConfigStore.config.value.enabled) {
            runCatching { BotService.start(context) }
                .onFailure { BotLog.add("⚠️ Автозапуск не удался: ${it.message}") }
        }
    }
}
