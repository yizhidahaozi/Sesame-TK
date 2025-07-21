package com.example.service // 请替换为你的实际包名

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.IOException

class MyMainService : Service() {
    // 协程管理
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    // 服务配置参数
    private var syncIntervalMillis: Long = 60_000 // 默认1分钟
    private var isSyncing = false

    // 使用 lazy 初始化 NotificationManager
    private val notificationManager: NotificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    // 通知相关常量
    companion object {
        const val CHANNEL_ID = "data_sync_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val EXTRA_SYNC_INTERVAL = "sync_interval" // 同步间隔(毫秒)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("服务初始化中..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // 获取同步间隔参数(如果有)
                syncIntervalMillis = intent.getLongExtra(EXTRA_SYNC_INTERVAL, 60_000)
                startSync()
            }
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isSyncing = false
        serviceJob.cancel()
        updateNotification("服务已停止")
    }

    private fun startSync() {
        if (isSyncing) return

        isSyncing = true
        serviceScope.launch {
            while (isActive && isSyncing) {
                try {
                    updateNotification("开始数据同步...")
                    syncDataWithServer()
                    updateNotification("数据同步完成")
                } catch (e: IOException) {
                    updateNotification("同步失败: 网络错误")
                    e.printStackTrace()
                } catch (e: Exception) {
                    updateNotification("同步失败: 未知错误")
                    e.printStackTrace()
                }
                delay(syncIntervalMillis)
            }
        }
    }

    private suspend fun syncDataWithServer() {
        withContext(Dispatchers.IO) {
            // 模拟网络请求延迟
            delay(2000)

            // 这里应该有实际的数据同步逻辑
            // 例如：
            // val result = repository.syncData()
            // if (!result) throw IOException("同步失败")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "数据同步服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "后台数据同步服务通知"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String = "数据同步中..."): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("数据同步服务")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setWhen(System.currentTimeMillis())
            .build()
    }

    private fun updateNotification(content: String) {
        notificationManager.notify(NOTIFICATION_ID, createNotification(content))
    }
}
