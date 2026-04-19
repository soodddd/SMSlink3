package com.smslink.di

import com.smslink.device.pairing.ImprovedPairingManager
import com.smslink.device.wifi.WifiNetworkDiscovery
import com.smslink.notification.NotificationInteractionHandler
import com.smslink.sync.CatchupSyncManager
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 新增功能模块的依赖注入
 * 注意：不再绑定IPermissionManager，避免与AppModule冲突
 */
@Module
@InstallIn(SingletonComponent::class)
object EnhancedFeaturesModule {
    // 这些类通过@Inject构造函数自动提供，不需要显式@Provides
    // Dagger Hilt会自动处理它们的依赖注入
}
