# SMS-Link

Android 设备间通知同步、通话桥接和文件传输应用。

## 功能特性

- **设备配对**: 通过 WiFi/蓝牙自动发现和配对设备
- **通知同步**: 实时同步通知到副设备
- **通话桥接**: 在副设备上接听主设备来电
- **文件传输**: 快速传输文件，支持断点续传

## 技术栈

- **语言**: Kotlin
- **UI**: Jetpack Compose + Material Design 3
- **架构**: MVVM + Clean Architecture
- **依赖注入**: Hilt
- **数据库**: Room
- **网络**: TCP/UDP + mDNS
- **音频**: Opus + WebRTC (Native C++)

## 项目结构

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

## 快速开始

### 环境要求
- JDK 17+
- Android SDK 35 (minSdk 33)
- Android NDK 27.2+ (未来音频模块需要)
- Gradle 8.7 (通过 Wrapper 提供)

### 构建

仓库已包含 Gradle Wrapper 文件,可直接使用以下命令构建:

**Unix/Linux/Mac:**
```bash
./gradlew build
```

**Windows:**
```powershell
.\gradlew.bat build
```

### 运行

**Unix/Linux/Mac:**
```bash
./gradlew :app:installDebug
```

**Windows:**
```powershell
.\gradlew.bat :app:installDebug
```

## 文档

- [开发指南](docs/development/DEVELOPMENT_GUIDE.md)
- [协议规范](docs/protocol/PROTOCOL_SPEC.md)
- [模块 API 文档](docs/modules/)

## 许可证

[待定]
