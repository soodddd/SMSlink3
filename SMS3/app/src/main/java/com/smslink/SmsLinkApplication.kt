package com.smslink

import android.app.Application
import com.smslink.call.ICallManager
import com.smslink.file.IFileTransferManager
import com.smslink.network.IConnectionManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * SMS-link 应用程序类
 * 使用 Hilt 进行依赖注入
 */
@HiltAndroidApp
class SmsLinkApplication : Application() {

    @Inject
    lateinit var connectionManager: IConnectionManager

    @Inject
    lateinit var fileTransferManager: IFileTransferManager

    @Inject
    lateinit var callManager: ICallManager

    override fun onCreate() {
        super.onCreate()
        // Telecom/phone-state callbacks are process-lifetime integrations. Start
        // them here so call mirroring does not depend on opening Call History.
        runCatching { callManager.startListening() }
            .onFailure { /* Devices without telephony are supported. */ }
    }
}
