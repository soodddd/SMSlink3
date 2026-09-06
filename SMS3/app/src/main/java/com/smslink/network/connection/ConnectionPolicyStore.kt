package com.smslink.network.connection

import android.content.Context
import android.content.SharedPreferences
import com.smslink.core.model.ConnectionType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable connection preference shared by discovery, message, and file
 * transport. The selected link is a preference, not a hard requirement: a
 * failed preferred link may still fall back to the other authenticated links.
 */
enum class ConnectionPolicy(
    val title: String,
    val description: String,
    private val preferredLink: LinkType?
) {
    AUTO("自动选择", "局域网 → 热点 → 蓝牙兜底", null),
    WIFI_LAN("优先 Wi‑Fi 局域网", "优先局域网，失败后自动降级", LinkType.WIFI_LAN),
    WIFI_HOTSPOT("优先 Wi‑Fi 热点", "优先热点，失败后自动降级", LinkType.WIFI_HOTSPOT),
    BLUETOOTH("优先蓝牙", "优先蓝牙 RFCOMM，失败后自动降级", LinkType.BLUETOOTH);

    fun orderedLinks(): List<LinkType> {
        val defaults = LinkType.getAllByPriority()
        return preferredLink?.let { preferred ->
            listOf(preferred) + defaults.filterNot { it == preferred }
        } ?: defaults
    }

    fun orderedConnectionTypes(): List<ConnectionType> = orderedLinks().map { link ->
        when (link) {
            LinkType.WIFI_LAN -> ConnectionType.WIFI
            LinkType.WIFI_HOTSPOT -> ConnectionType.HOTSPOT
            LinkType.BLUETOOTH -> ConnectionType.BLUETOOTH
        }
    }

    companion object {
        fun fromStoredValue(value: String?): ConnectionPolicy =
            value?.let { stored -> values().firstOrNull { it.name == stored } } ?: AUTO
    }
}

@Singleton
class ConnectionPolicyStore private constructor(
    private val preferences: SharedPreferences?
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    )

    private val _policy = MutableStateFlow(
        ConnectionPolicy.fromStoredValue(preferences?.getString(KEY_POLICY, null))
    )
    val policy: StateFlow<ConnectionPolicy> = _policy.asStateFlow()

    fun get(): ConnectionPolicy = _policy.value

    fun set(value: ConnectionPolicy) {
        preferences?.edit()?.putString(KEY_POLICY, value.name)?.apply()
        _policy.value = value
    }

    companion object {
        const val PREFERENCES_NAME = "smslink_prefs"
        const val KEY_POLICY = "connection_policy"
        const val KEY_DARK_THEME = "theme_dark"
        const val KEY_LANGUAGE = "language"

        internal fun forTests(): ConnectionPolicyStore = ConnectionPolicyStore(null)
    }
}
