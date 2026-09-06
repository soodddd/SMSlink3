package com.smslink.call

import android.os.Build
import android.telecom.Call
import android.telecom.InCallService
import androidx.annotation.RequiresApi
import com.smslink.core.log.ILogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject

/**
 * InCallService 实现
 * 提供通话控制功能（接听、挂断、静音、保持等）
 *
 * 注意：
 * - 需要应用设置为默认电话应用
 * - 需要 Android 9+ (API 28+)
 */
@RequiresApi(Build.VERSION_CODES.P)
@AndroidEntryPoint
class SmsLinkInCallService : InCallService() {

    @Inject
    lateinit var logger: ILogger

    private val _currentCall = MutableStateFlow<Call?>(null)
    val currentCall: StateFlow<Call?> = _currentCall.asStateFlow()
    private val calls = CopyOnWriteArrayList<Call>()

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            logger.d(TAG, "Call state changed: ${getStateString(state)}")
            handleCallStateChange(call, state)
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) {
            logger.d(TAG, "Call details changed: ${details.handle}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        logger.i(TAG, "InCallService created")
        instance = this
    }

    override fun onDestroy() {
        logger.i(TAG, "InCallService destroyed")
        // Telecom can destroy the service while calls are still in the local
        // collection. Unregister every callback before dropping references so
        // a late callback cannot update a dead service instance.
        calls.forEach { call ->
            runCatching { call.unregisterCallback(callCallback) }
        }
        calls.clear()
        _currentCall.value = null
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        logger.i(TAG, "Call added: ${call.details.handle}")
        calls.remove(call)
        calls.add(call)
        updateCurrentCall()
        call.registerCallback(callCallback)
    }

    override fun onCallRemoved(call: Call) {
        logger.i(TAG, "Call removed: ${call.details.handle}")
        try {
            call.unregisterCallback(callCallback)
        } finally {
            calls.remove(call)
            updateCurrentCall()
        }
    }

    /**
     * 接听电话
     */
    fun answerCall(): Boolean {
        return try {
            val call = _currentCall.value
            if (call != null && call.state == Call.STATE_RINGING) {
                call.answer(0)
                logger.i(TAG, "Call answered")
                true
            } else {
                logger.w(TAG, "No ringing call to answer, current state: ${call?.state}")
                false
            }
        } catch (e: SecurityException) {
            logger.e(TAG, "Permission denied to answer call - not default phone app?", e)
            false
        } catch (e: Exception) {
            logger.e(TAG, "Failed to answer call", e)
            false
        }
    }

    /**
     * 挂断电话
     */
    fun endCall(): Boolean {
        return try {
            val call = _currentCall.value
            if (call != null && call.state != Call.STATE_DISCONNECTED &&
                call.state != Call.STATE_DISCONNECTING
            ) {
                call.disconnect()
                logger.i(TAG, "Call ended")
                true
            } else {
                logger.w(TAG, "No active call to end")
                false
            }
        } catch (e: SecurityException) {
            logger.e(TAG, "Permission denied to end call - not default phone app?", e)
            false
        } catch (e: Exception) {
            logger.e(TAG, "Failed to end call", e)
            false
        }
    }

    /**
     * 静音
     */
    fun muteCall(): Boolean {
        return try {
            setMuted(true)
            logger.i(TAG, "Call muted")
            true
        } catch (e: Exception) {
            logger.e(TAG, "Failed to mute call", e)
            false
        }
    }

    /**
     * 取消静音
     */
    fun unmuteCall(): Boolean {
        return try {
            setMuted(false)
            logger.i(TAG, "Call unmuted")
            true
        } catch (e: Exception) {
            logger.e(TAG, "Failed to unmute call", e)
            false
        }
    }

    /**
     * 保持通话
     */
    fun holdCall(): Boolean {
        return try {
            val call = _currentCall.value
            if (call != null && call.state == Call.STATE_ACTIVE) {
                call.hold()
                logger.i(TAG, "Call held")
                true
            } else {
                logger.w(TAG, "No active call to hold")
                false
            }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to hold call", e)
            false
        }
    }

    /**
     * 恢复通话
     */
    fun resumeCall(): Boolean {
        return try {
            val call = _currentCall.value
            if (call != null && call.state == Call.STATE_HOLDING) {
                call.unhold()
                logger.i(TAG, "Call resumed")
                true
            } else {
                logger.w(TAG, "No held call to resume")
                false
            }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to resume call", e)
            false
        }
    }

    /**
     * 获取当前通话状态
     */
    fun getCurrentCallState(): Int? {
        return _currentCall.value?.state
    }

    /** Verify that the current telecom call still belongs to the number
     * carried by a remote command. Blank handles are allowed by telecom. */
    fun matchesCurrentCall(phoneNumber: String): Boolean {
        if (calls.isEmpty()) return false
        val expected = phoneNumber.filter(Char::isDigit)
        if (expected.isEmpty()) {
            return calls.any { call ->
                call.state != Call.STATE_DISCONNECTED && call.state != Call.STATE_DISCONNECTING
            }
        }
        return calls.any { call ->
            val actual = call.details.handle?.schemeSpecificPart
                ?.filter(Char::isDigit)
                .orEmpty()
            actual == expected &&
                call.state != Call.STATE_DISCONNECTED &&
                call.state != Call.STATE_DISCONNECTING
        }
    }

    /**
     * 处理通话状态变化
     */
    private fun handleCallStateChange(call: Call, state: Int) {
        updateCurrentCall()
        when (state) {
            Call.STATE_RINGING -> {
                logger.i(TAG, "Incoming call ringing")
            }
            Call.STATE_DIALING -> {
                logger.i(TAG, "Outgoing call dialing")
            }
            Call.STATE_ACTIVE -> {
                logger.i(TAG, "Call active")
            }
            Call.STATE_HOLDING -> {
                logger.i(TAG, "Call on hold")
            }
            Call.STATE_DISCONNECTED -> {
                logger.i(TAG, "Call disconnected")
            }
        }
    }

    private fun updateCurrentCall() {
        _currentCall.value = calls
            .filter { call ->
                call.state != Call.STATE_DISCONNECTED &&
                    call.state != Call.STATE_DISCONNECTING
            }
            .maxByOrNull { call ->
            when (call.state) {
                Call.STATE_RINGING -> 5
                Call.STATE_ACTIVE -> 4
                Call.STATE_DIALING,
                Call.STATE_CONNECTING -> 3
                Call.STATE_HOLDING -> 2
                else -> 1
            }
        }
    }

    /**
     * 获取状态字符串
     */
    private fun getStateString(state: Int): String {
        return when (state) {
            Call.STATE_NEW -> "NEW"
            Call.STATE_RINGING -> "RINGING"
            Call.STATE_DIALING -> "DIALING"
            Call.STATE_ACTIVE -> "ACTIVE"
            Call.STATE_HOLDING -> "HOLDING"
            Call.STATE_DISCONNECTED -> "DISCONNECTED"
            Call.STATE_CONNECTING -> "CONNECTING"
            Call.STATE_DISCONNECTING -> "DISCONNECTING"
            Call.STATE_SELECT_PHONE_ACCOUNT -> "SELECT_PHONE_ACCOUNT"
            else -> "UNKNOWN($state)"
        }
    }

    companion object {
        private const val TAG = "SmsLinkInCallService"

        @Volatile
        private var instance: SmsLinkInCallService? = null

        /**
         * 获取服务实例
         */
        fun getInstance(): SmsLinkInCallService? = instance
    }
}
