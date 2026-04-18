package com.smslink.core.log

/**
 * 日志接口
 * 提供统一的日志记录功能
 */
interface ILogger {
    /**
     * 记录调试日志
     * @param tag 日志标签
     * @param message 日志消息
     */
    fun d(tag: String, message: String)

    /**
     * 记录信息日志
     * @param tag 日志标签
     * @param message 日志消息
     */
    fun i(tag: String, message: String)

    /**
     * 记录警告日志
     * @param tag 日志标签
     * @param message 日志消息
     */
    fun w(tag: String, message: String)

    /**
     * 记录错误日志
     * @param tag 日志标签
     * @param message 日志消息
     * @param throwable 异常对象（可选）
     */
    fun e(tag: String, message: String, throwable: Throwable? = null)
}
