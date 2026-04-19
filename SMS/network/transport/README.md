# Network Transport Module

## 功能概述
实现底层传输协议 (TCP/UDP/蓝牙)。

## 已实现功能 ✅

### 1. TCP 传输层 (已完成)
- **TcpServer.kt** - TCP 服务端
  - 监听指定端口 (默认 8888)
  - 接受客户端连接
  - 管理多个并发连接
  - 连接事件通知
  
- **TcpClient.kt** - TCP 客户端
  - 连接到服务器
  - 自动重连机制
  - 连接状态管理
  - 消息发送接收

- **Connection.kt** - 连接管理
  - 单个 TCP 连接的生命周期管理
  - 消息接收和发送
  - 心跳机制 (30秒间隔)
  - 心跳超时检测 (60秒)
  - 自动断线检测

### 2. 核心特性
- ✅ 基于 Kotlin Coroutines 的异步 I/O
- ✅ Flow 流式消息接收
- ✅ 自动心跳保活
- ✅ 自动重连 (可配置)
- ✅ 连接状态监控
- ✅ 多客户端支持
- ✅ 大消息支持 (>100KB)

## 使用示例

### 服务端:
```kotlin
// 创建并启动服务器
val server = TcpServer(port = 8888, maxConnections = 10)
server.start()

// 监听连接事件
lifecycleScope.launch {
    server.connectionFlow.collect { event ->
        when (event) {
            is TcpServer.ConnectionEvent.ClientConnected -> {
                val connection = event.connection
                // 监听消息
                connection.messageFlow.collect { message ->
                    // 处理消息
                }
            }
            is TcpServer.ConnectionEvent.ClientDisconnected -> {
                // 处理断开
            }
        }
    }
}

// 停止服务器
server.stop()
```

### 客户端:
```kotlin
// 创建客户端
val client = TcpClient(
    host = "192.168.1.100",
    port = 8888,
    autoReconnect = true,
    reconnectDelayMs = 5000
)

// 连接
client.connect()

// 监听连接状态
lifecycleScope.launch {
    client.connectionStateFlow.collect { state ->
        when (state) {
            is TcpClient.ClientState.Connected -> {
                // 已连接
            }
            is TcpClient.ClientState.Disconnected -> {
                // 已断开
            }
            is TcpClient.ClientState.Reconnecting -> {
                // 重连中
            }
        }
    }
}

// 监听消息
lifecycleScope.launch {
    client.messageFlow?.collect { message ->
        // 处理消息
    }
}

// 发送消息
val message = Message(
    type = MessageType.DEVICE_DISCOVERY,
    flags = 0,
    messageId = System.currentTimeMillis(),
    payload = "Hello".toByteArray()
)
client.sendMessage(message)

// 断开连接
client.disconnect()
```

## 依赖关系
- `network:protocol` - 消息协议
- `kotlinx-coroutines-android` - 协程支持

## 测试

```bash
./gradlew :network:transport:test
```

测试覆盖:
- ✅ 服务器启动和停止
- ✅ 客户端连接
- ✅ 消息双向传输
- ✅ 多客户端支持
- ✅ 自动重连
- ✅ 大消息传输 (100KB)
- ✅ 心跳保活

## 待实现功能

- [ ] UDP 传输层 (UdpTransport)
- [ ] 蓝牙传输层 (BluetoothTransport)
- [ ] SSL/TLS 加密支持
- [ ] 连接池管理
- [ ] 流量控制

## 配置参数

### TcpServer:
- `port`: 监听端口 (默认 8888)
- `maxConnections`: 最大连接数 (默认 10)

### TcpClient:
- `host`: 服务器地址
- `port`: 服务器端口 (默认 8888)
- `autoReconnect`: 自动重连 (默认 true)
- `reconnectDelayMs`: 重连延迟 (默认 5000ms)
- `maxReconnectAttempts`: 最大重连次数 (默认 Int.MAX_VALUE)

### Connection:
- `heartbeatIntervalMs`: 心跳间隔 (默认 30000ms)
- `heartbeatTimeoutMs`: 心跳超时 (默认 60000ms)

## 架构设计

```
TcpServer
  ├── ServerSocket (监听端口)
  └── Connection[] (管理多个连接)

TcpClient
  └── Connection (单个连接)

Connection
  ├── Socket (底层 TCP 连接)
  ├── ReceiveJob (接收消息协程)
  ├── HeartbeatJob (心跳协程)
  ├── messageFlow (消息流)
  └── connectionStateFlow (状态流)
```
