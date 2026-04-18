package com.smslink.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.smslink.core.database.dao.CallLogDao
import com.smslink.core.database.dao.DeviceDao
import com.smslink.core.database.dao.MessageDao
import com.smslink.core.database.dao.NotificationDao
import com.smslink.core.model.AppNotification
import com.smslink.core.model.CallLog
import com.smslink.core.model.Device
import com.smslink.core.model.Message
import com.smslink.file.data.FileTransferDao
import com.smslink.file.data.FileTransferEntity

/**
 * 应用数据库
 * 使用 Room 持久化库管理本地数据
 */
@Database(
    entities = [
        Device::class,
        Message::class,
        AppNotification::class,
        FileTransferEntity::class,
        CallLog::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    /**
     * 设备 DAO
     */
    abstract fun deviceDao(): DeviceDao

    /**
     * 消息 DAO
     */
    abstract fun messageDao(): MessageDao

    /**
     * 通知 DAO
     */
    abstract fun notificationDao(): NotificationDao

    /**
     * 文件传输 DAO
     */
    abstract fun fileTransferDao(): FileTransferDao

    /**
     * 通话记录 DAO
     */
    abstract fun callLogDao(): CallLogDao

    companion object {
        const val DATABASE_NAME = "smslink_database"
    }
}
