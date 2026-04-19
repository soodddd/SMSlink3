package com.smslink

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.smslink.ui.navigation.MainNavigation
import com.smslink.ui.navigation.Routes
import com.smslink.ui.screens.home.HomeScreen
import com.smslink.ui.screens.notifications.NotificationsScreen
import com.smslink.ui.screens.onboarding.OnboardingScreen
import com.smslink.ui.screens.pairing.PairingScreen
import com.smslink.ui.screens.settings.SettingsScreen
import com.smslink.ui.screens.transfer.TransferScreen
import com.smslink.ui.theme.SmsLinkTheme
import com.smslink.ui.viewmodel.AppPreferencesViewModel
import com.smslink.ui.viewmodel.HomeViewModel
import com.smslink.ui.viewmodel.NotificationsViewModel
import com.smslink.ui.viewmodel.OnboardingViewModel
import com.smslink.ui.viewmodel.PairingViewModel
import com.smslink.ui.viewmodel.SettingsViewModel
import com.smslink.ui.viewmodel.TransferViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SmsLinkApp()
        }
    }
}

@Composable
fun SmsLinkApp(
    appPreferencesViewModel: AppPreferencesViewModel = hiltViewModel(),
    onboardingViewModel: OnboardingViewModel = hiltViewModel()
) {
    var currentRoute by remember { mutableStateOf(Routes.HOME) }
    val showOnboarding by appPreferencesViewModel.shouldShowOnboarding.collectAsState()
    val isDarkTheme by appPreferencesViewModel.isDarkTheme.collectAsState()
    val onboardingStep by onboardingViewModel.currentStep.collectAsState()

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    SmsLinkTheme(darkTheme = isDarkTheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            when {
                showOnboarding -> {
                    OnboardingScreen(
                        currentStep = onboardingStep,
                        onNextStep = { onboardingViewModel.nextStep() },
                        onRoleSelected = { role -> onboardingViewModel.selectRole(role) },
                        onPermissionsGranted = {
                            onboardingViewModel.requestNotificationPermissions()
                            onboardingViewModel.checkPermissionsAndProceed()
                        },
                        onComplete = {
                            appPreferencesViewModel.markOnboardingCompleted()
                            currentRoute = Routes.PAIRING
                        }
                    )
                }

                currentRoute == Routes.PAIRING -> {
                    val viewModel: PairingViewModel = hiltViewModel()
                    val uiState by viewModel.uiState.collectAsState()

                    PairingScreen(
                        uiState = uiState,
                        onBack = { currentRoute = Routes.HOME },
                        onGenerateCode = { viewModel.generateCode() },
                        onEnterCode = { code -> viewModel.enterCode(code) },
                        onDeviceSelected = { deviceId -> viewModel.selectDevice(deviceId) },
                        onCancel = { viewModel.cancel() }
                    )
                }

                else -> {
                    MainNavigation(
                        currentRoute = currentRoute,
                        onNavigate = { route -> currentRoute = route }
                    ) { route ->
                        when (route) {
                            Routes.HOME -> {
                                val viewModel: HomeViewModel = hiltViewModel()
                                val uiState by viewModel.uiState.collectAsState()

                                HomeScreen(
                                    uiState = uiState,
                                    onAddDevice = { currentRoute = Routes.PAIRING },
                                    onDeviceClick = { _ -> },
                                    onRefresh = { viewModel.refresh() }
                                )
                            }

                            Routes.TRANSFER -> {
                                val viewModel: TransferViewModel = hiltViewModel()
                                val uiState by viewModel.uiState.collectAsState()

                                TransferScreen(
                                    uiState = uiState,
                                    onAddTransfer = { },
                                    onTransferClick = { _ -> },
                                    onCallClick = { _ -> },
                                    onAcceptTransfer = { transferId ->
                                        viewModel.acceptIncomingTransfer(transferId)
                                    },
                                    onRejectTransfer = { transferId ->
                                        viewModel.rejectIncomingTransfer(transferId)
                                    }
                                )
                            }

                            Routes.NOTIFICATIONS -> {
                                val viewModel: NotificationsViewModel = hiltViewModel()
                                val uiState by viewModel.uiState.collectAsState()

                                NotificationsScreen(
                                    uiState = uiState,
                                    onSearch = { query -> viewModel.search(query) },
                                    onFilterDevice = { deviceId -> viewModel.filterByDevice(deviceId) },
                                    onNotificationClick = { _ -> }
                                )
                            }

                            Routes.SETTINGS -> {
                                val viewModel: SettingsViewModel = hiltViewModel()
                                val uiState by viewModel.uiState.collectAsState()

                                SettingsScreen(
                                    uiState = uiState,
                                    onRoleSwitchClick = { viewModel.toggleRole() },
                                    onNotificationSettingsClick = { },
                                    onFileTransferSettingsClick = { },
                                    onPermissionsClick = { },
                                    onAboutClick = { },
                                    onThemeChange = { isDark -> viewModel.setDarkTheme(isDark) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
