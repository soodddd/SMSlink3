package com.smslink.core.log

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 日志实现类
 * 使用 Android Log 系统记录日志，JVM 测试环境下自动回退到 stdout。
 */
@Singleton
class LoggerImpl @Inject constructor() : ILogger {

    override fun d(tag: String, message: String) {
        runCatching { Log.d(tag, message) }.getOrElse { println("D/$tag: $message") }
    }

    override fun i(tag: String, message: String) {
        runCatching { Log.i(tag, message) }.getOrElse { println("I/$tag: $message") }
    }

    override fun w(tag: String, message: String) {
        runCatching { Log.w(tag, message) }.getOrElse { println("W/$tag: $message") }
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) {
            runCatching { Log.e(tag, message, throwable) }.getOrElse {
                println("E/$tag: $message\n${throwable.stackTraceToString()}")
            }
        } else {
            runCatching { Log.e(tag, message) }.getOrElse { println("E/$tag: $message") }
        }
    }
}
