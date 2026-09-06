package com.smslink.core.database

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.smslink.core.model.DeviceRole
import com.smslink.core.model.DeviceType
import com.smslink.core.model.MessageType
import com.smslink.core.model.SmsDeliveryStatus

/**
 * Room 类型转换器
 * 用于将复杂类型转换为 Room 可存储的基本类型
 */
class Converters {
    companion object {
        @JvmStatic
        val gson = Gson()
    }

    @TypeConverter
    fun fromDeviceType(value: DeviceType): String {
        return value.name
    }

    @TypeConverter
    fun toDeviceType(value: String): DeviceType {
        return DeviceType.valueOf(value)
    }

    @TypeConverter
    fun fromDeviceRole(value: DeviceRole): String {
        return value.name
    }

    @TypeConverter
    fun toDeviceRole(value: String): DeviceRole {
        return DeviceRole.valueOf(value)
    }

    @TypeConverter
    fun fromMessageType(value: MessageType): String {
        return value.name
    }

    @TypeConverter
    fun toMessageType(value: String): MessageType {
        return MessageType.valueOf(value)
    }

    @TypeConverter
    fun fromSmsDeliveryStatus(value: SmsDeliveryStatus): String = value.name

    @TypeConverter
    fun toSmsDeliveryStatus(value: String): SmsDeliveryStatus = SmsDeliveryStatus.valueOf(value)

    @TypeConverter
    fun fromStringList(value: List<String>): String {
        return gson.toJson(value)
    }

    @TypeConverter
    fun toStringList(value: String): List<String> {
        val listType = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(value, listType)
    }
}
