package com.smslink.sms.di

import android.content.Context
import com.smslink.core.database.dao.MessageDao
import com.smslink.core.log.ILogger
import com.smslink.device.IDeviceManager
import com.smslink.network.transport.IMessageTransport
import com.smslink.sms.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 短信模块依赖注入配置
 */
@Module
@InstallIn(SingletonComponent::class)
object SmsModule {

    @Provides
    @Singleton
    fun provideSmsManager(impl: SmsManagerImpl): ISmsManager = impl

    @Provides
    @Singleton
    fun provideSmsRepository(
        messageDao: MessageDao
    ): SmsRepository {
        return SmsRepository(messageDao)
    }

    @Provides
    @Singleton
    fun provideSmsContentObserver(
        @ApplicationContext context: Context,
        smsManager: SmsManagerImpl,
        logger: ILogger
    ): SmsContentObserver {
        return SmsContentObserver(context, smsManager, logger)
    }

}
