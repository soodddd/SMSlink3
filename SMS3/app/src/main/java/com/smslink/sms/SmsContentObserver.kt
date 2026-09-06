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
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private var pendingSyncJob: Job? = null
    private val syncMutex = Mutex()

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
        lastSyncTimestamp = 0L
        pendingSyncJob = scope.launch { syncNow() }
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
        pendingSyncJob?.cancel()
        pendingSyncJob = null
    }

    override fun onChange(selfChange: Boolean) {
        onChange(selfChange, null)
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        logger.d(TAG, "SMS database changed, uri: $uri")

        // Coalesce provider bursts (one SMS may generate several changes).
        pendingSyncJob?.cancel()
        pendingSyncJob = scope.launch {
            try {
                kotlinx.coroutines.delay(DEBOUNCE_MS)
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
        syncMutex.withLock {
            val currentTime = System.currentTimeMillis()
            logger.d(TAG, "Syncing messages since: $lastSyncTimestamp")
            // ContentObserver callbacks can arrive after the provider has
            // coalesced several writes, and SMS_DELIVER/SMS_RECEIVED may
            // produce more than one callback. Re-read a bounded recent
            // window on every debounced callback; SmsManagerImpl reconciles
            // provider rows idempotently.
            smsManager.syncFromSystem(MAX_SYNC_MESSAGES)
            lastSyncTimestamp = currentTime
        }
    }

    /** Performs the initial bounded import and syncs it to connected peers. */
    suspend fun syncNow() {
        syncMutex.withLock {
            smsManager.syncFromSystem(MAX_SYNC_MESSAGES)
            lastSyncTimestamp = System.currentTimeMillis()
        }
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
        private const val DEBOUNCE_MS = 500L
        private const val MAX_SYNC_MESSAGES = 100
    }
}
