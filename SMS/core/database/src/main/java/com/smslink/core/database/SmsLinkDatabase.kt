package com.smslink.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * SMS-Link 数据库
 */
@Database(
    entities = [
        DeviceEntity::class,
        NotificationEntity::class,
        FileTransferEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class SmsLinkDatabase : RoomDatabase() {

    abstract fun deviceDao(): DeviceDao
    abstract fun notificationDao(): NotificationDao
    abstract fun fileTransferDao(): FileTransferDao

    companion object {
        @Volatile
        private var INSTANCE: SmsLinkDatabase? = null

        fun getInstance(context: Context): SmsLinkDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SmsLinkDatabase::class.java,
                    "smslink_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
