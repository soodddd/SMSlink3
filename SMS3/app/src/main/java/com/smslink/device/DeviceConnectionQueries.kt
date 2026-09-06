package com.smslink.device

import com.smslink.core.model.Device
import kotlinx.coroutines.flow.Flow

/**
 * Business traffic must use authenticated live links. The fallback preserves
 * the original interface contract for lightweight test/adaptor
 * implementations that do not expose DeviceManagerImpl's Room-backed live
 * state.
 */
fun IDeviceManager.observeLiveConnections(): Flow<List<Device>> =
    if (this is DeviceManagerImpl) {
        getLiveConnectedDevices()
    } else {
        getConnectedDevices()
    }
