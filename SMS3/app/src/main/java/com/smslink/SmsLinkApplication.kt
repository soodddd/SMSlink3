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
        // 初始化连接管理器和文件传输管理器
        // 注意：这里只是确保依赖注入完成，实际的启动逻辑在各自的服务中
    }
}
