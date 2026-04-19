package com.smslink.feature.device.di

import android.content.Context
import com.smslink.core.database.DeviceDao
import com.smslink.core.database.FileTransferDao
import com.smslink.core.database.SmsLinkDatabase
import com.smslink.core.preferences.DevicePreferences
import com.smslink.feature.device.DeviceManager
import com.smslink.feature.device.DeviceRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 设备模块依赖注入
 */
@Module
@InstallIn(SingletonComponent::class)
object DeviceModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SmsLinkDatabase =
        SmsLinkDatabase.getInstance(context)

    @Provides
    fun provideDeviceDao(database: SmsLinkDatabase): DeviceDao =
        database.deviceDao()

    @Provides
    fun provideFileTransferDao(database: SmsLinkDatabase): FileTransferDao =
        database.fileTransferDao()

    @Provides
    @Singleton
    fun provideDeviceRepository(deviceDao: DeviceDao): DeviceRepository =
        DeviceRepository(deviceDao)

    @Provides
    @Singleton
    fun provideDeviceManager(
        @ApplicationContext context: Context,
        repository: DeviceRepository,
        devicePreferences: DevicePreferences
    ): DeviceManager = DeviceManager(context, repository, devicePreferences)
}
