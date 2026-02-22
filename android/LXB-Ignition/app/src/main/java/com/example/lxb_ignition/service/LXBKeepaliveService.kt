package com.example.lxb_ignition.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.example.lxb_ignition.shizuku.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * LXB 前台服务保活
 * 提供三层保活机制：WakeLock + Foreground Service + Watchdog
 */
class LXBKeepaliveService : Service() {

    companion object {
        private const val TAG = "LXBKeepaliveService"
        private const val PREFS_NAME = "lxb_config"
        private const val KEY_LXB_PORT = "lxb_port"
        private const val CHANNEL_ID = "lxb_keepalive"
        private const val NOTIFICATION_ID = 1001
        private const val WATCHDOG_INTERVAL_MS = 10_000L
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var shizukuManager: ShizukuManager
    private var watchdogJob: Job? = null

    override fun onCreate() {
        super.onCreate()

        shizukuManager = ShizukuManager(this)
        shizukuManager.setListener(object : ShizukuManager.Listener {
            override fun onStateChanged(state: ShizukuManager.State, message: String) {
                Log.i(TAG, "Shizuku 状态: $state - $message")
                updateNotification(state, message)
            }

            override fun onLogLine(line: String) {
                Log.d(TAG, line)
            }
        })
        shizukuManager.attach()

        createNotificationChannel()
        val notification = buildNotification(ShizukuManager.State.UNAVAILABLE, "服务启动中...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        acquireWakeLock()
        startWatchdog()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        watchdogJob?.cancel()
        serviceScope.cancel()
        shizukuManager.detach()
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    /**
     * 10秒 Watchdog：若 Shizuku 就绪但服务未运行则自动重启
     */
    private fun startWatchdog() {
        watchdogJob = serviceScope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                try {
                    val state = shizukuManager.currentState
                    if (state == ShizukuManager.State.RUNNING) {
                        if (!shizukuManager.isServerRunning()) {
                            Log.i(TAG, "Watchdog: 进程已停止，尝试重启")
                            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            val port = prefs.getString(KEY_LXB_PORT, "12345")?.toIntOrNull() ?: 12345
                            shizukuManager.startServer(port)
                        }
                    } else if (state == ShizukuManager.State.READY) {
                        Log.i(TAG, "Watchdog: Shizuku 就绪，启动服务")
                        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        val port = prefs.getString(KEY_LXB_PORT, "12345")?.toIntOrNull() ?: 12345
                        shizukuManager.startServer(port)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Watchdog 异常: ${e.message}")
                }
            }
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "LXB::KeepaliveWakeLock"
        )
        wakeLock?.acquire(24 * 60 * 60 * 1000L)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "LXB 保活服务",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(state: ShizukuManager.State, message: String): Notification {
        val contentText = when (state) {
            ShizukuManager.State.RUNNING -> "服务运行中 ✓"
            ShizukuManager.State.STARTING -> "启动中..."
            ShizukuManager.State.READY -> "Shizuku 就绪，等待启动"
            ShizukuManager.State.PERMISSION_DENIED -> "需要 Shizuku 授权"
            ShizukuManager.State.ERROR -> "错误: $message"
            ShizukuManager.State.UNAVAILABLE -> message.ifEmpty { "Shizuku 未就绪" }
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("LXB Ignition")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    private fun updateNotification(state: ShizukuManager.State, message: String) {
        val notification = buildNotification(state, message)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }
}
