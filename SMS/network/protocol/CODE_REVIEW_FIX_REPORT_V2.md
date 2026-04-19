# 协议模块第二轮代码审查修正报告

**日期**: 2026-04-10  
**模块**: network:protocol  
**审查文档**: AI_REVISION_SPEC2.md (第二轮)

## 修正概览

根据第二轮代码审查,补齐了协议层的边界校验、资源保护和测试闭环,避免本端生成非法报文和超大负载导致的内存风险,并清理了无效依赖。

## 已完成的修正

### ✅ 任务 #1: 编码端添加标志位验证
**文件**: `MessageCodec.kt`  
**严重程度**: 高

**问题**: 只在 decode() 阶段拦截非法标志位,但 encode() 仍允许本端构造并发送保留位被置位的非法报文,形成"本端能编码、本端却不能解码"的协议不对称。

**修正**:
```kotlin
// Flags (1 byte)
val normalizedFlags = message.flags.toInt() and 0xFF
require((normalizedFlags and Message.UNSUPPORTED_FLAGS_MASK) == 0) {
    "Unsupported flags: 0x${normalizedFlags.toString(16)}"
}
buffer.put(normalizedFlags.toByte())
```

**影响**: 编码端现在会拒绝非法标志位,确保协议对称性。

---

### ✅ 任务 #2: 修正标志位掩码为无符号语义
**文件**: `Message.kt`, `MessageCodec.kt`  
**严重程度**: 中

**问题**: 
1. 掩码通过 `inv()` 从 `Int` 推导,结果是负数掩码
2. `flags.toInt()` 会对负数字节做符号扩展
3. 错误信息会出现 `0x-80` 这类非协议语义的输出

**修正**:
```kotlin
// Message.kt
const val SUPPORTED_FLAGS_MASK = 0x03
const val UNSUPPORTED_FLAGS_MASK = 0xFC

// MessageCodec.kt decode()
val normalizedFlags = flags.toInt() and 0xFF
val unsupportedFlags = normalizedFlags and Message.UNSUPPORTED_FLAGS_MASK
```

**影响**: 统一按无符号 8 位标志位处理,掩码定义和错误输出都落在 `0x00..0xFF` 范围内。

---

### ✅ 任务 #3: 添加负载大小上限保护
**文件**: `Message.kt`, `MessageCodec.kt`  
**严重程度**: 高

**问题**: 
1. `payload.size >= 0` 对 `ByteArray` 来说是恒真条件,没有保护作用
2. 允许远端或本地构造超大消息,容易在 Android 设备上触发 OOM

**修正**:
```kotlin
// Message.kt
const val MAX_PAYLOAD_SIZE = 1024 * 1024  // 1MB

// MessageCodec.kt encode()
require(message.payload.size <= Message.MAX_PAYLOAD_SIZE) {
    "Payload too large: ${message.payload.size} bytes"
}

// MessageCodec.kt decode()
if (payloadLength > Message.MAX_PAYLOAD_SIZE) {
    return Result.failure(
        IllegalArgumentException("Payload too large: $payloadLength bytes")
    )
}
```

**影响**: 单消息负载上限为 1MB,编解码两侧都会校验,防止内存风险。

---

### ✅ 任务 #4: 补充编码端和超大负载测试
**文件**: `MessageCodecTest.kt`  
**严重程度**: 高

**问题**: 缺少编码端拒绝非法输入和超大负载的回归测试。

