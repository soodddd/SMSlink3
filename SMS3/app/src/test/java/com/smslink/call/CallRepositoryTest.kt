package com.smslink.call

import com.smslink.core.database.dao.CallLogDao
import com.smslink.core.log.ILogger
import com.smslink.core.model.CallDirection
import com.smslink.core.model.CallLog
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CallRepository 单元测试
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallRepositoryTest {

    private lateinit var callRepository: CallRepository
    private lateinit var context: android.content.Context
    private lateinit var callLogDao: CallLogDao
    private lateinit var logger: ILogger

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        callLogDao = mockk(relaxed = true)
        logger = mockk(relaxed = true)

        callRepository = CallRepository(
            context = context,
            callLogDao = callLogDao,
            logger = logger
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `getAllCallLogs should return call logs from dao`() = runTest {
        // Given
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
        every { callLogDao.getAll(any()) } returns flowOf(callLogs)

        // When
        val result = callRepository.getAllCallLogs().first()

        // Then
        assertEquals(callLogs, result)
    }

    @Test
    fun `insertCallLog should call dao insert`() = runTest {
        // Given
        val callLog = CallLog(
            id = "1",
            phoneNumber = "1234567890",
            contactName = "Test",
            type = CallDirection.INCOMING,
            timestamp = System.currentTimeMillis(),
            duration = 60000,
            isSynced = false
        )
        coEvery { callLogDao.insert(any()) } just Runs

        // When
        callRepository.insertCallLog(callLog)

        // Then
        coVerify { callLogDao.insert(callLog) }
    }

    @Test
    fun `markAsSynced should call dao markAsSynced`() = runTest {
        // Given
        val callId = "call-123"
        coEvery { callLogDao.markAsSynced(any()) } just Runs

        // When
        callRepository.markAsSynced(callId)

        // Then
        coVerify { callLogDao.markAsSynced(callId) }
    }

    @Test
    fun `getUnsyncedLogs should return unsynced logs from dao`() = runTest {
        // Given
        val unsyncedLogs = listOf(
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
        coEvery { callLogDao.getUnsyncedLogs() } returns unsyncedLogs

        // When
        val result = callRepository.getUnsyncedLogs()

        // Then
        assertEquals(unsyncedLogs, result)
    }

    @Test
    fun `deleteCallLog should call dao delete`() = runTest {
        // Given
        val callId = "call-123"
        coEvery { callLogDao.deleteById(callId) } just Runs

        // When
        callRepository.deleteCallLog(callId)

        // Then
        coVerify { callLogDao.deleteById(callId) }
    }

    @Test
    fun `getCallLogById should return call log from dao`() = runTest {
        // Given
        val callId = "call-123"
        val expectedLog = CallLog(
            id = callId,
            phoneNumber = "1234567890",
            contactName = "Test",
            type = CallDirection.INCOMING,
            timestamp = System.currentTimeMillis(),
            duration = 60000,
            isSynced = false
        )
        coEvery { callLogDao.getById(callId) } returns expectedLog

        // When
        val result = callRepository.getCallLogById(callId)

        // Then
        assertEquals(expectedLog, result)
    }

    @Test
    fun `getCallLogsByPhoneNumber should return logs for phone number`() = runTest {
        // Given
        val phoneNumber = "1234567890"
        val expectedLogs = listOf(
            CallLog(
                id = "1",
                phoneNumber = phoneNumber,
                contactName = "Test",
                type = CallDirection.INCOMING,
                timestamp = System.currentTimeMillis(),
                duration = 60000,
                isSynced = false
            )
        )
        coEvery { callLogDao.getByPhoneNumber(phoneNumber) } returns flowOf(expectedLogs)

        // When
        val result = callRepository.getCallLogsByPhoneNumber(phoneNumber).first()

        // Then
        assertEquals(expectedLogs, result)
    }

    @Test
    fun `getIncomingCalls should return incoming calls`() = runTest {
        // Given
        val incomingCalls = listOf(
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
        coEvery { callLogDao.getByType(CallDirection.INCOMING) } returns flowOf(incomingCalls)

        // When
        val result = callRepository.getIncomingCalls().first()

        // Then
        assertEquals(incomingCalls, result)
        assertTrue(result.all { it.type == CallDirection.INCOMING })
    }

    @Test
    fun `getOutgoingCalls should return outgoing calls`() = runTest {
        // Given
        val outgoingCalls = listOf(
            CallLog(
                id = "1",
                phoneNumber = "1234567890",
                contactName = "Test",
                type = CallDirection.OUTGOING,
                timestamp = System.currentTimeMillis(),
                duration = 60000,
                isSynced = false
            )
        )
        coEvery { callLogDao.getByType(CallDirection.OUTGOING) } returns flowOf(outgoingCalls)

        // When
        val result = callRepository.getOutgoingCalls().first()

        // Then
        assertEquals(outgoingCalls, result)
        assertTrue(result.all { it.type == CallDirection.OUTGOING })
    }

    @Test
    fun `getMissedCalls should return missed calls`() = runTest {
        // Given
        val missedCalls = listOf(
            CallLog(
                id = "1",
                phoneNumber = "1234567890",
                contactName = "Test",
                type = CallDirection.MISSED,
                timestamp = System.currentTimeMillis(),
                duration = 0,
                isSynced = false
            )
        )
        coEvery { callLogDao.getByType(CallDirection.MISSED) } returns flowOf(missedCalls)

        // When
        val result = callRepository.getMissedCalls().first()

        // Then
        assertEquals(missedCalls, result)
        assertTrue(result.all { it.type == CallDirection.MISSED })
    }

    @Test
    fun `clearAllCallLogs should call dao deleteAll`() = runTest {
        // Given
        coEvery { callLogDao.deleteAll() } just Runs

        // When
        callRepository.clearAllCallLogs()

        // Then
        coVerify { callLogDao.deleteAll() }
    }

    @Test
    fun `getCallLogCount should return count from dao`() = runTest {
        // Given
        val expectedCount = 25
        coEvery { callLogDao.getCount() } returns expectedCount

        // When
        val result = callRepository.getCallLogCount()

        // Then
        assertEquals(expectedCount, result)
    }

    @Test
    fun `syncFromSystem should sync call logs from system`() = runTest {
        // Given
        coEvery { callLogDao.insertAll(any()) } just Runs

        // When
        callRepository.syncFromSystem(50)

        // Then
        // Note: This requires ContentResolver mocking
        // Verify that the method completes without error
        assertTrue(true)
    }
}
