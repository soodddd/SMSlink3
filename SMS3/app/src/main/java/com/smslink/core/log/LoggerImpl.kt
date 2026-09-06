package com.smslink.core.log

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 日志实现类
 * 使用 Android Log 系统记录日志，JVM 测试环境下自动回退到 stdout。
 */
@Singleton
class LoggerImpl @Inject constructor(
    private val diagnosticLogStore: DiagnosticLogStore
) : ILogger {

    /** Compatibility constructor used by the JVM unit tests. */
    constructor() : this(DiagnosticLogStore())

    override fun d(tag: String, message: String) {
        diagnosticLogStore.append("DEBUG", tag, message)
        runCatching { Log.d(tag, message) }.getOrElse { println("D/$tag: $message") }
    }

    override fun i(tag: String, message: String) {
        diagnosticLogStore.append("INFO", tag, message)
        runCatching { Log.i(tag, message) }.getOrElse { println("I/$tag: $message") }
    }

    override fun w(tag: String, message: String) {
        diagnosticLogStore.append("WARN", tag, message)
        runCatching { Log.w(tag, message) }.getOrElse { println("W/$tag: $message") }
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        val diagnosticMessage = if (throwable == null) {
            message
        } else {
            "$message\n${throwable.stackTraceToString()}"
        }
        diagnosticLogStore.append("ERROR", tag, diagnosticMessage)
        if (throwable != null) {
            runCatching { Log.e(tag, message, throwable) }.getOrElse {
                println("E/$tag: $message\n${throwable.stackTraceToString()}")
            }
        } else {
            runCatching { Log.e(tag, message) }.getOrElse { println("E/$tag: $message") }
        }
    }
}
