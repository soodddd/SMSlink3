package com.smslink.sms

import com.smslink.core.database.dao.MessageDao
import com.smslink.core.model.Message
import com.smslink.core.model.MessageType
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmsRepository @Inject constructor(
    private val messageDao: MessageDao
) {
    fun getAllMessages(limit: Int = 100): Flow<List<Message>> = messageDao.getAll(limit)

    fun getMessagesByThread(threadId: String): Flow<List<Message>> = messageDao.getByThreadId(threadId)

    fun getMessagesByAddress(address: String): Flow<List<Message>> = messageDao.getByAddress(address)

    fun getSentMessages(): Flow<List<Message>> = messageDao.getByType(MessageType.SENT)

    fun getReceivedMessages(): Flow<List<Message>> = messageDao.getByType(MessageType.INBOX)

    fun getUnreadMessages(): Flow<List<Message>> = messageDao.getUnreadMessages()

    fun getMessagesByType(type: MessageType): Flow<List<Message>> = messageDao.getByType(type)

    suspend fun getMessageById(messageId: String): Message? = messageDao.getById(messageId)

    suspend fun insertMessage(message: Message) {
        messageDao.insert(message)
    }

    suspend fun insertMessages(messages: List<Message>) {
        messageDao.insertAll(messages)
    }

    suspend fun updateMessage(message: Message) {
        messageDao.update(message)
    }

    suspend fun deleteMessage(message: Message) {
        messageDao.delete(message)
    }

    suspend fun deleteMessageById(messageId: String) {
        messageDao.deleteById(messageId)
    }

    suspend fun deleteAllMessages() {
        messageDao.deleteAll()
    }

    suspend fun markAsRead(messageId: String) {
        messageDao.markAsRead(messageId)
    }

    suspend fun markThreadAsRead(threadId: String) {
        messageDao.markThreadAsRead(threadId)
    }

    fun getUnreadCount(): Flow<Int> = messageDao.getUnreadCount()

    fun getConversations(): Flow<List<Conversation>> {
        throw NotImplementedError("Conversation list query not implemented yet")
    }
}

data class Conversation(
    val threadId: String,
    val address: String,
    val lastMessage: Message,
    val unreadCount: Int
)
