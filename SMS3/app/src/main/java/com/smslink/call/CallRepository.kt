package com.smslink.call

import android.content.Context
import android.provider.CallLog as AndroidCallLog
import com.smslink.core.database.dao.CallLogDao
import com.smslink.core.log.ILogger
import com.smslink.core.model.CallDirection
import com.smslink.core.model.CallLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val callLogDao: CallLogDao,
    private val logger: ILogger
) {
    fun getAllCallLogs(limit: Int = 100): Flow<List<CallLog>> = callLogDao.getAll(limit)

    fun getCallLogsByPhoneNumber(phoneNumber: String): Flow<List<CallLog>> =
        callLogDao.getByPhoneNumber(phoneNumber)

    fun getIncomingCalls(): Flow<List<CallLog>> = callLogDao.getByType(CallDirection.INCOMING)

    fun getOutgoingCalls(): Flow<List<CallLog>> = callLogDao.getByType(CallDirection.OUTGOING)

    fun getMissedCalls(): Flow<List<CallLog>> = callLogDao.getByType(CallDirection.MISSED)

    suspend fun getCallLogById(id: String): CallLog? = callLogDao.getById(id)

    suspend fun insertCallLog(callLog: CallLog) {
        callLogDao.insert(callLog)
    }

    suspend fun insertCallLogs(callLogs: List<CallLog>) {
        callLogDao.insertAll(callLogs)
    }

    suspend fun updateCallLog(callLog: CallLog) {
        callLogDao.update(callLog)
    }

    suspend fun deleteCallLog(callLog: CallLog) {
        callLogDao.delete(callLog)
    }

    suspend fun deleteCallLog(callId: String) {
        callLogDao.deleteById(callId)
    }

    suspend fun markAsSynced(id: String) {
        callLogDao.markAsSynced(id)
    }

    suspend fun getUnsyncedLogs(): List<CallLog> = callLogDao.getUnsyncedLogs()

    suspend fun clearAllCallLogs() {
        callLogDao.deleteAll()
    }

    suspend fun getCallLogCount(): Int = callLogDao.getCount()

    suspend fun syncFromSystem(limit: Int = 100): Result<Int> {
        return try {
            val systemCallLogs = readSystemCallLogs(limit)
            insertCallLogs(systemCallLogs)
            logger.i(TAG, "Synced ${systemCallLogs.size} call logs from system")
            Result.success(systemCallLogs.size)
        } catch (e: Exception) {
            logger.e(TAG, "Failed to sync call logs from system", e)
            Result.failure(e)
        }
    }

    private fun readSystemCallLogs(limit: Int = 100): List<CallLog> {
        val callLogs = mutableListOf<CallLog>()
        try {
            val projection = arrayOf(
                AndroidCallLog.Calls._ID,
                AndroidCallLog.Calls.NUMBER,
                AndroidCallLog.Calls.CACHED_NAME,
                AndroidCallLog.Calls.TYPE,
                AndroidCallLog.Calls.DATE,
                AndroidCallLog.Calls.DURATION
            )

            val cursor = context.contentResolver.query(
                AndroidCallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${AndroidCallLog.Calls.DATE} DESC LIMIT $limit"
            )

            cursor?.use {
                val idIndex = it.getColumnIndex(AndroidCallLog.Calls._ID)
                val numberIndex = it.getColumnIndex(AndroidCallLog.Calls.NUMBER)
                val nameIndex = it.getColumnIndex(AndroidCallLog.Calls.CACHED_NAME)
                val typeIndex = it.getColumnIndex(AndroidCallLog.Calls.TYPE)
                val dateIndex = it.getColumnIndex(AndroidCallLog.Calls.DATE)
                val durationIndex = it.getColumnIndex(AndroidCallLog.Calls.DURATION)

                while (it.moveToNext()) {
                    val id = it.getString(idIndex)
                    val number = it.getString(numberIndex) ?: ""
                    val name = it.getString(nameIndex)
                    val type = it.getInt(typeIndex)
                    val date = it.getLong(dateIndex)
                    val duration = it.getLong(durationIndex)

                    val callDirection = when (type) {
                        AndroidCallLog.Calls.INCOMING_TYPE -> CallDirection.INCOMING
                        AndroidCallLog.Calls.OUTGOING_TYPE -> CallDirection.OUTGOING
                        AndroidCallLog.Calls.MISSED_TYPE -> CallDirection.MISSED
                        else -> CallDirection.INCOMING
                    }

                    callLogs.add(
                        CallLog(
                            id = id,
                            phoneNumber = number,
                            contactName = name,
                            type = callDirection,
                            timestamp = date,
                            duration = duration * 1000,
                            isSynced = false
                        )
                    )
                }
            }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to read system call logs", e)
        }
        return callLogs
    }

    companion object {
        private const val TAG = "CallRepository"
    }
}
