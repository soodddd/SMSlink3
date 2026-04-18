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
import dagger.hilt.android.AndroidEntryPoint
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * 主 Activity
 * 应用的入口点
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private companion object {
        const val EXTRA_DEBUG_PAIR_CODE = "debug_pair_code"
        const val EXTRA_DEBUG_PAIR_CODE_B64 = "debug_pair_code_b64"
    }

    private var debugPairCode by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        debugPairCode = resolveDebugPairCode(intent)
        setContent {
            SmsLinkTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppShell(debugPairCode = debugPairCode)
                }
            }
        }
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
