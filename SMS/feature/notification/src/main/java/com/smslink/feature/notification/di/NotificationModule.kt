package com.smslink.feature.notification.di

import android.content.Context
import com.smslink.core.database.NotificationDao
import com.smslink.core.database.SmsLinkDatabase
import com.smslink.feature.device.DeviceManager
import com.smslink.feature.notification.NotificationDisplayManager
import com.smslink.feature.notification.NotificationRepository
import com.smslink.feature.notification.NotificationSyncManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 通知模块依赖注入
 */
@Module
@InstallIn(SingletonComponent::class)
object NotificationModule {

    @Provides
    @Singleton
    fun provideNotificationDao(
        @ApplicationContext context: Context
    ): NotificationDao {
        return SmsLinkDatabase.getInstance(context).notificationDao()
    }

    @Provides
    @Singleton
    fun provideNotificationRepository(
        notificationDao: NotificationDao
    ): NotificationRepository {
        return NotificationRepository(notificationDao)
    }

    @Provides
    @Singleton
    fun provideNotificationSyncManager(
        deviceManager: DeviceManager,
        notificationRepository: NotificationRepository,
        notificationDisplayManager: NotificationDisplayManager
    ): NotificationSyncManager {
        return NotificationSyncManager(
            deviceManager,
            notificationRepository,
            notificationDisplayManager
        )
    }
}
