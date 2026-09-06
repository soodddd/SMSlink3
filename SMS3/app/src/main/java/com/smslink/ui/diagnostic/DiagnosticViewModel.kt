package com.smslink.ui.diagnostic

import androidx.lifecycle.ViewModel
import com.smslink.core.log.DiagnosticLogStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class DiagnosticViewModel @Inject constructor(
    private val logStore: DiagnosticLogStore
) : ViewModel() {
    val logs = logStore.logs

    fun refresh() {
        // The StateFlow is live. Keeping this method makes the toolbar action
        // explicit without creating a second, stale snapshot.
    }

    fun clear() = logStore.clear()

    fun exportText(): String = logStore.exportText()
}
