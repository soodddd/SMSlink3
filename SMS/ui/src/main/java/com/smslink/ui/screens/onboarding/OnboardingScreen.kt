package com.smslink.ui.screens.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.smslink.feature.device.DeviceRole

/**
 * 引导流程页面
 */
@Composable
fun OnboardingScreen(
    currentStep: OnboardingStep,
    onNextStep: () -> Unit,
    onRoleSelected: (DeviceRole) -> Unit,
    onPermissionsGranted: () -> Unit,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        when (currentStep) {
            OnboardingStep.WELCOME -> {
                WelcomeStep(onNext = onNextStep)
            }
            OnboardingStep.ROLE_SELECTION -> {
                RoleSelectionStep(onRoleSelected = onRoleSelected)
            }
            OnboardingStep.PERMISSIONS -> {
                PermissionsStep(onPermissionsGranted = onPermissionsGranted)
            }
            OnboardingStep.PAIRING -> {
                PairingIntroStep(onComplete = onComplete)
            }
        }
    }
}

@Composable
private fun WelcomeStep(
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically)
    ) {
        Text(
            text = "欢迎使用",
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center
        )

        Text(
            text = "SMS-Link",
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )

        Text(
            text = "在手机和电脑之间无缝同步通知、通话和文件",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("开始使用", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun RoleSelectionStep(
    onRoleSelected: (DeviceRole) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically)
    ) {
        Text(
            text = "选择设备角色",
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center
        )

        Text(
            text = "请选择此设备的角色",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            onClick = { onRoleSelected(DeviceRole.PRIMARY) },
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "主设备（手机）",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = "提供通知、通话等功能",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Card(
            onClick = { onRoleSelected(DeviceRole.SECONDARY) },
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "副设备（电脑）",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = "接收通知、控制通话",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PermissionsStep(
    onPermissionsGranted: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically)
    ) {
        Text(
            text = "授予权限",
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center
        )

        Text(
            text = "SMS-Link 需要以下权限才能正常工作",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PermissionItem(
                title = "通知访问",
                description = "读取和同步通知"
            )
            PermissionItem(
                title = "电话权限",
                description = "管理通话功能"
            )
            PermissionItem(
                title = "存储权限",
                description = "保存和传输文件"
            )
            PermissionItem(
                title = "网络权限",
                description = "与其他设备通信"
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onPermissionsGranted,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("授予权限", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun PermissionItem(
    title: String,
    description: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PairingIntroStep(
    onComplete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically)
    ) {
        Text(
            text = "准备就绪",
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center
        )

        Text(
            text = "现在可以开始配对设备了",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "配对步骤：",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "1. 确保两台设备在同一网络",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "2. 在主设备上生成配对码",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "3. 在副设备上输入配对码",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "4. 等待配对完成",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onComplete,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("开始配对", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * 引导步骤
 */
enum class OnboardingStep {
    WELCOME,
    ROLE_SELECTION,
    PERMISSIONS,
    PAIRING
}
