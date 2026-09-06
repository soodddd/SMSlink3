package com.smslink

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.smslink.ui.shell.AppShell
import com.smslink.ui.theme.SmsLinkTheme
import com.smslink.core.permission.EnhancedPermissionManager
import com.smslink.network.connection.ConnectionPolicyStore
import com.smslink.sms.SmsSyncService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * 主 Activity
 * 应用的入口点
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var permissionManager: EnhancedPermissionManager

    private companion object {
        const val EXTRA_DEBUG_PAIR_CODE = "debug_pair_code"
        const val EXTRA_DEBUG_PAIR_CODE_B64 = "debug_pair_code_b64"
    }

    private var debugPairCode by mutableStateOf<String?>(null)
    private var darkTheme by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionManager.setupPermissionLaunchers(this)
        darkTheme = getSharedPreferences(
            ConnectionPolicyStore.PREFERENCES_NAME,
            MODE_PRIVATE
        ).getBoolean(ConnectionPolicyStore.KEY_DARK_THEME, false)
        debugPairCode = resolveDebugPairCode(intent)
        setContent {
            SmsLinkTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppShell(debugPairCode = debugPairCode)
                }
            }
        }

        // Starting from a visible activity is allowed on Android 12+ and keeps
        // the content observer alive after the UI leaves the foreground.
        runCatching { SmsSyncService.start(this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        debugPairCode = resolveDebugPairCode(intent)
    }

    private fun resolveDebugPairCode(intent: Intent?): String? {
        val base64Value = intent?.getStringExtra(EXTRA_DEBUG_PAIR_CODE_B64)
        if (!base64Value.isNullOrBlank()) {
            return runCatching {
                String(
                    Base64.getUrlDecoder().decode(base64Value),
                    StandardCharsets.UTF_8
                )
            }.getOrNull()
        }

        return intent?.getStringExtra(EXTRA_DEBUG_PAIR_CODE)
    }
}
