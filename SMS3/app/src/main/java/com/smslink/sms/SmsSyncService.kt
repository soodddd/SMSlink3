package com.smslink.sms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.smslink.R
import com.smslink.core.log.ILogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 短信同步前台服务
 * 保持应用在后台运行，持续监听短信和网络消息
 */
@AndroidEntryPoint
class SmsSyncService : Service() {

    @Inject
    lateinit var smsManager: SmsManagerImpl

    @Inject
    lateinit var logger: ILogger

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        logger.i(TAG, "SmsSyncService created")

        // 创建通知渠道
        createNotificationChannel()

        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification())

        // 开始监听
        startSyncMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logger.d(TAG, "SmsSyncService started")
        return START_STICKY // 服务被杀死后自动重启
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        logger.i(TAG, "SmsSyncService destroyed")

        // 取消所有协程
        syncJob?.cancel()
        serviceScope.cancel()
    }

    /**
     * 开始同步监听
     */
    private fun startSyncMonitoring() {
        syncJob = serviceScope.launch {
            try {
                // 监听新消息
                smsManager.observeNewMessages()
                    .catch { e ->
                        logger.e(TAG, "Error observing new messages", e)
                    }
                    .collect { message ->
                        logger.d(TAG, "New message in sync service: ${message.id}")
                        updateNotification("New message from ${message.address}")
                    }
            } catch (e: Exception) {
                logger.e(TAG, "Error in sync monitoring", e)
            }
        }
    }

    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SMS Sync Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keep SMS sync running in background"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 创建通知
     */
    private fun createNotification(contentText: String = "SMS sync is running"): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SMS Sync")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * 更新通知
     */
    private fun updateNotification(contentText: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(contentText))
    }

    companion object {
        private const val TAG = "SmsSyncService"
        private const val CHANNEL_ID = "sms_sync_channel"
        private const val NOTIFICATION_ID = 1001

        /**
         * 启动服务
         */
        fun start(context: Context) {
            val intent = Intent(context, SmsSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * 停止服务
         */
        fun stop(context: Context) {
            val intent = Intent(context, SmsSyncService::class.java)
            context.stopService(intent)
        }
    }
}
