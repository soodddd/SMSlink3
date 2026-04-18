package com.smslink.call

import android.content.Context
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.os.Build
import com.smslink.core.log.ILogger
import com.smslink.core.model.CallDirection
import com.smslink.core.model.CallState
import com.smslink.core.model.CallStateType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通话状态监听器
 * 监听系统通话状态变化
 */
@Singleton
class CallStateListener @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: ILogger
) {
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    private val _callState = MutableStateFlow<CallState?>(null)
    val callState: StateFlow<CallState?> = _callState.asStateFlow()

    private var currentCallId: String? = null
    private var currentPhoneNumber: String? = null
    private var callStartTime: Long = 0
    private var isListening = false

    // For Android 12+ (API 31+)
    private var telephonyCallback: TelephonyCallback? = null

    // For Android 11 and below
    @Suppress("DEPRECATION")
    private val phoneStateListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            handleCallStateChange(state, phoneNumber)
        }
    }

    /**
     * 开始监听通话状态
     */
    fun startListening() {
        if (isListening) {
            logger.d(TAG, "Already listening to call state")
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        handleCallStateChange(state, null)
                    }
                }
                telephonyCallback = callback
                telephonyManager.registerTelephonyCallback(context.mainExecutor, callback)
            } else {
                // Android 11 and below
                @Suppress("DEPRECATION")
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
            }

            isListening = true
            logger.i(TAG, "Started listening to call state")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to start listening to call state", e)
        }
    }

    /**
     * 停止监听通话状态
     */
    fun stopListening() {
        if (!isListening) {
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyCallback?.let {
                    telephonyManager.unregisterTelephonyCallback(it)
                }
                telephonyCallback = null
            } else {
                @Suppress("DEPRECATION")
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
            }

            isListening = false
            logger.i(TAG, "Stopped listening to call state")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to stop listening to call state", e)
        }
    }

    /**
     * 处理通话状态变化
     */
    private fun handleCallStateChange(state: Int, phoneNumber: String?) {
        logger.d(TAG, "Call state changed: state=$state, phoneNumber=$phoneNumber")

        when (state) {
            TelephonyManager.CALL_STATE_IDLE -> {
                // 通话结束
                currentCallId?.let { callId ->
                    val duration = if (callStartTime > 0) {
                        System.currentTimeMillis() - callStartTime
                    } else {
                        0L
                    }

                    _callState.value = CallState(
                        callId = callId,
                        phoneNumber = currentPhoneNumber ?: "",
                        contactName = null,
                        state = CallStateType.ENDED,
                        direction = determineCallDirection(),
                        startTime = callStartTime,
                        duration = duration
                    )
                }

                // 重置状态
                currentCallId = null
                currentPhoneNumber = null
                callStartTime = 0
            }

            TelephonyManager.CALL_STATE_RINGING -> {
                // 来电响铃
                currentCallId = UUID.randomUUID().toString()
                currentPhoneNumber = phoneNumber
                callStartTime = System.currentTimeMillis()

                _callState.value = CallState(
                    callId = currentCallId!!,
                    phoneNumber = phoneNumber ?: "",
                    contactName = null,
                    state = CallStateType.RINGING,
                    direction = CallDirection.INCOMING,
                    startTime = callStartTime,
                    duration = 0
                )
            }

            TelephonyManager.CALL_STATE_OFFHOOK -> {
                // 通话中（接听或拨出）
                if (currentCallId == null) {
                    currentCallId = UUID.randomUUID().toString()
                    currentPhoneNumber = phoneNumber
                    callStartTime = System.currentTimeMillis()
                }

                _callState.value = CallState(
                    callId = currentCallId!!,
                    phoneNumber = currentPhoneNumber ?: phoneNumber ?: "",
                    contactName = null,
                    state = CallStateType.OFFHOOK,
                    direction = determineCallDirection(),
                    startTime = callStartTime,
                    duration = 0
                )
            }
        }
    }

    /**
     * 判断通话方向
     */
    private fun determineCallDirection(): CallDirection {
        // 如果是从 RINGING 状态转换来的，则是来电
        // 否则是去电
        return if (_callState.value?.state == CallStateType.RINGING) {
            CallDirection.INCOMING
        } else {
            CallDirection.OUTGOING
        }
    }

    companion object {
        private const val TAG = "CallStateListener"
    }
}
