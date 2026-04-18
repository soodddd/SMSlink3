package com.smslink.notification

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
import com.smslink.MainActivity
import com.smslink.core.log.ILogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 通知同步前台服务
 * 保持应用在后台运行，持续监听通知和网络消息
 */
@AndroidEntryPoint
class NotificationSyncService : Service() {

    @Inject
    lateinit var notificationManager: NotificationManagerImpl

    @Inject
    lateinit var logger: ILogger

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        logger.i(TAG, "NotificationSyncService created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logger.i(TAG, "NotificationSyncService started")

        when (intent?.action) {
            ACTION_START -> startService()
            ACTION_STOP -> stopService()
            else -> startService()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        logger.i(TAG, "NotificationSyncService destroyed")
        isRunning = false
        scope.cancel()
    }

    /**
     * 启动服务
     */
    private fun startService() {
        if (isRunning) {
            logger.w(TAG, "Service already running")
            return
        }

        // 启动前台服务
        val notification = createForegroundNotification()
        startForeground(NOTIFICATION_ID, notification)

        // 启动通知监听
        notificationManager.startListening()

        // 监听新通知并自动同步
        startNotificationSync()

        isRunning = true
        logger.i(TAG, "NotificationSyncService started successfully")
    }

    /**
     * 停止服务
     */
    private fun stopService() {
        logger.i(TAG, "Stopping NotificationSyncService")
        notificationManager.stopListening()
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * 启动通知同步
     */
    private fun startNotificationSync() {
        scope.launch {
            notificationManager.getNotifications()
                .filter { notification -> !notification.isSynced }
                .catch { e ->
                    logger.e(TAG, "Error in notification sync", e)
                }
                .collect { notification ->
                    logger.d(TAG, "New notification received: ${notification.appName}")

                    // 自动同步到所有已连接设备
                    try {
                        notificationManager.syncNotificationToDevices(notification)
                    } catch (e: Exception) {
                        logger.e(TAG, "Failed to sync notification", e)
                    }
                }
        }
    }

    /**
     * 创建前台服务通知
     */
    private fun createForegroundNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, NotificationSyncService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SMS Link")
            .setContentText("Notification sync is active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPendingIntent
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Notification Sync Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps notification sync running in background"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "NotificationSyncService"
        private const val CHANNEL_ID = "notification_sync_service"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.smslink.notification.ACTION_START"
        const val ACTION_STOP = "com.smslink.notification.ACTION_STOP"

        /**
         * 启动服务
         */
        fun start(context: Context) {
            val intent = Intent(context, NotificationSyncService::class.java).apply {
                action = ACTION_START
            }
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
            val intent = Intent(context, NotificationSyncService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
