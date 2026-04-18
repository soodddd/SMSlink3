package com.smslink.core.di

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import com.smslink.call.CallManagerImpl
import com.smslink.call.ICallManager
import com.smslink.core.database.AppDatabase
import com.smslink.core.database.dao.CallLogDao
import com.smslink.core.database.dao.DeviceDao
import com.smslink.core.database.dao.MessageDao
import com.smslink.core.database.dao.NotificationDao
import com.smslink.core.log.ILogger
import com.smslink.core.log.LoggerImpl
import com.smslink.core.permission.IPermissionManager
import com.smslink.core.permission.PermissionManagerImpl
import com.smslink.device.IDeviceManager
import com.smslink.device.DeviceManagerImpl
import com.smslink.file.IFileTransferManager
import com.smslink.file.FileTransferManagerImpl
import com.smslink.file.data.FileTransferDao
import com.smslink.network.IConnectionManager
import com.smslink.network.INetworkManager
import com.smslink.network.NetworkManagerImpl
import com.smslink.network.connection.ConnectionFactory
import com.smslink.network.connection.ConnectionManagerImpl
import com.smslink.network.connection.LinkSelector
import com.smslink.network.encryption.EncryptionImpl
import com.smslink.network.encryption.IEncryption
import com.smslink.network.transport.IMessageTransport
import com.smslink.network.transport.MessageTransportImpl
import com.smslink.notification.INotificationManager
import com.smslink.notification.NotificationManagerImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt 依赖注入模块
 * 提供应用级别的单例依赖
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * 提供应用数据库
     */
    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    /**
     * 提供设备 DAO
     */
    @Provides
    @Singleton
    fun provideDeviceDao(database: AppDatabase): DeviceDao {
        return database.deviceDao()
    }

    /**
     * 提供消息 DAO
     */
    @Provides
    @Singleton
    fun provideMessageDao(database: AppDatabase): MessageDao {
        return database.messageDao()
    }

    /**
     * 提供通知 DAO
     */
    @Provides
    @Singleton
    fun provideNotificationDao(database: AppDatabase): NotificationDao {
        return database.notificationDao()
    }

    /**
     * 提供日志接口
     */
    @Provides
    @Singleton
    fun provideLogger(impl: LoggerImpl): ILogger {
        return impl
    }

    /**
     * 提供权限管理器
     */
    @Provides
    @Singleton
    fun providePermissionManager(impl: PermissionManagerImpl): IPermissionManager {
        return impl
    }

    /**
     * 提供设备管理器
     */
    @Provides
    @Singleton
    fun provideDeviceManager(impl: DeviceManagerImpl): IDeviceManager {
        return impl
    }

    /**
     * 提供文件传输 DAO
     */
    @Provides
    @Singleton
    fun provideFileTransferDao(database: AppDatabase): FileTransferDao {
        return database.fileTransferDao()
    }

    /**
     * 提供文件传输管理器
     */
    @Provides
    @Singleton
    fun provideFileTransferManager(impl: FileTransferManagerImpl): IFileTransferManager {
        return impl
    }

    /**
     * 提供加密接口
     */
    @Provides
    @Singleton
    fun provideEncryption(impl: EncryptionImpl): IEncryption {
        return impl
    }

    /**
     * 提供链路选择器
     */
    @Provides
    @Singleton
    fun provideLinkSelector(
        @ApplicationContext context: Context,
        logger: ILogger
    ): LinkSelector {
        return LinkSelector(context, logger)
    }

    /**
     * 提供连接工厂
     */
    @Provides
    @Singleton
    fun provideConnectionFactory(
        deviceDao: DeviceDao,
        encryption: IEncryption,
        logger: ILogger,
        linkSelector: LinkSelector
    ): ConnectionFactory {
        return ConnectionFactory(deviceDao, encryption, logger, linkSelector)
    }

    /**
     * 提供连接管理器
     */
    @Provides
    @Singleton
    fun provideConnectionManager(impl: ConnectionManagerImpl): IConnectionManager {
        return impl
    }

    /**
     * 提供消息传输接口
     */
    @Provides
    @Singleton
    fun provideMessageTransport(impl: MessageTransportImpl): IMessageTransport {
        return impl
    }

    /**
     * 提供网络管理器
     */
    @Provides
    @Singleton
    fun provideNetworkManager(impl: NetworkManagerImpl): INetworkManager {
        return impl
    }

    /**
     * 提供通话记录 DAO
     */
    @Provides
    @Singleton
    fun provideCallLogDao(database: AppDatabase): CallLogDao {
        return database.callLogDao()
    }

    /**
     * 提供通话管理器
     */
    @Provides
    @Singleton
    fun provideCallManager(impl: CallManagerImpl): ICallManager {
        return impl
    }

    /**
     * 提供通知管理器
     */
    @Provides
    @Singleton
    fun provideNotificationManager(impl: NotificationManagerImpl): INotificationManager {
        return impl
    }

    /**
     * 提供 SharedPreferences
     */
    @Provides
    @Singleton
    fun provideSharedPreferences(
        @ApplicationContext context: Context
    ): SharedPreferences {
        return context.getSharedPreferences("smslink_prefs", Context.MODE_PRIVATE)
    }
}
