package com.smslink.sms

import com.smslink.core.log.ILogger
import com.smslink.core.model.Device
import com.smslink.core.model.DeviceRole
import com.smslink.core.model.Message
import com.smslink.core.model.MessageType
import com.smslink.core.permission.IPermissionManager
import com.smslink.device.IDeviceManager
import com.smslink.sms.model.Conversation
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * SmsViewModel 单元测试
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmsViewModelTest {

    private lateinit var viewModel: SmsViewModel
    private lateinit var smsManager: SmsManagerImpl
    private lateinit var smsRepository: SmsRepository
    private lateinit var deviceManager: IDeviceManager
    private lateinit var permissionManager: IPermissionManager
    private lateinit var logger: ILogger

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        smsManager = mockk(relaxed = true)
        smsRepository = mockk(relaxed = true)
        deviceManager = mockk(relaxed = true)
        permissionManager = mockk(relaxed = true)
        logger = mockk(relaxed = true)

        // Mock permissions granted by default
        every { permissionManager.hasPermission(any()) } returns true

        // Mock empty connected devices
        every { deviceManager.getConnectedDevices() } returns flowOf(emptyList())

        // Mock empty messages
        coEvery { smsRepository.getAllMessages(any()) } returns flowOf(emptyList())

        // Mock empty conversations
        coEvery { smsManager.getConversations() } returns emptyList()

        // Mock new messages flow
        every { smsManager.observeNewMessages() } returns flowOf()

        viewModel = SmsViewModel(
            smsManager,
            smsRepository,
            deviceManager,
            permissionManager,
            logger
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `loadMessages should update messages state`() = runTest {
        // Given
        val expectedMessages = listOf(
            createTestMessage("1"),
            createTestMessage("2")
        )
        coEvery { smsRepository.getAllMessages(any()) } returns flowOf(expectedMessages)

        // When
        viewModel.loadMessages()

        // Then
        assertEquals(expectedMessages, viewModel.messages.value)
        assertIs<SmsUiState.Success>(viewModel.uiState.value)
    }

    @Test
    fun `loadMessages should show empty state when no messages`() = runTest {
        // Given
        coEvery { smsRepository.getAllMessages(any()) } returns flowOf(emptyList())

        // When
        viewModel.loadMessages()

        // Then
        assertTrue(viewModel.messages.value.isEmpty())
        assertIs<SmsUiState.Empty>(viewModel.uiState.value)
    }

    @Test
    fun `loadMessages should show error state on failure`() = runTest {
        // Given
        val errorMessage = "Database error"
        coEvery { smsRepository.getAllMessages(any()) } returns flow {
            throw Exception(errorMessage)
        }

        // When
        viewModel.loadMessages()

        // Then
        val state = viewModel.uiState.value
        assertIs<SmsUiState.Error>(state)
        assertTrue(state.message.contains(errorMessage))
    }

    @Test
    fun `loadConversations should update conversations state`() = runTest {
        // Given
        val expectedConversations = listOf(
            createTestConversation("1"),
            createTestConversation("2")
        )
        coEvery { smsManager.getConversations() } returns expectedConversations

        // When
        viewModel.loadConversations()

        // Then
        assertEquals(expectedConversations, viewModel.conversations.value)
        assertIs<SyncState.Success>(viewModel.syncState.value)
    }

    @Test
    fun `sendMessage should update send state to success`() = runTest {
        // Given
        val address = "+1234567890"
        val body = "Test message"
        coEvery { smsManager.sendMessage(address, body, null) } returns true

        // When
        viewModel.sendMessage(address, body)

        // Then
        assertIs<SendState.Success>(viewModel.sendState.value)
        coVerify { smsManager.sendMessage(address, body, null) }
    }

    @Test
    fun `sendMessage should update send state to error on failure`() = runTest {
        // Given
        val address = "+1234567890"
        val body = "Test message"
        coEvery { smsManager.sendMessage(address, body, null) } returns false

        // When
        viewModel.sendMessage(address, body)

        // Then
        val state = viewModel.sendState.value
        assertIs<SendState.Error>(state)
    }

    @Test
    fun `sendMessage should reject empty address`() = runTest {
        // When
        viewModel.sendMessage("", "Test message")

        // Then
        val state = viewModel.sendState.value
        assertIs<SendState.Error>(state)
        coVerify(exactly = 0) { smsManager.sendMessage(any(), any(), any()) }
    }

    @Test
    fun `sendMessage should reject empty body`() = runTest {
        // When
        viewModel.sendMessage("+1234567890", "")

        // Then
        val state = viewModel.sendState.value
        assertIs<SendState.Error>(state)
        coVerify(exactly = 0) { smsManager.sendMessage(any(), any(), any()) }
    }

    @Test
    fun `selectDevice should update selected device`() = runTest {
        // Given
        val device = createTestDevice("device1")

        // When
        viewModel.selectDevice(device)

        // Then
        assertEquals(device, viewModel.selectedDevice.value)
    }

    @Test
    fun `selectSimSlot should update selected sim slot`() = runTest {
        // Given
        val simSlot = 1

        // When
        viewModel.selectSimSlot(simSlot)

        // Then
        assertEquals(simSlot, viewModel.selectedSimSlot.value)
    }

    @Test
    fun `setViewMode should update view mode`() = runTest {
        // When
        viewModel.setViewMode(ViewMode.MESSAGES)

        // Then
        assertEquals(ViewMode.MESSAGES, viewModel.viewMode.value)
    }

    @Test
    fun `markAsRead should call sms manager`() = runTest {
        // Given
        val messageId = "test-id"
        coEvery { smsManager.markAsRead(messageId) } just Runs

        // When
        viewModel.markAsRead(messageId)

        // Then
        coVerify { smsManager.markAsRead(messageId) }
    }

    @Test
    fun `deleteMessage should call sms manager and reload messages`() = runTest {
        // Given
        val messageId = "test-id"
        coEvery { smsManager.deleteMessage(messageId) } just Runs
        coEvery { smsRepository.getAllMessages(any()) } returns flowOf(emptyList())

        // When
        viewModel.deleteMessage(messageId)

        // Then
        coVerify { smsManager.deleteMessage(messageId) }
    }

    @Test
    fun `resetSendState should reset to idle`() = runTest {
        // Given
        viewModel.sendMessage("+1234567890", "Test")

        // When
        viewModel.resetSendState()

        // Then
        assertIs<SendState.Idle>(viewModel.sendState.value)
    }

    @Test
    fun `should show permission required when permissions not granted`() = runTest {
        // Given
        every { permissionManager.hasPermission(any()) } returns false

        // When
        val newViewModel = SmsViewModel(
            smsManager,
            smsRepository,
            deviceManager,
            permissionManager,
            logger
        )

        // Then
        assertIs<SmsUiState.PermissionRequired>(newViewModel.uiState.value)
    }

    @Test
    fun `should observe connected devices`() = runTest {
        // Given
        val devices = listOf(createTestDevice("device1"))
        every { deviceManager.getConnectedDevices() } returns flowOf(devices)

        // When
        val newViewModel = SmsViewModel(
            smsManager,
            smsRepository,
            deviceManager,
            permissionManager,
            logger
        )

        // Then
        assertEquals(devices, newViewModel.connectedDevices.first())
    }

    private fun createTestMessage(id: String): Message {
        return Message(
            id = id,
            threadId = "thread-1",
            address = "+1234567890",
            body = "Test message $id",
            timestamp = System.currentTimeMillis(),
            type = MessageType.INBOX,
            read = false,
            deviceId = "local"
        )
    }

    private fun createTestConversation(id: String): Conversation {
        return Conversation(
            threadId = id,
            address = "+1234567890",
            contactName = "Test Contact",
            lastMessage = "Last message",
            lastTimestamp = System.currentTimeMillis(),
            unreadCount = 1,
            messageCount = 10
        )
    }

    private fun createTestDevice(id: String): Device {
        return Device(
            id = id,
            name = "Test Device",
            type = com.smslink.core.model.DeviceType.ANDROID,
            role = DeviceRole.MAIN,
            isPaired = true,
            isConnected = true,
            lastSeen = System.currentTimeMillis()
        )
    }
}
