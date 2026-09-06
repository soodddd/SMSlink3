package com.smslink.call

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.smslink.ui.theme.SmsLinkTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Minimal dial surface required when SMS-link is selected as the dialer. */
@AndroidEntryPoint
class DialActivity : ComponentActivity() {

    @Inject
    lateinit var callManager: ICallManager

    private var phoneNumber by mutableStateOf("")
    private var errorMessage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        phoneNumber = intent?.data?.schemeSpecificPart.orEmpty()
        render()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        phoneNumber = intent.data?.schemeSpecificPart.orEmpty()
        render()
    }

    private fun render() {
        setContent {
            SmsLinkTheme {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("拨号", style = MaterialTheme.typography.headlineSmall)
                    OutlinedTextField(
                        value = phoneNumber,
                        onValueChange = { phoneNumber = it; errorMessage = null },
                        label = { Text("电话号码") },
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            lifecycleScope.launch {
                                if (!callManager.makeCall(phoneNumber)) {
                                    errorMessage = "无法发起通话，请检查电话权限或默认电话应用设置"
                                } else {
                                    finish()
                                }
                            }
                        },
                        enabled = phoneNumber.isNotBlank()
                    ) {
                        Text("拨打")
                    }
                    TextButton(onClick = { finish() }) { Text("取消") }
                    errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}
