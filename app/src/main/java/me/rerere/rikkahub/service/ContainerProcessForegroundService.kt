package me.rerere.rikkahub.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.container.TerminalForegroundLifecycle

/** Keeps PRoot background processes and interactive terminal sessions alive while the app is backgrounded. */
class ContainerProcessForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var instanceToken = 0L

    companion object {
        private const val ACTION_UPDATE = "me.rerere.rikkahub.action.CONTAINER_PROCESS_UPDATE"
        private const val EXTRA_PROCESS_COUNT = "process_count"
        private const val EXTRA_REQUEST_ID = "request_id"
        private const val CHANNEL_ID = "container_processes"
        private const val NOTIFICATION_ID = 2004
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
        private val lifecycle = TerminalForegroundLifecycle()
        private val mainHandler = Handler(Looper.getMainLooper())

        internal fun diagnostic(): TerminalForegroundLifecycle.Diagnostic {
            check(Looper.myLooper() == Looper.getMainLooper())
            return lifecycle.diagnostic()
        }

        fun update(context: Context, processCount: Int) {
            val app = context.applicationContext
            if (Looper.myLooper() != Looper.getMainLooper()) {
                mainHandler.post { update(app, processCount) }
                return
            }
            val request = lifecycle.request(processCount.coerceAtLeast(0)) ?: return
            val intent = Intent(app, ContainerProcessForegroundService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_PROCESS_COUNT, processCount)
                putExtra(EXTRA_REQUEST_ID, request.id)
            }
            try {
                ContextCompat.startForegroundService(app, intent)
            } catch (error: RuntimeException) {
                lifecycle.failed(request)
                // A later manager refresh retries; requesting start is not confirmation of success.
                Log.w("ContainerForeground", "Foreground request rejected", error)
            }
        }

        fun stop(context: Context) {
            update(context, 0)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instanceToken = lifecycle.created()
        createChannel()
        // Promote before wake-lock work and before another main-thread message can delay onStartCommand.
        promote(lifecycle.desiredCount.coerceAtLeast(1))
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promote(lifecycle.desiredCount.coerceAtLeast(1))
        val count = lifecycle.promoted(instanceToken, intent?.getLongExtra(EXTRA_REQUEST_ID, 0L) ?: 0L)
        if (count == 0 && stopSelfResult(startId)) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        return START_NOT_STICKY
    }

    private fun promote(count: Int) {
        val notification = buildNotification(count)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        lifecycle.destroyed(instanceToken)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "RikkaHub:ContainerProcessWakeLock"
            ).apply {
                setReferenceCounted(false)
                // The manager renews this while active sessions exist; timeout prevents a stuck lock.
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }.onSuccess { wakeLock = it }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "容器后台进程",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持容器命令和交互终端在后台运行"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(count: Int) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.small_icon)
        .setContentTitle("容器进程正在运行")
        .setContentText("$count 个后台命令或终端会话正在运行")
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, RouteActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .setOngoing(true)
        .setSilent(true)
        .setOnlyAlertOnce(true)
        .build()
}
