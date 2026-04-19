AI 代码修改指令集
1. 概览
文件路径: `network/protocol/src/main/java/com/smslink/network/protocol/Message.kt`、`network/protocol/src/main/java/com/smslink/network/protocol/MessageCodec.kt`、`network/protocol/src/test/java/com/smslink/network/protocol/MessageCodecTest.kt`、`network/protocol/build.gradle.kts`

主要任务: 补齐协议层剩余的边界校验、资源保护和测试闭环，避免本端生成非法报文、避免超大负载导致内存风险，并清理无效依赖。

技术栈: Kotlin + Android Library + JUnit4

2. 修改任务清单
[任务 #01]
定位锚点: `MessageCodec.encode` 中的 `buffer.put(message.flags)`

当前代码:

```kotlin
// Flags (1 byte)
buffer.put(message.flags)
```

修改方案:

```kotlin
// Flags (1 byte)
val normalizedFlags = message.flags.toInt() and 0xFF
require((normalizedFlags and Message.UNSUPPORTED_FLAGS_MASK) == 0) {
    "Unsupported flags: 0x${normalizedFlags.toString(16)}"
}
buffer.put(normalizedFlags.toByte())
```

修改逻辑: 当前修复只在 `decode()` 阶段拦截非法标志位，但 `encode()` 仍允许本端构造并发送保留位被置位的非法报文，形成“本端能编码、本端却不能解码”的协议不对称。编码端也必须做同样的合法性检查，防止错误消息被发出。

严重程度: 高

[任务 #02]
定位锚点: `Message.kt` 中的 `SUPPORTED_FLAGS_MASK` / `UNSUPPORTED_FLAGS_MASK`，以及 `MessageCodec.decode` 中的 `flags.toInt()`

当前代码:

```kotlin
const val FLAG_REQUIRES_ACK = 0x01
const val FLAG_ENCRYPTED = 0x02
const val SUPPORTED_FLAGS_MASK = FLAG_REQUIRES_ACK or FLAG_ENCRYPTED
const val UNSUPPORTED_FLAGS_MASK = SUPPORTED_FLAGS_MASK.inv()
```

```kotlin
val flags = buffer.get()
val unsupportedFlags = flags.toInt() and Message.UNSUPPORTED_FLAGS_MASK
if (unsupportedFlags != 0) {
    return Result.failure(
        IllegalArgumentException("Unsupported flags: 0x${unsupportedFlags.toString(16)}")
    )
}
```

修改方案:

```kotlin
const val FLAG_REQUIRES_ACK = 0x01
const val FLAG_ENCRYPTED = 0x02
const val SUPPORTED_FLAGS_MASK = 0x03
const val UNSUPPORTED_FLAGS_MASK = 0xFC
```

```kotlin
val flags = buffer.get()
val normalizedFlags = flags.toInt() and 0xFF
val unsupportedFlags = normalizedFlags and Message.UNSUPPORTED_FLAGS_MASK
if (unsupportedFlags != 0) {
    return Result.failure(
        IllegalArgumentException("Unsupported flags: 0x${unsupportedFlags.toString(16)}")
    )
}
```

修改逻辑: 现在的掩码是通过 `inv()` 从 `Int` 推导出来的，结果是负数掩码，且 `flags.toInt()` 会对负数字节做符号扩展。逻辑碰巧还能拦截大部分非法位，但错误信息会出现 `0x-80` 这类非协议语义的输出，后续位运算也依赖了 JVM 的符号扩展细节。应统一按无符号 8 位标志位处理，掩码定义和错误输出都要落在 `0x00..0xFF` 范围内。

严重程度: 中

[任务 #03]
定位锚点: `MessageCodec.encode` 中的 `require(message.payload.size >= 0)` 与 `MessageCodec.decode` 中的 `ByteArray(payloadLength)`

当前代码:

```kotlin
// Payload Length (4 bytes)
require(message.payload.size >= 0) { "Payload length must be non-negative" }
buffer.putInt(message.payload.size)
```

```kotlin
// Payload
val payload = ByteArray(payloadLength)
buffer.get(payload)
```

修改方案:

```kotlin
// Message.kt
const val MAX_PAYLOAD_SIZE = 1024 * 1024
```

```kotlin
// Payload Length (4 bytes)
require(message.payload.size <= Message.MAX_PAYLOAD_SIZE) {
    "Payload too large: ${message.payload.size} bytes"
}
buffer.putInt(message.payload.size)
```

```kotlin
if (payloadLength > Message.MAX_PAYLOAD_SIZE) {
    return Result.failure(
        IllegalArgumentException("Payload too large: $payloadLength bytes")
    )
}

val payload = ByteArray(payloadLength)
buffer.get(payload)
```

修改逻辑: `payload.size >= 0` 对 `ByteArray` 来说是恒真条件，没有任何保护作用。当前实现允许远端或本地构造超大消息，在 `decode()` 时会再次分配同等大小的 `ByteArray`，容易在 Android 设备上触发高内存占用甚至 OOM。协议层必须设置明确的单消息负载上限，并在编解码两侧同时校验。

严重程度: 高

[任务 #04]
定位锚点: `MessageCodecTest` 当前测试集合仅覆盖 `decode rejects unsupported flags`，未覆盖编码端拒绝非法输入与超大负载

当前代码:

```kotlin
@Test
fun `decode rejects unsupported flags`() {
    val message = Message(
        type = MessageType.DEVICE_DISCOVERY,
        flags = 0x04,
        messageId = 1L,
        payload = byteArrayOf()
    )

    val result = MessageCodec.decode(MessageCodec.encode(message))

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull()?.message?.contains("Unsupported flags") == true)
}
```

修改方案:

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
    val buffer = java.nio.ByteBuffer.allocate(Message.HEADER_SIZE).order(java.nio.ByteOrder.BIG_ENDIAN)
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

修改逻辑: 当前测试只证明“解码时会拒绝非法标志位”，没有证明“编码时不会产出非法报文”，也没有保护负载上限。缺少这些回归测试，后续任何改动都可能再次把协议约束放宽，而 CI 无法发现。

严重程度: 高

[任务 #05]
定位锚点: `network/protocol/build.gradle.kts` 的 `dependencies` 块

当前代码:

```kotlin
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
```

修改方案:

```kotlin
dependencies {
    testImplementation("junit:junit:4.13.2")
}
```

修改逻辑: 当前协议模块源码只使用 `java.nio` 和 JUnit，没有使用 `core:model`、`core:common`、`androidx.core`、`coroutines-android`、`coroutines-test`。这些无效依赖会增大编译类路径、拖慢构建、增加模块耦合，并掩盖协议层本应保持纯粹的事实。应移除未使用依赖，保持该模块为最小闭包。

严重程度: 中

3. 约束规则 (强制执行)
编码规范: 必须继续使用 Kotlin 和现有命名风格；协议字段读写必须显式使用大端序；所有按位运算必须先归一化到无符号 8 位语义后再处理；新增测试必须使用 JUnit4 标准断言。

禁止变动: 严禁修改 `Message`、`MessageType`、`ErrorCode` 的现有公开构造参数、枚举值和协议头布局；严禁变更 Magic 值 `0x534C`、版本号 `0x01`、头长度 `16`；严禁引入破坏现有调用方的 API 重命名。

依赖限制: 禁止引入任何新的第三方依赖；仅允许删除未使用依赖或使用当前 JDK/Kotlin/JUnit 能力完成修正。

4. 验证标准
[ ] `MessageCodec.encode()` 必须拒绝携带未定义标志位的消息，不能继续产出本端自身都不接受的非法报文。

[ ] 标志位错误信息必须以 `0x00..0xFF` 的无符号十六进制形式输出，不能出现 `0x-80` 这类符号扩展结果。

[ ] 协议层必须定义并执行单消息负载上限，编码和解码两侧都要覆盖该限制。

[ ] `MessageCodecTest.kt` 必须新增“编码端非法标志位拒绝”和“超大负载拒绝”的回归测试。

[ ] `network/protocol/build.gradle.kts` 必须移除当前未使用的实现与测试依赖，保持协议模块最小依赖闭包。

[ ] 修改后 `.\gradlew.bat :network:protocol:test` 必须能够实际执行并通过；当前仓库状态下我验证该命令时仍然在 Gradle Wrapper 启动阶段失败，报错为 `NoClassDefFoundError: org/gradle/wrapper/IDownload`。
