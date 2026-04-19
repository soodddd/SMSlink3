package com.smslink

import android.app.Application
import android.util.Log
import com.smslink.app.MessageRouter
import com.smslink.feature.device.DeviceManager
import com.smslink.feature.device.DeviceRole
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class SmsLinkApplication : Application() {

    @Inject
    lateinit var deviceManager: DeviceManager

    @Inject
    lateinit var messageRouter: MessageRouter

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        Log.d("SmsLinkApp", "Application onCreate started")

        // 初始化 DeviceManager 和 MessageRouter（延迟到 Hilt 注入完成后）
        appScope.launch {
            try {
                Log.d("SmsLinkApp", "Initializing DeviceManager...")
                val currentRole = deviceManager.currentRole.first()
                Log.d("SmsLinkApp", "Current role: $currentRole")

                if (currentRole == DeviceRole.UNPAIRED) {
                    // 默认初始化为 SECONDARY（等待用户在 onboarding 中选择）
                    Log.d("SmsLinkApp", "Initializing as SECONDARY")
                    deviceManager.initialize(DeviceRole.SECONDARY)
                } else {
                    // 恢复之前的角色
                    Log.d("SmsLinkApp", "Restoring role: $currentRole")
                    deviceManager.initialize(currentRole)
                }
                Log.d("SmsLinkApp", "DeviceManager initialized successfully")

                // 初始化消息路由器
                Log.d("SmsLinkApp", "Initializing MessageRouter...")
                messageRouter.initialize()
                Log.d("SmsLinkApp", "MessageRouter initialized successfully")
            } catch (e: Exception) {
                Log.e("SmsLinkApp", "Failed to initialize", e)
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        // 清理资源
        messageRouter.cleanup()
    }
}
