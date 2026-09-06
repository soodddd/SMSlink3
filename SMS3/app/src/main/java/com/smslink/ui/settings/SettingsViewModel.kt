package com.smslink.ui.settings

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import com.smslink.network.connection.ConnectionPolicy
import com.smslink.network.connection.ConnectionPolicyStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val connectionPolicyStore: ConnectionPolicyStore,
    private val preferences: SharedPreferences
) : ViewModel() {

    val connectionPolicy: StateFlow<ConnectionPolicy> = connectionPolicyStore.policy

    private val _darkTheme = MutableStateFlow(
        preferences.getBoolean(ConnectionPolicyStore.KEY_DARK_THEME, false)
    )
    val darkTheme: StateFlow<Boolean> = _darkTheme.asStateFlow()

    private val _language = MutableStateFlow(
        preferences.getString(ConnectionPolicyStore.KEY_LANGUAGE, LANGUAGE_ZH_CN)
            ?.takeIf { it == LANGUAGE_ZH_CN }
            ?: LANGUAGE_ZH_CN
    )
    val language: StateFlow<String> = _language.asStateFlow()

    fun setConnectionPolicy(policy: ConnectionPolicy) {
        connectionPolicyStore.set(policy)
    }

    fun setDarkTheme(enabled: Boolean) {
        preferences.edit().putBoolean(ConnectionPolicyStore.KEY_DARK_THEME, enabled).apply()
        _darkTheme.value = enabled
    }

    fun setLanguage(language: String) {
        // Keep the stored value explicit. The current UI ships Chinese only;
        // refusing unsupported locales is better than presenting a fake switch.
        if (language != LANGUAGE_ZH_CN) return
        preferences.edit().putString(ConnectionPolicyStore.KEY_LANGUAGE, language).apply()
        _language.value = language
    }

    companion object {
        const val LANGUAGE_ZH_CN = "中文"
    }
}
