# 额外发现的严重问题

## 文件传输模块 - 极高风险

### 1. **FileTransferViewModel - CoroutineScope 泄漏** ⚠️⚠️⚠️
**位置**: `FileTransferViewModel.kt:33`
**问题**: 
```kotlin
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
```
- 使用 `Dispatchers.Unconfined` 极其危险，会导致不可预测的线程行为
- 自定义 scope 在 ViewModel 中，但已有 `viewModelScope`
- `onCleared()` 中取消了 scope，但 init 中启动的协程可能已泄漏

**风险**: 内存泄漏、线程混乱、崩溃
**修复**: 使用 `viewModelScope`

### 2. **FileTransferManagerImpl - 文件句柄泄漏** ⚠️⚠️⚠️
**位置**: `FileTransferManagerImpl.kt:273, 625`
**问题**:
```kotlin
FileInputStream(file).use { input ->
    // 如果中途抛异常，input 会正确关闭
    // 但如果 Job 被 cancel()，use 块可能不会执行完
}

FileOutputStream(file, true).use { output ->
    // 同样的问题
}
```
- 在协程中使用 `use {}`，如果协程被取消，可能导致文件句柄泄漏
- `handleFileChunk` 每次都打开文件追加，频繁 I/O

**风险**: 文件描述符耗尽、OOM
**修复**: 使用 `ensureActive()` 检查，或改用 Channel

### 3. **数据包解析无边界检查** ⚠️⚠️⚠️
**位置**: `FileTransferManagerImpl.kt:470-489, 569-589`
**问题**:
```kotlin
val transferIdBytes = data.copyOfRange(offset, offset + 36)
val fileNameLength = data[offset].toInt()
val fileName = String(data.copyOfRange(offset, offset + fileNameLength))
```
- 没有检查 `data.size`
- `fileNameLength` 可能是负数或超大值
- 恶意数据包会导致 `ArrayIndexOutOfBoundsException` 或 OOM

**风险**: 崩溃、DoS 攻击
**修复**: 添加边界检查和长度限制

### 4. **文件路径注入漏洞** ⚠️⚠️
**位置**: `FileTransferManagerImpl.kt:505, 607`
**问题**:
```kotlin
val filePath = File(downloadDir, fileName).absolutePath
```
- `fileName` 来自网络，未验证
- 可能包含 `../` 导致路径遍历攻击
- 可能覆盖系统文件

**风险**: 安全漏洞、数据丢失
**修复**: 验证文件名，禁止路径分隔符

### 5. **ByteArray 转换错误** ⚠️⚠️
**位置**: `FileTransferManagerImpl.kt:797-803`
**问题**:
```kotlin
private fun ByteArray.toLong(): Long {
    var result = 0L
    for (i in indices) {
        result = result or ((this[i].toLong() and 0xFF) shl (8 * i))
    }
    return result
}
```
- 假设 ByteArray 长度为 8，但未检查
- 如果长度不足，会返回错误值
- 如果长度超过 8，会溢出

**风险**: 数据损坏、文件大小错误
**修复**: 添加长度检查

### 6. **并发修改 activeTransfers** ⚠️
**位置**: `FileTransferManagerImpl.kt:36, 135, 194, 321`
**问题**:
```kotlin
private val activeTransfers = mutableMapOf<String, Job>()
// 多个协程同时访问，无同步
activeTransfers[transferId] = job
activeTransfers.remove(transferId)
```
**风险**: ConcurrentModificationException
**修复**: 使用 ConcurrentHashMap 或 synchronized

## 通知模块 - 高风险

### 7. **NotificationManagerImpl - 单例模式错误** ⚠️⚠️
**位置**: `NotificationManagerImpl.kt:468-475`
**问题**:
```kotlin
@Volatile
private var instance: NotificationManagerImpl? = null

internal fun setInstance(manager: NotificationManagerImpl) {
    instance = manager
}
```
- 使用 `@Volatile` 但不是线程安全的单例
- Hilt 已经提供 `@Singleton`，不应该再手动管理单例
- `NotificationListenerServiceImpl` 通过 `getInstance()` 访问，可能为 null

**风险**: NPE、内存泄漏
**修复**: 移除手动单例，使用依赖注入

### 8. **NotificationListenerServiceImpl - 协程泄漏** ⚠️
**位置**: `NotificationListenerServiceImpl.kt:27, 66`
**问题**:
```kotlin
private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

override fun onNotificationPosted(sbn: StatusBarNotification) {
    serviceScope.launch {
        // 每个通知都启动新协程
        // 如果通知频繁，协程会累积
    }
}
```
**风险**: 内存泄漏、性能下降
**修复**: 使用 Channel 或限制并发数

### 9. **SharedPreferences 并发修改** ⚠️
**位置**: `NotificationListenerServiceImpl.kt:172-198`
**问题**:
```kotlin
fun addToWhitelist(packageName: String) {
    val whitelist = preferences.getStringSet(PREF_WHITELIST, emptySet())?.toMutableSet()
    whitelist.add(packageName)
    preferences.edit().putStringSet(PREF_WHITELIST, whitelist).apply()
}
```
- `getStringSet()` 返回的 Set 不应该被修改
- 多线程同时修改会导致数据丢失
- 应该使用 `commit()` 而不是 `apply()` 来确保写入成功

**风险**: 数据丢失、崩溃
**修复**: 使用 `toMutableSet()` 并检查返回值

### 10. **NotificationViewModel - 无限循环风险** ⚠️
**位置**: `NotificationViewModel.kt:104-115`
**问题**:
```kotlin
private fun observeNewNotifications() {
    viewModelScope.launch {
        notificationManager.getNotifications()
            .collect { newNotification ->
                loadNotifications() // 每次新通知都重新加载全部
            }
    }
}
```
- 每个新通知都触发数据库查询
- 如果通知频繁，会导致性能问题
- 应该直接更新列表而不是重新查询

**风险**: 性能下降、UI 卡顿
**修复**: 直接添加到列表

### 11. **NotificationViewModel - init 中启动 Discovery** ⚠️
**位置**: `NotificationViewModel.kt:61`
**问题**:
```kotlin
init {
    deviceManager.startDiscovery()
}
```
- ViewModel 不应该在 init 中启动设备发现
- 可能导致不必要的蓝牙扫描
- 应该由用户主动触发

**风险**: 电池消耗、权限问题
**修复**: 移除或改为手动触发

## UI 层 - 中等风险

### 12. **FileTransferViewModel - 中文乱码** ⚠️
**位置**: `FileTransferViewModel.kt:19-20, 40-42, 69-70`
**问题**: 注释使用了乱码字符
```kotlin
/**
 * 鏂囦欢浼犺緭 ViewModel
 */
```
**风险**: 代码可读性差
**修复**: 使用正确的 UTF-8 编码

## 总结

### 新发现的严重问题统计
- **极高风险**: 6 个（文件传输模块）
- **高风险**: 5 个（通知模块）
- **中等风险**: 1 个（UI 层）

### 必须立即修复（真机测试前）
1. FileTransferViewModel - Dispatchers.Unconfined
2. 数据包解析无边界检查
3. 文件路径注入漏洞
4. ByteArray 转换错误
5. NotificationManagerImpl 单例模式
6. SharedPreferences 并发修改

### 建议修复（性能和稳定性）
7. 文件句柄泄漏
8. 并发修改 activeTransfers
9. NotificationListenerServiceImpl 协程泄漏
10. NotificationViewModel 无限循环
11. init 中启动 Discovery

### 可延后修复
12. 中文注释乱码
