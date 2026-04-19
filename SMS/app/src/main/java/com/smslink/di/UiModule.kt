package com.smslink.di

import android.content.Context
import com.smslink.feature.device.DeviceManager
import com.smslink.ui.bridge.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * UI 模块依赖注入
 */
@Module
@InstallIn(SingletonComponent::class)
object UiModule {

    @Provides
    @Singleton
    fun provideDeviceBridge(
        @ApplicationContext context: Context,
        deviceManager: DeviceManager
    ): DeviceBridge {
        return DeviceBridge(context, deviceManager)
    }

    @Provides
    @Singleton
    fun provideNotificationBridge(
        notificationRepository: com.smslink.feature.notification.NotificationRepository
    ): NotificationBridge {
        return NotificationBridge(notificationRepository)
    }
}
