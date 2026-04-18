package com.smslink.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.smslink.core.model.Message
import com.smslink.core.model.MessageType
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: Message)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<Message>)

    @Update
    suspend fun update(message: Message)

    @Delete
    suspend fun delete(message: Message)

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getById(messageId: String): Message?

    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT :limit")
    fun getAll(limit: Int): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE threadId = :threadId ORDER BY timestamp DESC")
    fun getByThreadId(threadId: String): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE address = :address ORDER BY timestamp DESC")
    fun getByAddress(address: String): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE type = :type ORDER BY timestamp DESC")
    fun getByType(type: MessageType): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE type = 'SENT' ORDER BY timestamp DESC")
    fun getSentMessages(): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE type = 'INBOX' ORDER BY timestamp DESC")
    fun getReceivedMessages(): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE read = 0 ORDER BY timestamp DESC")
    fun getUnreadMessages(): Flow<List<Message>>

    @Query("SELECT COUNT(*) FROM messages WHERE read = 0")
    fun getUnreadCount(): Flow<Int>

    @Query("UPDATE messages SET read = 1 WHERE id = :messageId")
    suspend fun markAsRead(messageId: String)

    @Query("UPDATE messages SET read = 1 WHERE threadId = :threadId")
    suspend fun markThreadAsRead(threadId: String)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteById(messageId: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}
