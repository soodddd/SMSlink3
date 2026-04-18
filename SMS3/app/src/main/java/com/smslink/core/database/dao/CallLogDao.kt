package com.smslink.core.database.dao

import androidx.room.*
import com.smslink.core.model.CallLog
import com.smslink.core.model.CallDirection
import kotlinx.coroutines.flow.Flow

/**
 * 通话记录 DAO
 */
@Dao
interface CallLogDao {

    @Query("SELECT * FROM call_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getAll(limit: Int = 100): Flow<List<CallLog>>

    @Query("SELECT * FROM call_logs WHERE id = :id")
    suspend fun getById(id: String): CallLog?

    @Query("SELECT * FROM call_logs WHERE phoneNumber = :phoneNumber ORDER BY timestamp DESC")
    fun getByPhoneNumber(phoneNumber: String): Flow<List<CallLog>>

    @Query("SELECT * FROM call_logs WHERE type = :type ORDER BY timestamp DESC")
    fun getByType(type: CallDirection): Flow<List<CallLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(callLog: CallLog)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(callLogs: List<CallLog>)

    @Update
    suspend fun update(callLog: CallLog)

    @Delete
    suspend fun delete(callLog: CallLog)

    @Query("DELETE FROM call_logs WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE call_logs SET isSynced = 1 WHERE id = :id")
    suspend fun markAsSynced(id: String)

    @Query("SELECT * FROM call_logs WHERE isSynced = 0")
    suspend fun getUnsyncedLogs(): List<CallLog>

    @Query("DELETE FROM call_logs")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM call_logs")
    suspend fun getCount(): Int
}
