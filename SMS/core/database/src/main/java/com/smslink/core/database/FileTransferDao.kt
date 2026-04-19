package com.smslink.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 文件传输 DAO
 */
@Dao
interface FileTransferDao {

    /**
     * 插入文件传输记录
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transfer: FileTransferEntity)

    /**
     * 更新文件传输记录
     */
    @Update
    suspend fun update(transfer: FileTransferEntity)

    /**
     * 根据 ID 查询文件传输记录
     */
    @Query("SELECT * FROM file_transfers WHERE id = :transferId")
    suspend fun getById(transferId: String): FileTransferEntity?

    /**
     * 获取所有文件传输记录（按时间倒序）
     */
    @Query("SELECT * FROM file_transfers ORDER BY timestamp DESC")
    fun getAllTransfers(): Flow<List<FileTransferEntity>>

    /**
     * 根据状态查询文件传输记录
     */
    @Query("SELECT * FROM file_transfers WHERE status = :status ORDER BY timestamp DESC")
    fun getTransfersByStatus(status: String): Flow<List<FileTransferEntity>>

    /**
     * 根据设备 ID 查询文件传输记录
     */
    @Query("SELECT * FROM file_transfers WHERE deviceId = :deviceId ORDER BY timestamp DESC")
    fun getTransfersByDevice(deviceId: String): Flow<List<FileTransferEntity>>

    /**
     * 删除文件传输记录
     */
    @Query("DELETE FROM file_transfers WHERE id = :transferId")
    suspend fun delete(transferId: String)

    /**
     * 清空所有文件传输记录
     */
    @Query("DELETE FROM file_transfers")
    suspend fun deleteAll()

    /**
     * 更新传输进度
     */
    @Query("UPDATE file_transfers SET progress = :progress, transferredBytes = :transferredBytes, speed = :speed WHERE id = :transferId")
    suspend fun updateProgress(transferId: String, progress: Int, transferredBytes: Long, speed: Long)

    /**
     * 更新传输状态
     */
    @Query("UPDATE file_transfers SET status = :status, errorMessage = :errorMessage WHERE id = :transferId")
    suspend fun updateStatus(transferId: String, status: String, errorMessage: String?)
}
