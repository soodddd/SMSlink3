package com.smslink.sms

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import com.smslink.core.log.ILogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 短信内容观察者
 * 监听系统短信数据库变化，实现增量同步
 */
@Singleton
class SmsContentObserver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val smsManager: SmsManagerImpl,
    private val logger: ILogger
) : ContentObserver(Handler(Looper.getMainLooper())) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastSyncTimestamp: Long = 0
    private var isObserving = false

    /**
     * 开始监听
     */
    fun startObserving() {
        if (isObserving) {
            logger.w(TAG, "Already observing SMS changes")
            return
        }

        logger.i(TAG, "Starting SMS content observer")
        context.contentResolver.registerContentObserver(
            Telephony.Sms.CONTENT_URI,
            true,
            this
        )
        isObserving = true
        lastSyncTimestamp = System.currentTimeMillis()
    }

    /**
     * 停止监听
     */
    fun stopObserving() {
        if (!isObserving) {
            return
        }

        logger.i(TAG, "Stopping SMS content observer")
        context.contentResolver.unregisterContentObserver(this)
        isObserving = false
    }

    override fun onChange(selfChange: Boolean) {
        onChange(selfChange, null)
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        logger.d(TAG, "SMS database changed, uri: $uri")

        // 增量同步：只同步最近1分钟的消息
        scope.launch {
            try {
                syncRecentMessages()
            } catch (e: Exception) {
                logger.e(TAG, "Failed to sync recent messages", e)
            }
        }
    }

    /**
     * 同步最近的消息（增量同步）
     */
    private suspend fun syncRecentMessages() {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastSync = currentTime - lastSyncTimestamp

        // 只同步最近1分钟的消息
        if (timeSinceLastSync < SYNC_INTERVAL_MS) {
            logger.d(TAG, "Skipping sync, too soon since last sync")
            return
        }

        logger.d(TAG, "Syncing messages since: $lastSyncTimestamp")

        val messages = readRecentSystemSms(lastSyncTimestamp)
        if (messages.isNotEmpty()) {
            smsManager.syncFromSystem(messages.size)
            logger.i(TAG, "Synced ${messages.size} recent messages")
        }

        lastSyncTimestamp = currentTime
    }

    /**
     * 读取最近的系统短信
     */
    private fun readRecentSystemSms(sinceTimestamp: Long): List<String> {
        val messageIds = mutableListOf<String>()
        val uri = Telephony.Sms.CONTENT_URI
        val projection = arrayOf(Telephony.Sms._ID)
        val selection = "${Telephony.Sms.DATE} > ?"
        val selectionArgs = arrayOf(sinceTimestamp.toString())

        val cursor = context.contentResolver.query(
            uri,
            projection,
            selection,
            selectionArgs,
            "${Telephony.Sms.DATE} DESC"
        )

        cursor?.use {
            val idIndex = it.getColumnIndexOrThrow(Telephony.Sms._ID)
            while (it.moveToNext()) {
                messageIds.add(it.getString(idIndex))
            }
        }

        return messageIds
    }

    companion object {
        private const val TAG = "SmsContentObserver"
        private const val SYNC_INTERVAL_MS = 60_000L // 1分钟
    }
}
