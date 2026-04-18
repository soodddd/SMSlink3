package com.smslink

import android.app.Application
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

    override fun onCreate() {
        super.onCreate()
        connectionManager.receiveData()
        fileTransferManager.getActiveTransfers()
    }
}
