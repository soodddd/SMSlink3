# SMS-Link 开发指南

## 快速开始

### 环境要求
- JDK 17+
- Android SDK 35
- Android NDK 27.2+
- Gradle 8.7+
- CMake 3.22.1+

### 克隆和构建

```bash
git clone <repository-url>
cd sms
./gradlew build
```

### 项目结构

```
sms/
├── app/                    # 应用主模块
├── core/                   # 核心基础层
│   ├── common/            # 通用工具
│   ├── model/             # 数据模型
│   ├── database/          # 本地数据库
│   └── preferences/       # 配置管理
├── network/               # 网络通信层
│   ├── protocol/          # 协议定义
│   ├── transport/         # 传输实现
│   ├── discovery/         # 设备发现
│   └── hotspot/           # 热点管理
├── audio/                 # 音频处理 (Native)
├── feature/               # 功能模块
│   ├── device/            # 设备管理
│   ├── notification/      # 通知同步
│   ├── call/              # 通话功能
│   ├── transfer/          # 文件传输
│   └── settings/          # 设置
├── ui/                    # UI 组件库
└── docs/                  # 文档
```

## 开发流程

### 1. 选择模块

根据你要开发的功能选择对应模块:
- 设备配对 → `feature:device`
- 通知同步 → `feature:notification`
- 通话功能 → `feature:call`
- 文件传输 → `feature:transfer`
- 音频处理 → `audio`
- 网络通信 → `network/*`

### 2. 阅读文档

每个模块都有 README.md 和 API 文档:
- 模块 README: `<module>/README.md`
- API 文档: `docs/modules/<MODULE>_API.md`
- 协议规范: `docs/protocol/PROTOCOL_SPEC.md`

### 3. 编写代码

遵循以下原则:
- 单一职责: 每个类只做一件事
- 依赖注入: 使用 Hilt
- 协程优先: 使用 Kotlin Coroutines + Flow
- 测试驱动: 先写测试再写实现

### 4. 运行测试

```bash
# 单元测试
./gradlew :module:test

# 集成测试
./gradlew :module:connectedAndroidTest

# 所有测试
./gradlew test
```

### 5. 提交代码

```bash
git add .
git commit -m "feat(module): 简短描述"
git push
```

## 代码规范

### Kotlin 风格
- 使用 4 空格缩进
- 类名: PascalCase
- 函数名: camelCase
- 常量: UPPER_SNAKE_CASE
- 最大行长: 120 字符

### 命名约定
- Repository: 数据访问层
- Manager: 业务逻辑层
- ViewModel: UI 状态管理
- UseCase: 单一业务用例

### 文件组织
```
module/src/main/java/com/smslink/module/
├── data/              # 数据层
│   ├── repository/
│   └── source/
├── domain/            # 业务层
│   ├── model/
│   └── usecase/
└── presentation/      # 表现层
    ├── ui/
    └── viewmodel/
```

## 调试技巧

### 查看日志
```bash
adb logcat | grep "SmsLink"
```

### 网络调试
使用 Wireshark 抓包分析协议:
```bash
adb shell tcpdump -i any -w /sdcard/capture.pcap
adb pull /sdcard/capture.pcap
```

### 音频调试
```bash
# 录制音频
adb shell "am broadcast -a com.smslink.DEBUG_RECORD_AUDIO"

# 导出音频文件
adb pull /sdcard/Android/data/com.smslink/files/debug_audio.pcm
```

## 常见问题

### 构建失败
1. 清理构建缓存: `./gradlew clean`
2. 同步 Gradle: `./gradlew --refresh-dependencies`
3. 检查 NDK 路径: `echo $ANDROID_NDK_HOME`

### 设备发现失败
1. 确保设备在同一网络
2. 检查防火墙设置
3. 验证 mDNS 服务是否运行

### 音频延迟过高
1. 降低缓冲区大小
2. 使用 UDP 传输
3. 启用 WebRTC 优化

## 贡献指南

1. Fork 项目
2. 创建功能分支: `git checkout -b feature/amazing-feature`
3. 提交更改: `git commit -m 'feat: add amazing feature'`
4. 推送分支: `git push origin feature/amazing-feature`
5. 创建 Pull Request

## 许可证

[待定]
