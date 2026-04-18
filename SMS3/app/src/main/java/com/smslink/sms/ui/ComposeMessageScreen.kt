package com.smslink.sms.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.smslink.sms.SendState
import com.smslink.sms.SmsViewModel

/**
 * 编写短信页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeMessageScreen(
    viewModel: SmsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {}
) {
    var recipient by remember { mutableStateOf("") }
    var messageBody by remember { mutableStateOf("") }
    val sendState by viewModel.sendState.collectAsState()

    LaunchedEffect(sendState) {
        when (sendState) {
            is SendState.Success -> {
                onNavigateBack()
                viewModel.resetSendState()
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Message") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            viewModel.sendMessage(recipient, messageBody)
                        },
                        enabled = recipient.isNotBlank() && messageBody.isNotBlank() && sendState !is SendState.Sending
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            OutlinedTextField(
                value = recipient,
                onValueChange = { recipient = it },
                label = { Text("To") },
                placeholder = { Text("Phone number") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = sendState !is SendState.Sending
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = messageBody,
                onValueChange = { messageBody = it },
                label = { Text("Message") },
                placeholder = { Text("Type a message") },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                enabled = sendState !is SendState.Sending
            )

            Spacer(modifier = Modifier.height(16.dp))

            when (sendState) {
                is SendState.Sending -> {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                is SendState.Error -> {
                    Text(
                        text = (sendState as SendState.Error).message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                else -> {}
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Character count: ${messageBody.length}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (messageBody.length > 160) {
                Text(
                    text = "This message will be sent as ${(messageBody.length / 160) + 1} parts",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
