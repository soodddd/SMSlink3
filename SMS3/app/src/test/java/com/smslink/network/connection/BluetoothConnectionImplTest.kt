package com.smslink.network.connection

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import com.smslink.core.log.ILogger
import com.smslink.core.model.ConnectionType
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BluetoothConnectionImpl 单元测试
 */
class BluetoothConnectionImplTest {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var logger: ILogger
    private lateinit var bluetoothConnection: BluetoothConnectionImpl

    private val deviceId = "test-device"
    private val bluetoothAddress = "00:11:22:33:44:55"

    @Before
    fun setup() {
        bluetoothAdapter = mockk(relaxed = true)
        logger = mockk(relaxed = true)

        bluetoothConnection = BluetoothConnectionImpl(
            deviceId = deviceId,
            bluetoothAddress = bluetoothAddress,
            bluetoothAdapter = bluetoothAdapter,
            logger = logger
        )
    }

    @After
    fun tearDown() {
        bluetoothConnection.close()
        unmockkAll()
    }

    @Test
    fun `getType should return BLUETOOTH`() {
        // When
        val result = bluetoothConnection.getType()

        // Then
        assertEquals(ConnectionType.BLUETOOTH, result)
    }

    @Test
    fun `getRemoteAddress should return bluetooth address`() {
        // When
        val result = bluetoothConnection.getRemoteAddress()

        // Then
        assertEquals(bluetoothAddress, result)
    }

    @Test
    fun `isConnected should return false initially`() {
        // When
        val result = bluetoothConnection.isConnected()

        // Then
        assertFalse(result)
    }

    @Test
    fun `close should set connected to false`() {
        // When
        bluetoothConnection.close()

        // Then
        assertFalse(bluetoothConnection.isConnected())
        verify { logger.i(any(), any()) }
    }
}
