package com.smslink.app

import android.content.Context
import android.net.Uri
import android.util.Log
import com.smslink.feature.call.CallManager
import com.smslink.feature.device.DeviceManager
import com.smslink.feature.transfer.FileTransferManager
import com.smslink.network.protocol.Message
import com.smslink.network.protocol.MessageType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private fun ByteArray.indexOf(byte: Byte, startIndex: Int = 0): Int {
    for (i in startIndex until size) {
        if (this[i] == byte) return i
    }
    return -1
}

@Singleton
class MessageRouter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceManager: DeviceManager,
    private val callManager: CallManager,
    private val fileTransferManager: FileTransferManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun initialize() {
        registerCallHandlers()
        registerFileTransferHandlers()
        setupMessageSenders()
    }

    private fun registerCallHandlers() {
        deviceManager.registerMessageHandler(MessageType.CALL_INCOMING) { message ->
            val payload = String(message.payload)
            val parts = payload.split("|")
            if (parts.size >= 3) {
                val callId = parts[0]
                val phoneNumber = parts[1]
                val contactName = parts.getOrNull(2)
                callManager.handleRemoteIncomingCall(callId, phoneNumber, contactName)
            }
        }

        deviceManager.registerMessageHandler(MessageType.CALL_AUDIO_DATA) { message ->
            callManager.receiveAudioData(message.payload)
        }

        deviceManager.registerMessageHandler(MessageType.CALL_END) { message ->
            val payload = String(message.payload)
            val parts = payload.split("|")
            if (parts.size >= 2) {
                val callId = parts[0]
                val duration = parts[1].toLongOrNull() ?: 0
                callManager.handleRemoteCallEnd(callId, duration)
            }
        }
    }

    private fun registerFileTransferHandlers() {
        deviceManager.registerMessageHandler(MessageType.FILE_TRANSFER_REQUEST) { message ->
            val payload = String(message.payload)
            val parts = payload.split("|")
            if (parts.size >= 6) {
                val transferId = parts[0]
                val relativePath = Uri.decode(parts[1])
                val fileSize = parts[2].toLongOrNull() ?: 0L
                val totalSize = parts[3].toLongOrNull() ?: fileSize
                val isFolder = parts.getOrNull(4) == "1"
                val fileCount = parts.getOrNull(5)?.toIntOrNull() ?: 1
                val remoteDevice = deviceManager.pairedDevice.value
                scope.launch {
                    fileTransferManager.prepareIncomingTransfer(
                        transferId = transferId,
                        deviceId = remoteDevice?.deviceId ?: "remote",
                        deviceName = remoteDevice?.deviceName ?: "Remote Device",
                        relativePath = relativePath,
                        fileSize = fileSize,
                        totalSize = totalSize,
                        isFolder = isFolder,
                        fileCount = fileCount
                    )
                }
            }
        }

        deviceManager.registerMessageHandler(MessageType.FILE_TRANSFER_DATA) { message ->
            val payload = message.payload
            val firstPipe = payload.indexOf('|'.code.toByte())
            if (firstPipe > 0) {
                val secondPipe = payload.indexOf('|'.code.toByte(), firstPipe + 1)
                if (secondPipe > 0) {
                    val thirdPipe = payload.indexOf('|'.code.toByte(), secondPipe + 1)
                    if (thirdPipe > 0) {
                        val fourthPipe = payload.indexOf('|'.code.toByte(), thirdPipe + 1)
                        if (fourthPipe > 0) {
                            val transferId = String(payload.copyOfRange(0, firstPipe))
                            val relativePath = Uri.decode(String(payload.copyOfRange(firstPipe + 1, secondPipe)))
                            val offset = String(payload.copyOfRange(secondPipe + 1, thirdPipe)).toLongOrNull() ?: 0L
                            val fileSize = String(payload.copyOfRange(thirdPipe + 1, fourthPipe)).toLongOrNull() ?: 0L
                            val chunk = payload.copyOfRange(fourthPipe + 1, payload.size)
                            scope.launch {
                                fileTransferManager.receiveFileChunk(
                                    transferId = transferId,
                                    relativePath = relativePath,
                                    chunk = chunk,
                                    offset = offset,
                                    fileSize = fileSize
                                )
                            }
                        }
                    }
                }
            }
        }

        deviceManager.registerMessageHandler(MessageType.FILE_TRANSFER_PROGRESS) { message ->
            val payload = String(message.payload)
            Log.d("MessageRouter", "Received transfer progress: $payload")
        }

        deviceManager.registerMessageHandler(MessageType.FILE_TRANSFER_COMPLETE) { message ->
            val transferId = String(message.payload).substringBefore("|").trim()
            if (transferId.isNotEmpty()) {
                scope.launch {
                    fileTransferManager.completeIncomingTransfer(transferId)
                }
            }
        }

        deviceManager.registerMessageHandler(MessageType.FILE_TRANSFER_CANCEL) { message ->
            val payload = String(message.payload)
            val transferId = payload.substringBefore("|").trim()
            val reason = payload.substringAfter("|", "").takeIf { it.isNotBlank() }?.let(Uri::decode)
            if (transferId.isNotEmpty()) {
                scope.launch {
                    fileTransferManager.cancelIncomingTransfer(transferId, reason)
                }
            }
        }

        deviceManager.registerMessageHandler(MessageType.FILE_TRANSFER_ACCEPT) { message ->
            val transferId = String(message.payload).substringBefore("|").trim()
            if (transferId.isNotEmpty()) {
                fileTransferManager.handleTransferAccepted(transferId)
            }
            Log.d("MessageRouter", "Transfer accepted: ${String(message.payload)}")
        }

        deviceManager.registerMessageHandler(MessageType.FILE_TRANSFER_REJECT) { message ->
            val payload = String(message.payload)
            val transferId = payload.substringBefore("|").trim()
            val reason = payload.substringAfter("|", "").takeIf { it.isNotBlank() }?.let(Uri::decode)
            if (transferId.isNotEmpty()) {
                fileTransferManager.handleTransferRejected(transferId, reason)
            }
            Log.d("MessageRouter", "Transfer rejected: ${String(message.payload)}")
        }
    }

    private fun setupMessageSenders() {
        callManager.onSendMessage = { message ->
            scope.launch {
                runCatching {
                    deviceManager.sendMessage(message)
                }.onFailure { error ->
                    Log.e("MessageRouter", "Failed to send call message", error)
                }
            }
        }

        fileTransferManager.onSendMessage = { message ->
            scope.launch {
                runCatching {
                    deviceManager.sendMessage(message)
                }.onFailure { error ->
                    Log.e("MessageRouter", "Failed to send file transfer message", error)
                }
            }
        }
    }

    fun cleanup() {
        deviceManager.unregisterMessageHandler(MessageType.CALL_INCOMING)
        deviceManager.unregisterMessageHandler(MessageType.CALL_AUDIO_DATA)
        deviceManager.unregisterMessageHandler(MessageType.CALL_END)
        deviceManager.unregisterMessageHandler(MessageType.FILE_TRANSFER_REQUEST)
        deviceManager.unregisterMessageHandler(MessageType.FILE_TRANSFER_DATA)
        deviceManager.unregisterMessageHandler(MessageType.FILE_TRANSFER_PROGRESS)
        deviceManager.unregisterMessageHandler(MessageType.FILE_TRANSFER_COMPLETE)
        deviceManager.unregisterMessageHandler(MessageType.FILE_TRANSFER_CANCEL)
        deviceManager.unregisterMessageHandler(MessageType.FILE_TRANSFER_ACCEPT)
        deviceManager.unregisterMessageHandler(MessageType.FILE_TRANSFER_REJECT)

        callManager.onSendMessage = null
        fileTransferManager.onSendMessage = null
    }
}
