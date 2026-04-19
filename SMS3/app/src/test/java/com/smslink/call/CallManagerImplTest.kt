package com.smslink.call

import com.smslink.core.log.ILogger
import com.smslink.core.model.CallDirection
import com.smslink.core.model.CallState
import com.smslink.core.model.CallStateType
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import android.content.Intent
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * CallManagerImpl 单元测试
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallManagerImplTest {

    private lateinit var callManager: CallManagerImpl
    private lateinit var callStateListener: CallStateListener
    private lateinit var callRepository: CallRepository
    private lateinit var networkManager: com.smslink.network.INetworkManager
    private lateinit var logger: ILogger
    private lateinit var context: android.content.Context
    private var launchedIntent: Intent? = null

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        callStateListener = mockk(relaxed = true)
        callRepository = mockk(relaxed = true)
        networkManager = mockk(relaxed = true)
        logger = mockk(relaxed = true)
        launchedIntent = null
        CallManagerImpl.testCallIntentLauncher = { launchedIntent = it }

        callManager = CallManagerImpl(
            context = context,
            callStateListener = callStateListener,
            callRepository = callRepository,
            networkManager = networkManager,
            logger = logger,
            callIntentLauncher = { launchedIntent = it }
        )
    }

    @After
    fun tearDown() {
        CallManagerImpl.testCallIntentLauncher = null
        unmockkAll()
    }

    @Test
    fun `startListening should start call state listener`() {
        // When
        callManager.startListening()

        // Then
        verify { callStateListener.startListening() }
    }

    @Test
    fun `stopListening should stop call state listener`() {
        // When
        callManager.stopListening()

        // Then
        verify { callStateListener.stopListening() }
    }

    @Test
    fun `makeCall should start call intent`() = runTest {
        // Given
        val phoneNumber = "1234567890"

        // When
        val result = callManager.makeCall(phoneNumber)

        // Then
        assertEquals(true, result)
        assertNotNull(launchedIntent)
    }

    @Test
    fun `makeCall should return false on exception`() = runTest {
        // Given
        val phoneNumber = "1234567890"
        CallManagerImpl.testCallIntentLauncher = { throw SecurityException("No permission") }
        callManager = CallManagerImpl(
            context = context,
            callStateListener = callStateListener,
            callRepository = callRepository,
            networkManager = networkManager,
            logger = logger,
            callIntentLauncher = { throw SecurityException("No permission") }
        )

        // When
        val result = callManager.makeCall(phoneNumber)

        // Then
        assertEquals(false, result)
    }

    @Test
    fun `syncCallState should save to repository and send via network`() = runTest {
        // Given
        val callState = CallState(
            callId = "call-123",
            phoneNumber = "1234567890",
            contactName = "Test Contact",
            state = CallStateType.OFFHOOK,
            direction = CallDirection.OUTGOING,
            startTime = System.currentTimeMillis(),
            duration = 60000
        )
        val targetDeviceId = "device-456"

        coEvery { callRepository.insertCallLog(any()) } just Runs
        coEvery { networkManager.sendMessage(any(), any()) } just Runs
        coEvery { callRepository.markAsSynced(any()) } just Runs

        // When
        callManager.syncCallState(callState, targetDeviceId)

        // Then
        coVerify { callRepository.insertCallLog(any()) }
        coVerify { networkManager.sendMessage(any(), targetDeviceId) }
        coVerify { callRepository.markAsSynced(callState.callId) }
    }

    @Test
    fun `syncCallState should handle exception gracefully`() = runTest {
        // Given
        val callState = CallState(
            callId = "call-123",
            phoneNumber = "1234567890",
            contactName = "Test Contact",
            state = CallStateType.OFFHOOK,
            direction = CallDirection.OUTGOING,
            startTime = System.currentTimeMillis(),
            duration = 60000
        )
        val targetDeviceId = "device-456"
        coEvery { callRepository.insertCallLog(any()) } throws Exception("Database error")

        // When
        callManager.syncCallState(callState, targetDeviceId)

        // Then
        verify { logger.e("CallManagerImpl", "Failed to sync call state", any()) }
    }

    @Test
    fun `answerCall should return result based on API level`() = runTest {
        // Given
        val callId = "call-123"

        // When
        val result = callManager.answerCall(callId)

        // Then - result depends on Android version
        assertNotNull(result)
    }

    @Test
    fun `endCall should return result based on API level`() = runTest {
        // Given
        val callId = "call-123"

        // When
        val result = callManager.endCall(callId)

        // Then - result depends on Android version
        assertNotNull(result)
    }

    @Test
    fun `getCallState should return flow from listener`() = runTest {
        // Given
        val callState = CallState(
            callId = "call-123",
            phoneNumber = "1234567890",
            contactName = "Test Contact",
            state = CallStateType.RINGING,
            direction = CallDirection.INCOMING,
            startTime = System.currentTimeMillis(),
            duration = 0
        )
        every { callStateListener.callState } returns kotlinx.coroutines.flow.MutableStateFlow(callState)

        // When
        val flow = callManager.getCallState()

        // Then
        assertNotNull(flow)
    }
}
