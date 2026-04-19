package com.smslink.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smslink.core.preferences.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppPreferencesViewModel @Inject constructor(
    private val appPreferences: AppPreferences
) : ViewModel() {

    val shouldShowOnboarding: StateFlow<Boolean> = appPreferences.shouldShowOnboarding
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    val isDarkTheme: StateFlow<Boolean> = appPreferences.themeMode
        .map { mode -> mode.isDarkMode() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    fun markOnboardingCompleted() {
        viewModelScope.launch {
            appPreferences.markOnboardingCompleted()
        }
    }

    fun setDarkTheme(enabled: Boolean) {
        viewModelScope.launch {
            appPreferences.setDarkTheme(enabled)
        }
    }
}
