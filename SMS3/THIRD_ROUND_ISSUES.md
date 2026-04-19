# 第三轮审查发现的问题

## 审查模块
- ✅ 核心模型层
- ✅ 通话模块
- ✅ 权限管理
- ✅ 依赖注入配置

---

## 🔴 严重问题

### 1. CallViewModel - Dispatchers.Unconfined 再次出现 ⚠️⚠️⚠️
**位置**: `CallViewModel.kt:45`
**问题**:
```kotlin
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
```
- 与 FileTransferViewModel 相同的问题
- 使用 Dispatchers.Unconfined 导致线程不可预测
- 应该使用 viewModelScope

**风险**: 线程混乱、崩溃
**修复**: 改用 viewModelScope

### 2. CallManagerImpl - Gson 实例重复创建 ⚠️
**位置**: `CallManagerImpl.kt:93`
**问题**:
```kotlin
private val gson = Gson()
```
- 每个 CallManagerImpl 实例都创建新的 Gson
- 虽然是 @Singleton，但仍然浪费

**风险**: 内存浪费
**修复**: 使用 companion object 或注入

### 3. CallManagerImpl - 测试代码泄漏到生产 ⚠️⚠️
**位置**: `CallManagerImpl.kt:46-47, 89, 131-134`
**问题**:
```kotlin
@Volatile
var testCallIntentLauncher: ((Intent) -> Unit)? = null

testCallIntentLauncher?.let { launcher ->
    launcher(Intent())
    return true
}
```
- 测试代码混入生产代码
- 使用 @Volatile 但不是线程安全
- 可能被恶意利用

**风险**: 安全漏洞、测试污染
**修复**: 移除或使用依赖注入

### 4. PermissionManagerImpl - 权限请求未实现 ⚠️⚠️
**位置**: `PermissionManagerImpl.kt:31-50`
**问题**:
```kotlin
override fun requestPermission(permission: String): Flow<PermissionResult> = flow {
    // NOTE: This is skeleton implementation
    val granted = hasPermission(permission)
    emit(PermissionResult(permission, granted, false))
}
```
- 权限请求只是骨架实现
- 不会触发系统权限对话框
- 用户无法授予权限

**风险**: 功能不可用
**修复**: 实现真实的权限请求或明确标记为 TODO

### 5. CallViewModel - 类型转换不安全 ⚠️
**位置**: `CallViewModel.kt:180, 188, 196, 204, 216`
**问题**:
```kotlin
val success = (callManager as? CallManagerImpl)?.muteCall(callId) ?: false
```
- 依赖具体实现类而不是接口
- 如果注入的是其他实现，功能会静默失败

**风险**: 功能失效
**修复**: 将方法添加到 ICallManager 接口

### 6. CallManagerImpl - 构造函数测试代码 ⚠️
**位置**: `CallManagerImpl.kt:50-90`
**问题**: 
- 第二个构造函数创建假的 IMessageTransport 和 IDeviceManager
- 测试代码混入生产类

**风险**: 代码混乱、维护困难
**修复**: 移到测试类

---

## 🟡 中等问题

### 7. 核心模型层 - 缺少验证
**位置**: 所有 model 类
**问题**: 
- Connection、CallState 等模型没有数据验证
- 可能创建无效状态的对象

**风险**: 逻辑错误
**修复**: 添加 init 块验证或使用 require()

### 8. CallManagerImpl - 异常处理过于宽泛
**位置**: `CallManagerImpl.kt:143-149`
**问题**:
```kotlin
} catch (e: Exception) {
    logger.w(TAG, "Call intent launch returned an unexpected error, treating as initiated: ${e.message}")
    true
}
```
- 捕获所有异常并返回 true
- 可能隐藏真实错误

**风险**: 错误被隐藏
**修复**: 只捕获预期的异常

---

## 📊 统计

### 新发现问题
- **严重问题**: 6 个
- **中等问题**: 2 个
- **总计**: 8 个

### 必须修复（真机测试前）
1. CallViewModel - Dispatchers.Unconfined
2. CallManagerImpl - 测试代码泄漏
3. PermissionManagerImpl - 权限请求未实现
4. CallViewModel - 类型转换不安全

### 建议修复
5. CallManagerImpl - Gson 单例
6. CallManagerImpl - 构造函数测试代码
7. 核心模型层验证
8. 异常处理优化

---

## 总结

第三轮审查又发现了 **8 个问题**，其中 **6 个严重问题**。

最严重的是：
1. **Dispatchers.Unconfined** 再次出现（CallViewModel）
2. **测试代码泄漏到生产**（CallManagerImpl）
3. **权限请求未实现**（PermissionManagerImpl）

这些问题必须在真机测试前修复！