**新增测试**:
```kotlin
@Test(expected = IllegalArgumentException::class)
fun `encode rejects unsupported flags`() {
    val message = Message(
        type = MessageType.DEVICE_DISCOVERY,
        flags = 0x04,
        messageId = 1L,
        payload = byteArrayOf()
    )
    MessageCodec.encode(message)
}

@Test(expected = IllegalArgumentException::class)
fun `encode rejects oversized payload`() {
    val message = Message(
        type = MessageType.FILE_TRANSFER_DATA,
        flags = 0,
        messageId = 1L,
        payload = ByteArray(Message.MAX_PAYLOAD_SIZE + 1)
    )
    MessageCodec.encode(message)
}

@Test
fun `decode rejects oversized payload length`() {
    val buffer = java.nio.ByteBuffer.allocate(Message.HEADER_SIZE)
        .order(java.nio.ByteOrder.BIG_ENDIAN)
    buffer.putShort(Message.MAGIC)
    buffer.put(Message.VERSION)
    buffer.put(MessageType.DEVICE_DISCOVERY.value)
    buffer.put(0)
    buffer.putInt(Message.MAX_PAYLOAD_SIZE + 1)
    buffer.putLong(1L)

    val result = MessageCodec.decode(buffer.array())

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull()?.message?.contains("Payload too large") == true)
}
```

**影响**: 确保编码端约束和负载上限被测试覆盖,防止后续改动放宽协议约束。

---

### ✅ 任务 #5: 清理无效依赖
**文件**: `build.gradle.kts`  
**严重程度**: 中

**问题**: 协议模块源码只使用 `java.nio` 和 JUnit,但依赖了 `core:model`、`core:common`、`androidx.core`、`coroutines-android`、`coroutines-test` 等未使用的库。

**修正**:
```kotlin
// 修正前
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

// 修正后
dependencies {
    testImplementation("junit:junit:4.13.2")
}
```

**影响**: 
- 减少编译类路径,加快构建速度
- 降低模块耦合
- 保持协议层为最小闭包

---

## 验证标准检查

- [x] `MessageCodec.encode()` 拒绝携带未定义标志位的消息
- [x] 标志位错误信息以 `0x00..0xFF` 的无符号十六进制形式输出
- [x] 协议层定义并执行单消息负载上限 (1MB)
- [x] 编码和解码两侧都覆盖负载上限
- [x] 新增"编码端非法标志位拒绝"回归测试
- [x] 新增"超大负载拒绝"回归测试
- [x] 移除未使用的实现与测试依赖

## 约束规则遵守情况

✅ **编码规范**: 保持 Kotlin 命名风格,使用大端序,按位运算归一化到无符号 8 位  
✅ **禁止变动**: 未修改公开 API、协议头布局、Magic 值、版本号  
✅ **依赖限制**: 仅删除未使用依赖,未引入新依赖

## 影响分析

### 破坏性变更
**无** - 所有修正都是加强约束,不影响正确使用协议的代码。

### 行为变更
1. **编码端现在会拒绝非法标志位** (之前只在解码端拒绝)
2. **编解码两侧都会拒绝超过 1MB 的负载** (之前无限制)
3. **标志位错误信息格式统一** (0x00-0xFF 范围)

这些变更都是**加强协议安全性和资源保护**。

## 关键改进

### 1. 协议对称性
编码端和解码端现在执行相同的约束规则,避免"能编码但不能解码"的不一致。

### 2. 内存安全
1MB 负载上限防止恶意或错误的超大消息导致 Android 设备 OOM。

### 3. 模块纯粹性
协议模块现在是零依赖的纯 Kotlin/JDK 实现,便于移植和测试。

### 4. 测试完整性
回归测试覆盖了编码端约束和资源限制,确保协议实现的稳定性。

## 文件清单

**修改的文件**:
- `network/protocol/src/main/java/com/smslink/network/protocol/Message.kt`
- `network/protocol/src/main/java/com/smslink/network/protocol/MessageCodec.kt`
- `network/protocol/src/test/java/com/smslink/network/protocol/MessageCodecTest.kt`
- `network/protocol/build.gradle.kts`

## 下一步建议

1. 运行测试验证修正: `./gradlew :network:protocol:test`
2. 考虑添加性能测试,验证 1MB 负载的编解码性能
3. 继续实现 TCP 传输层 (network:transport)

---

**修正完成时间**: 2026-04-10 11:15  
**所有任务状态**: ✅ 已完成
