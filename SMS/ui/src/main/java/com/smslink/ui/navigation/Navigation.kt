package com.smslink.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 主导航容器
 */
@Composable
fun MainNavigation(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    content: @Composable (String) -> Unit
) {
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationItem.entries.forEach { item ->
                    NavigationBarItem(
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                        selected = currentRoute == item.route,
                        onClick = { onNavigate(item.route) }
                    )
                }
            }
        }
    ) { paddingValues ->
        androidx.compose.foundation.layout.Box(
            modifier = androidx.compose.ui.Modifier.padding(paddingValues)
        ) {
            content(currentRoute)
        }
    }
}

/**
 * 导航项
 */
enum class NavigationItem(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    HOME("home", "首页", Icons.Default.Home),
    TRANSFER("transfer", "传输", Icons.Default.SwapHoriz),
    NOTIFICATIONS("notifications", "通知", Icons.Default.Notifications),
    SETTINGS("settings", "设置", Icons.Default.Settings)
}

/**
 * 路由定义
 */
object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val PAIRING = "pairing"
    const val TRANSFER = "transfer"
    const val NOTIFICATIONS = "notifications"
    const val SETTINGS = "settings"
    const val INCOMING_CALL = "incoming_call"
    const val ONGOING_CALL = "ongoing_call"
}
