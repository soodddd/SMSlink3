package com.smslink.sms.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smslink.sms.PermissionResult
import com.smslink.sms.SmsPermissionHelper
import com.smslink.sms.SmsViewModel
import com.smslink.ui.theme.SmsLinkTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 短信功能示例 Activity
 * 展示如何集成和使用短信模块
 */
@AndroidEntryPoint
class SmsExampleActivity : ComponentActivity() {

    @Inject
    lateinit var permissionHelper: SmsPermissionHelper

    private var currentScreen by mutableStateOf(Screen.MessageList)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            SmsLinkTheme {
                SmsExampleApp(
                    currentScreen = currentScreen,
                    onNavigate = { screen -> currentScreen = screen },
                    onRequestPermissions = {
                        permissionHelper.requestSmsPermissions(
                            this,
                            SmsPermissionHelper.SMS_PERMISSION_REQUEST_CODE
                        )
                    }
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        val result = permissionHelper.onPermissionsResult(
            requestCode,
            permissions,
            grantResults,
            SmsPermissionHelper.SMS_PERMISSION_REQUEST_CODE
        )

        when (result) {
            is PermissionResult.AllGranted -> {
                // 权限已授予，刷新界面
                recreate()
            }
            is PermissionResult.AllDenied -> {
                // 所有权限被拒绝
                showPermissionDeniedDialog()
            }
            is PermissionResult.PartiallyGranted -> {
                // 部分权限被授予
                showPartialPermissionDialog(result.denied)
            }
            is PermissionResult.NotHandled -> {
                // 不是我们的请求
            }
        }
    }

    private fun showPermissionDeniedDialog() {
        // TODO: 显示权限被拒绝的对话框
    }

    private fun showPartialPermissionDialog(deniedPermissions: List<String>) {
        // TODO: 显示部分权限被拒绝的对话框
    }
}

/**
 * 屏幕枚举
 */
enum class Screen {
    MessageList,
    ComposeMessage
}

/**
 * 短信示例应用
 */
@Composable
fun SmsExampleApp(
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit,
    onRequestPermissions: () -> Unit
) {
    when (currentScreen) {
        Screen.MessageList -> {
            SmsListScreen(
                onMessageClick = { /* 处理消息点击 */ },
                onComposeClick = { onNavigate(Screen.ComposeMessage) }
            )
        }
        Screen.ComposeMessage -> {
            ComposeMessageScreen(
                onNavigateBack = { onNavigate(Screen.MessageList) }
            )
        }
    }
}

/**
 * 权限请求界面
 */
@Composable
fun PermissionRequestScreen(
    onRequestPermissions: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "SMS Permissions Required",
                style = MaterialTheme.typography.headlineMedium
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "This app needs SMS permissions to read, send, and receive messages.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onRequestPermissions,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Grant Permissions")
            }
        }
    }
}
