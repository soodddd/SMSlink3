package com.smslink.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.smslink.call.ui.CallHistoryScreen
import com.smslink.device.ui.DeviceListScreen
import com.smslink.file.ui.FileTransferScreen
import com.smslink.notification.ui.NotificationListScreen
import com.smslink.notification.ui.NotificationSettingsScreen
import com.smslink.sms.ui.SmsListScreen
import com.smslink.ui.connect.ConnectScreen
import com.smslink.ui.diagnostic.DiagnosticScreen
import com.smslink.ui.settings.PermissionGuideScreen
import com.smslink.ui.settings.SettingsScreen

@Composable
fun AppNavigation(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    startDestination: String = Screen.Devices.route,
    debugPairCode: String? = null
) {
    var selectedDeviceId by remember { mutableStateOf<String?>(null) }

    NavHost(
        navController = navController,
        modifier = modifier,
        startDestination = startDestination
    ) {
        composable(Screen.Devices.route) {
            DeviceListScreen(
                debugPairCode = debugPairCode,
                onDeviceClick = { device -> selectedDeviceId = device.id }
            )
        }

        composable(Screen.Notifications.route) {
            NotificationListScreen(
                onSettingsClick = { navController.navigate(Screen.NotificationSettings.route) },
                onNotificationClick = { }
            )
        }

        composable(Screen.Connect.route) {
            ConnectScreen()
        }

        composable(Screen.Messages.route) {
            SmsListScreen()
        }

        composable(Screen.Files.route) {
            FileTransferScreen(deviceId = selectedDeviceId ?: "")
        }

        composable(Screen.Calls.route) {
            CallHistoryScreen()
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateToPermissions = {
                    navController.navigate(Screen.Permissions.route)
                },
                onNavigateToNotificationSettings = {
                    navController.navigate(Screen.NotificationSettings.route)
                },
                onNavigateToDiagnostic = {
                    navController.navigate(Screen.Diagnostic.route)
                }
            )
        }

        composable(Screen.NotificationSettings.route) {
            NotificationSettingsScreen(
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(Screen.Permissions.route) {
            PermissionGuideScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Diagnostic.route) {
            DiagnosticScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}

sealed class Screen(val route: String) {
    data object Devices : Screen("devices")
    data object Notifications : Screen("notifications")
    data object Connect : Screen("connect")
    data object Messages : Screen("messages")
    data object Files : Screen("files")
    data object Calls : Screen("calls")
    data object Settings : Screen("settings")
    data object NotificationSettings : Screen("notification-settings")
    data object Permissions : Screen("permissions")
    data object Diagnostic : Screen("diagnostic")
}
