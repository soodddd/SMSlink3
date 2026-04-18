package com.smslink.file.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.smslink.core.model.TransferState
import kotlinx.coroutines.flow.Flow

@Dao
interface FileTransferDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transfer: FileTransferEntity)

    @Update
    suspend fun update(transfer: FileTransferEntity)

    @Query("SELECT * FROM file_transfers WHERE id = :transferId")
    suspend fun getById(transferId: String): FileTransferEntity?

    @Query("SELECT * FROM file_transfers ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getAll(limit: Int): List<FileTransferEntity>

    @Query("SELECT * FROM file_transfers WHERE state IN ('PENDING', 'TRANSFERRING') ORDER BY timestamp DESC")
    fun getActiveTransfers(): Flow<List<FileTransferEntity>>

    @Query("SELECT * FROM file_transfers WHERE state = :state ORDER BY timestamp DESC")
    suspend fun getByState(state: TransferState): List<FileTransferEntity>

    @Query("SELECT * FROM file_transfers WHERE deviceId = :deviceId ORDER BY timestamp DESC")
    suspend fun getByDevice(deviceId: String): List<FileTransferEntity>

    @Query("DELETE FROM file_transfers WHERE id = :transferId")
    suspend fun delete(transferId: String)

    @Query("DELETE FROM file_transfers WHERE state = :state")
    suspend fun deleteByState(state: TransferState)

    @Query("SELECT COUNT(*) FROM file_transfers")
    suspend fun getCount(): Int

    @Query("DELETE FROM file_transfers")
    suspend fun deleteAll()
}
