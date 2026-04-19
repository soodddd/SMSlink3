package com.smslink.call

import com.smslink.core.log.ILogger
import com.smslink.core.model.CallDirection
import com.smslink.core.model.CallLog
import com.smslink.core.model.CallState
import com.smslink.core.permission.IPermissionManager
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CallViewModelTest {

    private lateinit var viewModel: CallViewModel
    private lateinit var callManager: ICallManager
    private lateinit var callRepository: CallRepository
    private lateinit var permissionManager: IPermissionManager
    private lateinit var logger: ILogger

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        callManager = mockk(relaxed = true)
        callRepository = mockk(relaxed = true)
        permissionManager = mockk(relaxed = true)
        logger = mockk(relaxed = true)

        every { permissionManager.hasCallPermission() } returns true
        every { callManager.getCallState() } returns flowOf()
        every { callRepository.getAllCallLogs(any()) } returns flowOf(emptyList())

        viewModel = CallViewModel(
            callManager = callManager,
            callRepository = callRepository,
            permissionManager = permissionManager,
            logger = logger
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `loadCallHistory should update ui state with call logs`() = runTest {
        val callLogs = listOf(
            CallLog(
                id = "1",
                phoneNumber = "1234567890",
                contactName = "Test",
                type = CallDirection.INCOMING,
                timestamp = System.currentTimeMillis(),
                duration = 60000,
                isSynced = false
            )
        )
        every { callRepository.getAllCallLogs(any()) } returns flowOf(callLogs)

        viewModel.loadCallHistory()
        testDispatcher.scheduler.advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertFalse(uiState.isLoading)
        assertEquals(callLogs, uiState.callLogs)
    }

    @Test
    fun `makeCall should call callManager when permission granted`() = runTest {
        val phoneNumber = "1234567890"
        every { permissionManager.hasCallPermission() } returns true
        coEvery { callManager.makeCall(any()) } returns true

        viewModel.makeCall(phoneNumber)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { callManager.makeCall(phoneNumber) }
    }

    @Test
    fun `makeCall should show error when permission denied`() = runTest {
        val phoneNumber = "1234567890"
        every { permissionManager.hasCallPermission() } returns false

        viewModel.makeCall(phoneNumber)
        testDispatcher.scheduler.advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertEquals("Call permission required", uiState.error)
    }

    @Test
    fun `syncCallHistory should update sync state`() = runTest {
        coEvery { callRepository.syncFromSystem() } returns Result.success(10)

        viewModel.syncCallHistory()
        testDispatcher.scheduler.advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertFalse(uiState.isSyncing)
        assertEquals("Synced 10 call logs", uiState.syncMessage)
    }

    @Test
    fun `deleteCallLog should call repository delete`() = runTest {
        val callLog = CallLog(
            id = "1",
            phoneNumber = "1234567890",
            contactName = "Test",
            type = CallDirection.INCOMING,
            timestamp = System.currentTimeMillis(),
            duration = 60000,
            isSynced = false
        )
        coEvery { callRepository.deleteCallLog(any<CallLog>()) } just Runs

        viewModel.deleteCallLog(callLog)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { callRepository.deleteCallLog(callLog) }
    }

    @Test
    fun `startListening should call callManager startListening`() {
        viewModel.startListening()
        verify { callManager.startListening() }
    }

    @Test
    fun `stopListening should call callManager stopListening`() {
        viewModel.stopListening()
        verify { callManager.stopListening() }
    }

    @Test
    fun `answerCall should call callManager answerCall`() = runTest {
        val callId = "call-123"
        every { permissionManager.hasCallPermission() } returns true
        coEvery { callManager.answerCall(callId) } returns true

        viewModel.answerCall(callId)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { callManager.answerCall(callId) }
    }

    @Test
    fun `endCall should call callManager endCall`() = runTest {
        val callId = "call-123"
        every { permissionManager.hasCallPermission() } returns true
        coEvery { callManager.endCall(callId) } returns true

        viewModel.endCall(callId)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { callManager.endCall(callId) }
    }

    @Test
    fun `clearCallHistory should call repository clearAll`() = runTest {
        coEvery { callRepository.clearAllCallLogs() } just Runs

        viewModel.clearCallHistory()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { callRepository.clearAllCallLogs() }
    }

    @Test
    fun `filterCallLogs should filter by call type`() = runTest {
        val allLogs = listOf(
            CallLog(
                id = "1",
                phoneNumber = "1234567890",
                contactName = "Test",
                type = CallDirection.INCOMING,
                timestamp = System.currentTimeMillis(),
                duration = 60000,
                isSynced = false
            ),
            CallLog(
                id = "2",
                phoneNumber = "0987654321",
                contactName = "Test2",
                type = CallDirection.OUTGOING,
                timestamp = System.currentTimeMillis(),
                duration = 30000,
                isSynced = false
            )
        )
        every { callRepository.getAllCallLogs(any()) } returns flowOf(allLogs)

        viewModel.loadCallHistory()
        viewModel.filterByType(CallDirection.INCOMING)
        testDispatcher.scheduler.advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertTrue(uiState.callLogs.all { it.type == CallDirection.INCOMING })
    }

    @Test
    fun `should observe call state changes`() = runTest {
        val callState = CallState(
            callId = "call-123",
            phoneNumber = "1234567890",
            contactName = "Test",
            state = com.smslink.core.model.CallStateType.RINGING,
            direction = CallDirection.INCOMING,
            startTime = System.currentTimeMillis(),
            duration = 0
        )
        every { callManager.getCallState() } returns flowOf(callState)

        val newViewModel = CallViewModel(
            callManager = callManager,
            callRepository = callRepository,
            permissionManager = permissionManager,
            logger = logger
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val currentCallState = newViewModel.currentCallState.value
        assertNotNull(currentCallState)
    }

    @Test
    fun `loadCallHistory should handle empty list`() = runTest {
        every { callRepository.getAllCallLogs(any()) } returns flowOf(emptyList())

        viewModel.loadCallHistory()
        testDispatcher.scheduler.advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertTrue(uiState.callLogs.isEmpty())
    }

    @Test
    fun `loadCallHistory should handle error`() = runTest {
        every { callRepository.getAllCallLogs(any()) } returns flow {
            throw Exception("Database error")
        }

        viewModel.loadCallHistory()
        testDispatcher.scheduler.advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertNotNull(uiState.error)
    }
}
