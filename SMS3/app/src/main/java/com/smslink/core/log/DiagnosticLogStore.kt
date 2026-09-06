package com.smslink.core.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small in-process ring buffer used by the diagnostic screen.
 *
 * Android Logcat remains the authoritative system log. This store is only a
 * bounded, user-visible view so the app never grows an unbounded file or
 * retains sensitive payloads indefinitely.
 */
data class DiagnosticLog(
    val level: String,
    val tag: String,
    val message: String,
    val timestamp: Long
)

@Singleton
class DiagnosticLogStore @Inject constructor() {
    companion object {
        const val MAX_ENTRIES = 500
    }

    private val lock = Any()
    private val _logs = MutableStateFlow<List<DiagnosticLog>>(emptyList())
    val logs: StateFlow<List<DiagnosticLog>> = _logs.asStateFlow()

    fun append(level: String, tag: String, message: String, timestamp: Long = System.currentTimeMillis()) {
        val entry = DiagnosticLog(
            level = level,
            tag = tag.take(80),
            message = message.take(4000),
            timestamp = timestamp
        )
        synchronized(lock) {
            _logs.value = (_logs.value + entry).takeLast(MAX_ENTRIES)
        }
    }

    fun clear() {
        synchronized(lock) {
            _logs.value = emptyList()
        }
    }

    fun exportText(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        return synchronized(lock) {
            _logs.value.joinToString(separator = "\n") { entry ->
                "${formatter.format(Date(entry.timestamp))} ${entry.level}/${entry.tag}: ${entry.message}"
            }
        }
    }
}
